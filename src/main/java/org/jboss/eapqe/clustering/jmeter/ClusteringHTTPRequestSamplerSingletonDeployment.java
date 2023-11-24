package org.jboss.eapqe.clustering.jmeter;

import org.apache.http.HeaderIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.jboss.eapqe.clustering.jmeter.util.CircularList;
import org.jboss.eapqe.clustering.jmeter.util.JSessionId;
import org.jboss.eapqe.clustering.jmeter.util.LogLoader;
import org.jboss.eapqe.clustering.jmeter.util.MiscHelpers;
import org.jboss.eapqe.clustering.jmeter.util.ResponseWrapper;
import org.jboss.eapqe.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

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
 * @author Tommaso Borgato tborgato@redhat.com
 */
public class ClusteringHTTPRequestSamplerSingletonDeployment extends AbstractJavaSamplerClient implements Serializable {


    private static final Logger LOG = LoggerFactory.getLogger(ClusteringHTTPRequestSamplerSingletonDeployment.class);

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
    private String url = null;

    /**
     * HTTP client
     */
    private CloseableHttpClient client;

    /**
     * Cookie Store
     */
    private BasicCookieStore cookieStore;

    /**
     * Counter for request validation.
     */
    private int serial = 0;

    /**
     * Name of this thread for logging purposes.
     * Format: Thread Group X-Y
     */
    private String threadName;

    private static PoolingHttpClientConnectionManager poolingConnManager;
    private HttpClientBuilder httpClientBuilder;
    private boolean isJvmRouteChange = false;
    private String hosts, ports;
    /**
     * JMeter Sample
     */
    private Boolean isSampleSuccessful = null;
    private String sampleLabel = null;
    private String sampleMessage = null;
    private String sampleResponseData = null;
    private List<String> logs = new ArrayList<>();
    private ResponseWrapper responseWrapper = null;
    private boolean firstRound = true;
    /**
     * URL for request
     */
    private CircularList<String> urls = null;
    private int currentUrlIndex, previousUrlIndex = 0;

    private MiscHelpers miscHelpers;

    private MiscHelpers getMiscHelpers() {
        if (this.miscHelpers == null) {
            this.miscHelpers = new MiscHelpers();
        }
        return this.miscHelpers;
    }

