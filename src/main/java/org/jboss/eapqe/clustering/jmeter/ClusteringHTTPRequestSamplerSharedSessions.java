package org.jboss.eapqe.clustering.jmeter;

import org.apache.http.HeaderIterator;
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
import org.jboss.eapqe.clustering.jmeter.util.LogLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
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
 * @author Michal Vinkler mvinkler@redhat.com
 */
public class ClusteringHTTPRequestSamplerSharedSessions extends AbstractJavaSamplerClient implements Serializable {


    private static final Logger LOG = LoggerFactory.getLogger(ClusteringHTTPRequestSamplerSharedSessions.class);

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
     * URL #1 for request
     */
    private static String urlOne = null;

    /**
     * URL #2 for request
     */
    private static String urlTwo = null;

    private boolean first = true;

    /**
     * HTTP client
     */
    private HttpClient client;

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

    /**
     * Stores the session id from previous request for comparison.
     */
    private String previousSessionId;

    /**
     * Stores the jvmRoute from previous request for comparison.
     */
    private String previousJvmRoute;

    private static PoolingHttpClientConnectionManager poolingConnManager;
    private HttpClientBuilder httpClientBuilder;

    // non-javadoc, see interface JavaSamplerClient
    public SampleResult runTest(JavaSamplerContext javaSamplerContext) {

        LogLoader.loadLogs();

        SampleResult sampleResult = new SampleResult();
        sampleResult.setSampleLabel("ClusterbenchRequest");

        HttpGet request = null;
        HttpResponse response = null;
        try {

            storePreviousCookie();

            //round-robin
            String url = (first) ? urlOne : urlTwo;

            LOG.trace("{}: Serial {}: Request path: {}", threadName, serial, url);
            request = new HttpGet(url);

            first = !first;

            //TODO: correct headers for request
            request.addHeader("User-Agent", "Mozilla/5.0 (compatible; AcmeInc/1.0");

            String requestHeaders = getRequestHeadersString(request);
            LOG.trace("{}: Serial {}: Request headers: {}", threadName, serial, requestHeaders);
            sampleResult.setRequestHeaders(requestHeaders);

            LOG.trace("{}: Serial {}: Executing the request.", threadName, serial);
            sampleResult.sampleStart();
            response = client.execute(request);
            sampleResult.sampleEnd();

            //TODO: fix these fields
            sampleResult.setConnectTime(sampleResult.getTime());
            sampleResult.setBodySize(response.getEntity().getContentLength());
//            sampleResult.setDataEncoding(response.getEntity().getContentEncoding().toString()); //throws NPE
            sampleResult.setDataType(response.getEntity().getContentType().toString());

            LOG.trace("{}: Serial {}: Response time: {}, Response status: {}", threadName, serial, sampleResult.getTime(), response.getStatusLine());

            String responseHeaders = getResponseHeadersString(response);
            LOG.trace("{}: Serial {}: Response headers: {}", threadName, serial, responseHeaders);
            sampleResult.setResponseHeaders(responseHeaders);

            //there is only one cookie stored in the cookie manager, if the request was successful
            if(!getBasicCookieStore().getCookies().isEmpty()) {
                Cookie jSessionIdCookie = getBasicCookieStore().getCookies().get(0);
                LOG.trace("{}: Serial {}: Cookie: {}={}", threadName, serial, jSessionIdCookie.getName(), jSessionIdCookie.getValue());
                checkIfCookieMatches(jSessionIdCookie);
            } else {
                LOG.trace("{}: Serial {}: No cookie in CookieStore. Response status code: {} ", threadName, serial, response.getStatusLine().getStatusCode());
            }

            validateResponse(sampleResult, response);

        } catch (Exception e) {

            LOG.error("{}: Serial {}: Error during request execution: {}", threadName, serial, e.getMessage(), e);

            //sampleResult.sampleEnd() cannot be called twice
            if(sampleResult.getStartTime() != 0 & sampleResult.getEndTime() == 0) {
                sampleResult.sampleEnd();
            }
            sampleResult.setResponseMessage(e.getMessage());
            sampleResult.setSuccessful(Boolean.FALSE);
        }

        return sampleResult;
    }

