package org.jboss.eapqe.clustering.jmeter;

import org.apache.http.HeaderIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.jboss.eapqe.clustering.jmeter.util.JSessionId;
import org.jboss.eapqe.clustering.jmeter.util.LogLoader;
import org.jboss.eapqe.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom sampler for clustering scenarios. This sampler uses Apache HTTP client.
 * <p>
 * Each thread defined in JMeter test plan corresponds to one ClusteringHTTPRequestSampler instance.
 * Each ClusteringHTTPRequestSampler instance creates its own HttpClient, cookie store and holds its own counter.
 * Cookie store stores only one cookie - the one related to the requests done by this instance.
 * <p>
 * Extra functionality compared to standard JMeter HTTP Sampler:
 * <ul>
 * <li>requests validation</li>
 * <li>logging</li>
 * <li>cookie handling/resetting</li>
 * </ul>
 *
 * @author Michal Vinkler mvinkler@redhat.com
 */
public class ClusteringHTTPRequestSamplerRankedRouting extends AbstractJavaSamplerClient implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(ClusteringHTTPRequestSamplerRankedRouting.class);

    /**
     * Host parameter defined in JMeter test plan
     */
    private static final String HOST = "host";

    /**
     * Port parameter defined in JMeter test plan
     */
    private static final String PORT = "port";

    /**
     * Path parameter defined in JMeter test plan
     */
    private static final String PATH = "path";

    /**
     * HTTP request timeout
     */
    private static final String TIMEOUT = "timeout";

    /**
     * URL for request
     */
    private static String url = null;

    /**
     * HTTP client
     */
    private HttpClient client;

    /**
     * Cookie Store
     */
    private BasicCookieStore cookieStore;

    /**
     * Name of this thread for logging purposes.
     * Format: Thread Group X-Y
     */
    private String threadName;

    private static PoolingHttpClientConnectionManager poolingConnManager;
    private HttpClientBuilder httpClientBuilder;

    /**
     * JSESSIONID
     */
    private JSessionId previousJSessionId = null;
    private JSessionId currentJSessionId = null;
    private String currentJvmRoute = null;
    private String previousJvmRoute = null;
    private String currentClusterMembers = null;
    private String previousClusterMembers = null;

    private JSessionId getJSessionIdCookie(BasicCookieStore cookieStore) {
        if (cookieStore != null) {
            List<Cookie> cookies = cookieStore.getCookies();
            if (!cookies.isEmpty()) {
                //we don't rely on the fact there is only one cookie
                for (Cookie cookie : cookies) {

                    //check if the cookie name matches
                    if (cookie.getName().equals("JSESSIONID")) {

                        //split sessioId.jvmRoute String
                        String[] cookieSplit = cookie.getValue().split("\\"+ Constants.JSESSIONID_SESSION_ID_SEPARATOR, 2); // "." e.g. JSESSIONID=HMrxfyBy-MX11Xl9CYZtyu7sYLSQrl6Y82-tI78Z.WFL1:WFL2

                        //"SESSION_ID.jvmRoute" format expected
                        if (cookieSplit.length != 2) {
                            LOG.warn(
                                    String.format("%s: Malformed cookie from previous request: %s=%s", threadName, cookie.getName(), cookie.getValue())
                            );
                            cookieStore.getCookies().remove(cookie);
                        } else {
                            LOG.trace("JSESSIONID {}", cookie.getValue());
                            JSessionId jSessionId = new JSessionId();
                            jSessionId.setSessionId(cookieSplit[0]);
                            jSessionId.setJvmRoutes(cookieSplit[1].split("\\"+Constants.JSESSIONID_JVM_ROUTES_SEPARATOR)); // ":" e.g. JSESSIONID=HMrxfyBy-MX11Xl9CYZtyu7sYLSQrl6Y82-tI78Z.WFL1:WFL2
                            return jSessionId;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String join(String[] strs, String delimiter) {
        if (strs == null || delimiter == null) return null;
        StringBuilder sb = new StringBuilder();
        int cnt = 1;
        for (String str : strs) {
            sb.append(str);
            if (cnt < strs.length) sb.append(delimiter);
            cnt++;
        }
        return sb.toString();
    }

    // non-javadoc, see interface JavaSamplerClient
    public SampleResult runTest(JavaSamplerContext javaSamplerContext) {

        LogLoader.loadLogs();

        SampleResult sampleResult = new SampleResult();
        Boolean isSampleSuccessful = Boolean.TRUE;
        StringBuilder sampleMessage = new StringBuilder();
        String sampleLabel = "OK";

        try {
            /*
                JSESSIONID the cookie that is going to be sent to the server;
                This cookie contains info routing (the "WLF1.WLF2.WLF3" portion) that the load balacer will use
                to route the request narrowing the list of possible targets node to the ones listed by this cookie;
                As long as one of this nodes is still alive, the load balancer must route the request to one of them;
             */
            previousJSessionId = currentJSessionId;
            if (previousJSessionId != null) LOG.trace("{}: [PREV]JSESSIONID {}.{}", threadName, previousJSessionId.getSessionId(), join(previousJSessionId.getJvmRoutes(),Constants.JSESSIONID_JVM_ROUTES_SEPARATOR));

            /*
                From the "/clusterbench/debug" servlet we can actually know which is the node that is serving the request;
                The debug servlet makes the ""
             */
            previousJvmRoute = currentJvmRoute;
            previousClusterMembers = currentClusterMembers;

            HttpGet request = new HttpGet(url);

            request.addHeader("User-Agent", "Mozilla/5.0 (compatible; AcmeInc/1.0");

            String requestHeaders = getRequestHeadersString(request);
            LOG.trace("{}: Request headers: {}", threadName, requestHeaders);
            sampleResult.setRequestHeaders(requestHeaders);

            LOG.trace("{}: Executing the request.", threadName);
            sampleResult.sampleStart();
            HttpResponse response = client.execute(request);
            sampleResult.sampleEnd();

            sampleResult.setConnectTime(sampleResult.getTime());
            sampleResult.setBodySize(response.getEntity().getContentLength());
            sampleResult.setDataType(response.getEntity().getContentType().toString());

            LOG.trace("{}: Response time: {}, Response status: {}", threadName, sampleResult.getTime(), response.getStatusLine());

            String responseHeaders = getResponseHeadersString(response);
            LOG.trace("{}: Response headers: {}", threadName, responseHeaders);
            sampleResult.setResponseHeaders(responseHeaders);

            //If the request was successful we check the JSESSIONID cookie
            int statusCode = response.getStatusLine().getStatusCode();
            sampleResult.setResponseCode(String.valueOf(statusCode));

            String responseBody = getResponseEntityString(response);
            if (statusCode == HttpURLConnection.HTTP_OK || statusCode == HttpURLConnection.HTTP_NOT_MODIFIED)
            {
                currentJvmRoute = getJvmRoute(responseBody);
                if (currentJvmRoute == null) {
                    throw new IllegalStateException(String.format("\"Node name\" not present in response body!!"));
                }

                currentJSessionId = getJSessionIdCookie(getBasicCookieStore());
                if (currentJSessionId == null) {
                    throw new IllegalStateException(String.format("JSESSIONID is null!!"));
                } else {
                    LOG.info("{}: JSESSIONID {}.{} JVMROUTE {}"
                            , threadName
                            , currentJSessionId.getSessionId()
                            , join(currentJSessionId.getJvmRoutes(), Constants.JSESSIONID_JVM_ROUTES_SEPARATOR)
                            , (previousJvmRoute == null) ?
                                    currentJvmRoute : previousJvmRoute.equalsIgnoreCase(currentJvmRoute) ?
                                                            currentJvmRoute : String.format("%s (%s)", currentJvmRoute, previousJvmRoute)
                    );
                }

                currentClusterMembers = getClusterMembers(responseBody);
                if (currentClusterMembers == null) {
                    throw new IllegalStateException(String.format("\"Members\" not present in response body!!"));
                }

                if (previousJSessionId != null)
                {
                    /*
                        PRE-CHECK: Session ID is till the same
                     */
                    if (previousJSessionId.getSessionId().equalsIgnoreCase(currentJSessionId.getSessionId())) {
                        /*
                            FIRST: we check that the response comes from one of the nodes listed in the JSESSIONID cookie sent with the request
                         */
                        if (isValidRoute(currentJvmRoute, previousJSessionId.getJvmRoutes())) {
                            /*
                                SECOND: we check that the ranking is respected and that the response comes from the first available node
                             */
                            int currentJvmRouteRanking = getJvmRouteRanking(currentJvmRoute, currentClusterMembers, previousJSessionId.getJvmRoutes());
                            if (currentJvmRouteRanking != 1) {
                                isSampleSuccessful = Boolean.FALSE;
                                String message = String.format("%s NEW JVMROUTE %s ranking is %d! ", printContext(), currentJvmRoute, currentJvmRouteRanking);
                                sampleMessage.append("Wrong ranked JVMROUTE chosen from JSESSIONID list");
                                sampleLabel = "LOW-RANKED-JVMROUTE";
                                LOG.error(message);
                            }
                        } else {
                            isSampleSuccessful = Boolean.FALSE;
                            String message = String.format("%s NEW JVMROUTE is not in OLD JSESSIONID list! ", printContext());
                            sampleMessage.append("JVMROUTE not in JSESSIONID list");
                            sampleLabel = "JVMROUTE-NOT-IN-JSESSIONID";
                            LOG.error(message);
                        }
                    } else {
                        isSampleSuccessful = Boolean.FALSE;
                        String message = String.format("%s NEW JSESSIONID differs from OLD JSESSIONID! " , printContext());
                        sampleMessage.append("Session ID changed in JSESSIONID");
                        sampleLabel = "SESSIONID-CHANGE";
                        LOG.error(message);
                    }
                }
            } else {
                throw new IllegalStateException(String.format("%s: statusCode: %d: Invalid response: %s", threadName, statusCode, response.getStatusLine().toString()));
            }
        } catch (Exception e) {
            isSampleSuccessful = Boolean.FALSE;
            sampleMessage.append(e.getMessage());
            LOG.error("{}: Error during request execution: {}", threadName, e.getMessage(), e);
            getBasicCookieStore().clear();
            currentJSessionId = null;
            previousJSessionId = null;
            currentJvmRoute = null;
            previousJvmRoute = null;
        } finally {
            //sampleResult.sampleEnd() cannot be called twice
            if(sampleResult.getStartTime() != 0 & sampleResult.getEndTime() == 0) {
                sampleResult.sampleEnd();
            }
            sampleResult.setResponseMessage((sampleMessage.toString() == null || "".equalsIgnoreCase(sampleMessage.toString())) ? "OK" : sampleMessage.toString());
            sampleResult.setSuccessful(isSampleSuccessful);
            sampleResult.setSampleLabel(sampleLabel);
        }

        return sampleResult;
    }

    private String printContext() {
        return String.format(
                "[OLD/NEW JSESSIONID %s.%s %s.%s OLD/NEW JVMROUTE %s %s OLD/NEW CLUSTER MEMBERS \"%s\" \"%s\"]"
                , currentJSessionId.getSessionId()
                , join(currentJSessionId.getJvmRoutes(), Constants.JSESSIONID_JVM_ROUTES_SEPARATOR)
                , previousJSessionId.getSessionId()
                , join(previousJSessionId.getJvmRoutes(), Constants.JSESSIONID_JVM_ROUTES_SEPARATOR)
                , previousJvmRoute
                , currentJvmRoute
                , previousClusterMembers
                , currentClusterMembers
        );
    }

    /**
     * ranks the route; e.g. route "w4" with members "w1, w2, w3, w4, w5, w6" and routes "w4","w5" would be 1
     * @param currentJvmRoute
     * @param clusterMembers
     * @param jvmRoutes
     * @return rank of route (1 means it's the first available route)
     */
    public int getJvmRouteRanking(String currentJvmRoute, String clusterMembers, String[] jvmRoutes) {
        int index = 0;
        String[] validNodes = new String[jvmRoutes.length];
        for (int i = 0; i < jvmRoutes.length; i++) {
            if (clusterMembers.contains(jvmRoutes[i])) {
                validNodes[index++] = jvmRoutes[i];
                if (currentJvmRoute.equalsIgnoreCase(jvmRoutes[i])) {
                    return index;
                }
            }
        }
        return -1;
    }


    /**
     * checks the new
     * @param previousJvmRoute
     * @param newRoute
     * @return
     */
    private boolean isValidRoute(String newRoute, String[] previousJvmRoute) {
        if (previousJvmRoute == null || newRoute == null) {
            return true;
        }
        for (String oldRoute: previousJvmRoute) {
            if (oldRoute != null && newRoute.trim().equalsIgnoreCase(oldRoute.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read response content as String
     * @param response
     * @return
     * @throws IOException
     */
    private String getResponseEntityString(HttpResponse response) {
        StringBuilder stringBuilder = new StringBuilder();
        if (response != null) {
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                BufferedReader bufferedReader = null;
                try {
                    bufferedReader = new BufferedReader(new InputStreamReader(responseEntity.getContent()));
                    boolean keepReading = true;
                    while (keepReading) {
                        String currentLine = bufferedReader.readLine();
                        if (currentLine == null) {
                            keepReading = false;
                        } else {
                            stringBuilder.append(currentLine);
                            stringBuilder.append("\n");
                        }
                    }
                } catch (IOException err) {
                    throw new IllegalStateException(String.format("Error reading response entity: %s", err.getLocalizedMessage()));
                } finally {
                    try {
                        if (bufferedReader != null) {
                            bufferedReader.close();
                        }
                    } catch (Exception ignore) {
                    }
                }
            }
        } else {
            throw new IllegalStateException("Error reading response entity: response is null!");
        }
        return stringBuilder.toString();
    }

    /**
     * Extracts the node name from the request body (e.g. WFL1 from "... Node name: WFL1 ...")
     * @param responseBody
     * @return
     */
    public String getJvmRoute(String responseBody) {
        Pattern pattern = Pattern.compile("Node name:\\s*([^\\s\\n\\r]+)");
        Matcher matcher = pattern.matcher(responseBody);
        if (matcher.find()) {
            String route = matcher.group(1);
            return route;
        }
        return null;
    }

    public String getClusterMembers(String responseBody) {
        // Members: [wildfly4, wildfly1, wildfly2, wildfly3]
        Pattern pattern = Pattern.compile("Members:\\s*\\[([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(responseBody);
        if (matcher.find()) {
            String route = matcher.group(1);
            return route;
        }
        return null;
    }

    public String[] splitClusterMembers(String clusterMembers) {
        return clusterMembers == null ? null : clusterMembers.split("[,]\\s*");
    }

    //TODO: unify getRequestHeadersString + getResponseHeadersString
    private String getRequestHeadersString(HttpGet response) {

        StringBuilder requestHeaders = new StringBuilder();

        HeaderIterator requestHeaderIterator = response.headerIterator();

        while (requestHeaderIterator.hasNext()) {
            requestHeaders.append(requestHeaderIterator.nextHeader().toString());
            requestHeaders.append(System.getProperty("line.separator"));
        }

        return requestHeaders.toString();
    }

    private String getResponseHeadersString(HttpResponse response) {
        StringBuilder responseHeaders = new StringBuilder();

        //append "status line" as a first header, IMHO this should be automatically included
        responseHeaders.append(response.getStatusLine().toString());
        responseHeaders.append(System.getProperty("line.separator"));

        HeaderIterator responseHeaderIterator = response.headerIterator();

        while (responseHeaderIterator.hasNext()) {
            responseHeaders.append(responseHeaderIterator.nextHeader().toString());
            responseHeaders.append(System.getProperty("line.separator"));
        }
        return responseHeaders.toString();
    }

    /**
     * Define default parameters in JMeter GUI test plan.
     */
    @Override
    public Arguments getDefaultParameters() {
        Arguments defaultParameters = new Arguments();
        defaultParameters.addArgument(HOST, "${__P(" + HOST + ",localhost)}");
        defaultParameters.addArgument(PORT, "${__P(" + PORT + ",8080)}");
        defaultParameters.addArgument(PATH, "${__P(" + PATH + ",/clusterbench/session)}");
        defaultParameters.addArgument(TIMEOUT, "${__P(" + TIMEOUT + ",-1)}");

        return defaultParameters;
    }

    /**
     * Initialize client, prepare data.
     */
    @Override
    public void setupTest(JavaSamplerContext context) {

        super.setupTest(context);

        threadName = context.getJMeterContext().getThread().getThreadName();

        // define url
        url = String.format("http://%s:%s%s", context.getParameter(HOST), context.getParameter(PORT), context.getParameter(PATH));

        // timeout
        int timeout = Integer.parseInt(context.getParameter(TIMEOUT));
        LOG.trace("JMETER-URL: {} TIMEOUT {}", url, timeout);
        // initialize HTTP client
        client = getHttpClientBuilder(timeout).build();
    }

    /**
     * Close the client.
     */
    @Override
    public void teardownTest(JavaSamplerContext context) {

        super.teardownTest(context);

        HttpClientUtils.closeQuietly(client);
    }

    /**
     * There must be just one
     * @return
     */
    private static PoolingHttpClientConnectionManager getPoolingHttpClientConnectionManager() {
        if (poolingConnManager == null) {
            poolingConnManager = new PoolingHttpClientConnectionManager();
            poolingConnManager.setDefaultMaxPerRoute(500);
            poolingConnManager.setMaxTotal(800);
            poolingConnManager.setValidateAfterInactivity(200);
        }
        return poolingConnManager;
    }

    /**
     * cookie store must be thread specific
     * @return
     */
    private BasicCookieStore getBasicCookieStore() {
        if (this.cookieStore == null) {
            this.cookieStore = new BasicCookieStore();
        }
        return this.cookieStore;
    }

    /**
     * client builder, and hence cookie store, must be thread specific
     * @param timeout
     * @return
     */
    private HttpClientBuilder getHttpClientBuilder(int timeout){
        if (this.httpClientBuilder==null){

            this.httpClientBuilder = HttpClientBuilder.create().setConnectionManager(getPoolingHttpClientConnectionManager());
            this.httpClientBuilder.setDefaultCookieStore(getBasicCookieStore());
            if (timeout > 0) {
                RequestConfig config = RequestConfig.custom()
                        .setConnectTimeout(timeout * 1000)
                        .setConnectionRequestTimeout(timeout * 1000)
                        .setSocketTimeout(timeout * 1000).build();
                this.httpClientBuilder.setDefaultRequestConfig(config);
            }
        }
        return this.httpClientBuilder;
    }
}