    /**
     * Read response content as String
     * @param response
     * @return
     * @throws IOException
     */
    private String getResponseEntityString(CloseableHttpResponse response) {
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
        }
        return stringBuilder.toString();
    }

    /**
     * Executes HTTP GET 
     * @param url
     * @return
     * @throws IOException
     */
    private ResponseWrapper executeGet(final String url) throws IOException {
        ResponseWrapper responseWrapper = new ResponseWrapper();
        CloseableHttpResponse response = null;
        HttpGet request = new HttpGet(url);
        request.addHeader("User-Agent", "Mozilla/5.0 (compatible; AcmeInc/1.0");
        responseWrapper.requestHeaders = getRequestHeadersString(request);
        responseWrapper.previousJSessionId = responseWrapper.currentJSessionId;

        try {
            response = client.execute(request);
            responseWrapper.responseCode = response.getStatusLine().getStatusCode();
            responseWrapper.statusLine = response.getStatusLine().toString();
            responseWrapper.currentJSessionId = getJSessionIdCookie(getBasicCookieStore());
            responseWrapper.responseBody = getResponseEntityString(response);
            responseWrapper.bodySize = response.getEntity().getContentLength();
            responseWrapper.dataType = response.getEntity().getContentType().toString();
            responseWrapper.responseHeaders = getResponseHeadersString(response);
        } finally {
            try {if (response != null) EntityUtils.consumeQuietly(response.getEntity());} catch (Exception ignore){}
        }
        LOG.trace("{}: {}: {}: {}", threadName, url, responseWrapper.responseCode, responseWrapper.responseBody);
        return responseWrapper;
    }

    // non-javadoc, see interface JavaSamplerClient
    public SampleResult runTest(JavaSamplerContext javaSamplerContext) {

        LogLoader.loadLogs();

        SampleResult sampleResult = null;
        previousUrlIndex = currentUrlIndex;

        threadName = javaSamplerContext.getJMeterContext().getThread().getThreadName();

        int nodeLeft = urls.size();
        JSessionId lastJSessionId = null;
        while (nodeLeft > 0) {

            // let's check next url
            url = urls.get(currentUrlIndex);

            try {
                sampleResult = new SampleResult();
                sampleResult.setSampleLabel("clusterbench");

                isSampleSuccessful = Boolean.FALSE;
                sampleLabel = null;
                sampleMessage = null;
                sampleResponseData = null;

                sampleResult.sampleStart();
                responseWrapper = executeGet(url);
                sampleResult.sampleEnd();

                if (responseWrapper.responseCode != HttpURLConnection.HTTP_OK) {
                    sampleLabel = "Singleton Deployment NOT FOUND";
                    throw new IllegalStateException(
                            String.format("Error with url '%s': %s: %s", url, responseWrapper.statusLine, responseWrapper.responseBody)
                    );
                }

                // JSESSIONID
                if (responseWrapper.currentJSessionId == null) {
                    sampleLabel = "NO-JSESSIONID-COOKIE";
                    sampleMessage = "No JSESSIONID cookie in response";
                    throw new IllegalStateException(String.format("No JSESSIONID cookie from url %s: %s", url, responseWrapper.statusLine));
                }
                //check if sessionId matches
                if (responseWrapper.previousJSessionId == null) {
                    LOG.trace("{}: Serial {}: Session assigned: {}.{}", threadName, serial, responseWrapper.currentJSessionId.getSessionId(), responseWrapper.currentJSessionId.getJvmRoutes()[0]);
                } else if (!responseWrapper.previousJSessionId.getSessionId().equalsIgnoreCase(responseWrapper.currentJSessionId.getSessionId())) {
                    LOG.trace("{}: Serial {}: Session changed: {}.{}", threadName, serial, responseWrapper.currentJSessionId.getSessionId(), responseWrapper.currentJSessionId.getJvmRoutes()[0]);
                }
                //check if jvmRoute matches, or log assignment of the new one
                if (responseWrapper.previousJSessionId != null && !responseWrapper.previousJSessionId.getJvmRoutes()[0].equalsIgnoreCase(responseWrapper.currentJSessionId.getJvmRoutes()[0])) {
                    LOG.trace("{}: Serial {}: JvmRoute changed. {} -> {}", threadName, serial, responseWrapper.previousJSessionId.getJvmRoutes()[0], responseWrapper.currentJSessionId.getJvmRoutes()[0]);
                }

                sampleLabel = "OK";
                sampleMessage = "Singleton Deployment UP";
                isSampleSuccessful = Boolean.TRUE;
                lastJSessionId = responseWrapper.currentJSessionId;
                /*
                    LOGS
                 */
                logs.add(
                        String.format("%s: OK %s: %d: %s", threadName, url, responseWrapper.responseCode, responseWrapper.responseBody)
                );
                /*
                    if we have our "OK" response, we don't need to try other nodes
                 */
                break;
            } catch (Exception err) {
                /*
                    NEXT URL
                 */
                currentUrlIndex++;
                if (currentUrlIndex == urls.size()) {currentUrlIndex = 0;}
                isSampleSuccessful = Boolean.FALSE;
                lastJSessionId = responseWrapper.currentJSessionId;
                if (sampleLabel == null) { sampleLabel = "Singleton Deployment ERROR"; }
                if (sampleMessage == null) { sampleMessage = err.getLocalizedMessage(); }
                /*
                    LOGS
                 */
                logs.add(
                        String.format("%s: ERR %s: %d: %s", threadName, url, -1, err.getLocalizedMessage())
                );
                /*
                    RESET
                 */
                getBasicCookieStore().clear();
                responseWrapper.currentJSessionId = null;
                responseWrapper.previousJSessionId = null;
                serial = 0;
            } finally {
                /*
                    DECREASE CHANCES
                 */
                nodeLeft--;
            }
        }

        //sampleResult.sampleEnd() cannot be called twice
        if(sampleResult.getStartTime() != 0 & sampleResult.getEndTime() == 0) { sampleResult.sampleEnd(); }
        sampleResult.setSuccessful(isSampleSuccessful);
        sampleResult.setSampleLabel(sampleLabel == null ? "?" : sampleLabel);
        sampleResult.setResponseMessage((sampleMessage == null) ? "?" : sampleMessage);
        sampleResult.setResponseCode(Integer.toString(responseWrapper.responseCode));
        if (sampleResponseData != null) {sampleResult.setResponseData(sampleResponseData, null);}

        sampleResult.setConnectTime(sampleResult.getTime());
        sampleResult.setBodySize(responseWrapper.bodySize);
        sampleResult.setDataType(responseWrapper.dataType);
        sampleResult.setRequestHeaders(responseWrapper.requestHeaders);
        sampleResult.setResponseHeaders(responseWrapper.responseHeaders);
        if (responseWrapper.responseBody != null) sampleResult.setResponseData(responseWrapper.responseBody.getBytes());

        if (!isSampleSuccessful) {
            String msg = "";
            if (logs != null) {
                for (String l : logs) {
                    if (l != null && l.contains("404 - Not Found")) msg = " HTTP/1.1 404 Not Found";
                }
            }
            LOG.error(
                    "{}: JSESSIONID {}{}{}{}\n" +
                            "==================================================\n" +
                            "{}\n" +
                            "==================================================",
                    threadName,
                    (lastJSessionId != null) ? lastJSessionId.getSessionId() : "?",
                    Constants.JSESSIONID_SESSION_ID_SEPARATOR,
                    (lastJSessionId != null) ? lastJSessionId.getJvmRoutes()[0] : "?",
                    msg,
                    String.join("\n", logs)
            );
        } else if (!firstRound && (previousUrlIndex != currentUrlIndex)) {
            LOG.trace(
                    "{}: JSESSIONID {}{}{}: url change '{}' --> '{}'",
                    threadName,
                    (lastJSessionId != null) ? lastJSessionId.getSessionId() : "?",
                    Constants.JSESSIONID_SESSION_ID_SEPARATOR,
                    (lastJSessionId != null) ? lastJSessionId.getJvmRoutes()[0] : "?",
                    urls.get(previousUrlIndex),
                    urls.get(currentUrlIndex)
            );
        }
        logs.clear();
        firstRound = false;

        return sampleResult;
    }

    /**
     * Get the JSESSIONID cookie
     * @param cookieStore
     * @return
     */
    private JSessionId getJSessionIdCookie(BasicCookieStore cookieStore) {
        if (cookieStore != null) {
            List<Cookie> cookies = cookieStore.getCookies();
            if (cookies != null && !cookies.isEmpty()) {
                //we don't rely on the fact there is only one cookie
                for (Cookie cookie : cookies) {

                    //check if the cookie name matches
                    if (cookie.getName().equals("JSESSIONID")) {

                        //split sessioId.jvmRoute String
                        String[] cookieSplit = cookie.getValue().split("\\"+ Constants.JSESSIONID_SESSION_ID_SEPARATOR, 2); // "." e.g. JSESSIONID=HMrxfyBy-MX11Xl9CYZtyu7sYLSQrl6Y82-tI78Z.WFL1

                        //"SESSION_ID.jvmRoute" format expected
                        if (cookieSplit.length != 2) {
                            LOG.warn("{}: Malformed cookie from previous request: {}={}", threadName, cookie.getName(), cookie.getValue());
                            cookieStore.getCookies().remove(cookie);
                        } else {
                            LOG.trace("JSESSIONID {}", cookie.getValue());
                            JSessionId jSessionId = new JSessionId();
                            jSessionId.setSessionId(cookieSplit[0]);
                            jSessionId.setJvmRoutes(new String[]{cookieSplit[1]}); // no multiple JVM routes here
                            return jSessionId;
                        }
                    }
                }
            }
        }
        return null;
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

        // define urlOfHttpRemotingConnector - we expect a comma-delimited list of 1..n hosts in HOST property and
        // one port (same port for each host) or a comma-delimited list of 1..n ports in PORT property
        hosts = context.getJMeterProperties().getProperty(HOST);
        if (hosts == null) {
            hosts = context.getParameter(HOST);
        }
        ports = context.getJMeterProperties().getProperty(PORT);
        if (ports == null) {
            ports = context.getParameter(PORT);
        }
        String msg1 = String.format(
                "hosts: %s ports: %s",
                hosts, ports
        );

        // define url e.g. http://10.0.153.188:8080/second-level-cache-web
        urls = getMiscHelpers().getUrls(hosts, ports, context.getParameter(PATH), threadName, ",");

        // timeout
        int timeout = Integer.parseInt(context.getParameter(TIMEOUT));

        LOG.trace("{} --> JMETER-URLS: {} TIMEOUT {}", msg1, String.join(",", urls), timeout);

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