    /**
     * When called, the method checks for a cookie in the cookiestore, checks if the cookie name matches,
     * parses sessionId and jvmRoute from the cookie and stores them in previousSessionId, previousJvmRoute fields
     * (in the case there is a cookie stored in the cookiestore, otherwise it does nothing).
     */
    private void storePreviousCookie() {
        List<Cookie> cookies = getBasicCookieStore().getCookies();

        if(!cookies.isEmpty()) {
            //we don't rely on the fact there is only one cookie
            for (Cookie jSessionIdCookie: getBasicCookieStore().getCookies()) {

                //check if the cookie name matches
                if (jSessionIdCookie.getName().equals("JSESSIONID")) {

                    //split sessioId.jvmRoute String
                    String[] cookieSplit = jSessionIdCookie.getValue().split("\\.", 2);

                    //"SESSION_ID.jvmRoute" format expected
                    if (cookieSplit.length != 2) {
                        //throw new RuntimeException(String.format("%s: Serial %d: Unknown cookie stored in a cookie store: %s=%s", threadName, serial, jSessionIdCookie.getName(), jSessionIdCookie.getValue()));
                        LOG.warn(
                                String.format("%s: Serial %d: Malformed cookie from previous request: %s=%s", threadName, serial, jSessionIdCookie.getName(), jSessionIdCookie.getValue())
                        );
                        getBasicCookieStore().getCookies().remove(jSessionIdCookie);
                    } else {
                        LOG.trace("JSESSIONID {}", jSessionIdCookie.getValue());
                    }

                    //store values from cookie stored in a store == cookie from previous request
                    previousSessionId = cookieSplit[0];
                    previousJvmRoute = cookieSplit[1];

                } else {
                    //throw new RuntimeException(String.format("%s: Serial %d: Unknown cookie stored in a cookie store: %s=%s", threadName, serial, jSessionIdCookie.getName(), jSessionIdCookie.getValue()));
                    LOG.trace(
                            String.format("%s: Serial %d: Unknown cookie from previous request: %s=%s", threadName, serial, jSessionIdCookie.getName(), jSessionIdCookie.getValue())
                    );
                    getBasicCookieStore().getCookies().remove(jSessionIdCookie);
                }
            }
        }
    }

    /**
     * Checks if sessionId and jvmRoute stored using storePreviousCookie() from previous request match the values
     * from the current request. If not, the change is logged.
     * If previous values were null, new assingment is logged.
     *
     * @param jSessionIdCookie
     *            the cookie to be checked
     */
    private void checkIfCookieMatches(Cookie jSessionIdCookie) {
        //split sessioId.jvmRoute String
        String[] cookieSplit = jSessionIdCookie.getValue().split("\\.",2);

        //"SESSION_ID.jvmRoute" format expected
        if (cookieSplit.length != 2) {
            throw new RuntimeException(String.format("%s: Serial %d: Unknown cookie stored in a cookie store: %s=%s", threadName, serial, jSessionIdCookie.getName(), jSessionIdCookie.getValue()));
        }

        String newSessionId = cookieSplit[0];
        String newRoute = cookieSplit[1];

        //check if sessionId matches
        if (!newSessionId.equals(previousSessionId)) {
            if (previousSessionId == null) {
                LOG.trace("{}: Serial {}: Session assigned: {}", threadName, serial, newSessionId);
            } else {
                LOG.trace("{}: Serial {}: Session changed: {}", threadName, serial, newSessionId);
            }
            previousSessionId = newSessionId;
        }

        //check if jvmRoute matches, or log assignment of the new one
        if (!newRoute.equals(previousJvmRoute)) {
            if (previousJvmRoute == null) {
                LOG.debug("{}: JSESSIONID: {} Serial {}: JvmRoute assigned: {}", threadName, newSessionId, serial, newRoute);
            } else {
                LOG.debug("{}: JSESSIONID: {} Serial {}: JvmRoute changed. {} -> {}", threadName, newSessionId, serial, previousJvmRoute, newRoute);
            }
            previousJvmRoute = newRoute;
        }
    }

    /**
     * Contains custom validation logic for clustering scenarios.
     * Assumes response content contains only one number, representing counter for this session returned from cluster.
     * Compares the number against internally stored counter. Values must match, otherwise the sample is considered
     * invalid.
     *
     * @param sampleResult
     *              sampleResult instance for storing the validation results
     * @param response
     *             HTTP response for reading the content and status code
     * @throws IOException
     *             If an I/O error occurs during reading the response data
     */
    private void validateResponse(SampleResult sampleResult, HttpResponse response) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

        //we expect only one line is returned, containing only the counter number, nothing else
        String numberFromServer = rd.readLine();
        rd.close();

        LOG.trace("{}: Serial {}: Message: {}", threadName, serial, numberFromServer);
        sampleResult.setResponseData(numberFromServer, null); //use platform-default encoding

        int statusCode = response.getStatusLine().getStatusCode();
        sampleResult.setResponseCode(Integer.toString(statusCode));

        //if HTTP/200 was returned, continue with custom validation
        if (statusCode == HttpURLConnection.HTTP_OK || statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {

            //compare internal counter and returned value
            if (String.valueOf(serial).equalsIgnoreCase(numberFromServer)) {
                sampleResult.setSuccessful(Boolean.TRUE);
                sampleResult.setResponseMessage("200 OK: " + serial + "/" + numberFromServer);

                serial++;

                //mark the result as not valid, reset counter and cookiestore
            } else {

                //TODO: some better validation logic:
                //new session (based on information from checkIfCookieMatches()/stale data (counters differ exactly by 1)?
                sampleResult.setSuccessful(Boolean.FALSE);
                sampleResult.setResponseMessage("200 OK: Serial mismatch: " + serial + "/" + numberFromServer);

                getBasicCookieStore().clear();
                int tmpSerial = serial;
                serial = 0;
                previousSessionId = null;
                previousJvmRoute = null;

                //we can log error, throw an exception with full/trimmed stacktrace, or both
//                LOG.error("{}: Serial {}: Invalid response: Expected {}, received {}", threadName, serial, serial, numberFromServer);
                throw new IllegalStateException(String.format("%s: Serial %d: Invalid response: Expected %d, received %s", threadName, tmpSerial, tmpSerial, numberFromServer));
            }

            //if HTTP/200 was not returned, mark the result as not valid, reset counter and cookiestore
        } else {

            sampleResult.setSuccessful(Boolean.FALSE);
            sampleResult.setResponseMessage(response.getStatusLine().getReasonPhrase());

            getBasicCookieStore().clear();
            int tmpSerial = serial;
            serial = 0;
            previousSessionId = null;
            previousJvmRoute = null;

            //we can log error, throw an exception with full/trimmed stacktrace, or both
//            LOG.error("{}: Serial {}: Invalid response: {}", threadName, serial, response.getStatusLine().toString());
            throw new IllegalStateException(String.format("%s: statusCode: %d Serial %d: Invalid response: %s", threadName, statusCode, tmpSerial, response.getStatusLine().toString()));
        }
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

        String[] split = context.getParameter(PATH).split(",");

        if(split.length != 2) {
            throw new IllegalArgumentException(String.format("%s: Serial %d: Expected 2 paths to be provided, but got %d", threadName, serial, split.length));
        }

        // define urls
        urlOne = String.format("http://%s:%s%s", context.getParameter(HOST), context.getParameter(PORT), split[0]);
        urlTwo = String.format("http://%s:%s%s", context.getParameter(HOST), context.getParameter(PORT), split[1]);

        // timeout
        int timeout = Integer.parseInt(context.getParameter(TIMEOUT));
        LOG.trace("JMETER-URL-1: {} JMETER-URL-2: {} TIMEOUT {}", urlOne, urlTwo, timeout);

        // initialize HTTP client
        client = getHttpClientBuilder(timeout).build();
        // initialize serial number to be received from server
        serial = 0;
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
