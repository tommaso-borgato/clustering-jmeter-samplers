package org.jboss.eapqe.clustering.jmeter;

import org.apache.http.HeaderIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.config.Registry;
import org.apache.http.conn.util.PublicSuffixMatcher;
import org.apache.http.conn.util.PublicSuffixMatcherLoader;
import org.apache.http.cookie.Cookie;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.jboss.eapqe.clustering.jmeter.util.LogLoader;
import org.jboss.eapqe.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Custom sampler for clustering scenarios. This sampler uses Apache HTTP client.
 * <p>
 * Each thread defined in JMeter test plan corresponds to one ClusteringHTTPRequestSampler instance.
 * Each ClusteringHTTPRequestSampler instance creates its own HttpClient, cookie store and holds its own counter.
 * Cookie store stores only one cookie - the one related to the requests done by this instance.
 * </p>
 * <p>
 * In SSO, there is a new JSESSIONIDSSO cookie that represents an authenticated client;
 * This JSESSIONIDSSO cookie lifecycle is distinct from then JSESSIONID cookie: if you logout or invalidate the session
 * (hence invalidating JSESSIONID cookie) the JSESSIONIDSSO cookie is still valid;
 * </p>
 * <p>
 * In SSO we test:
 * <ul>
 *     <li>On JVRoute change, the client does not need to re-authenticate</li>
 *     <li>If we have two applications in the same security domain, the client just authenticates once</li>
 * </ul>
 * Note we don't test the number returned from the server because it's held in a different cache than the one used for SSO
 * data and, here, we just tests the cache for used for SSO data;
 * </p>
 * Extra functionality compared to standard JMeter HTTP Sampler:
 * <ul>
 * <li>requests validation</li>
 * <li>logging</li>
 * <li>cookie handling/resetting</li>
 * </ul>
 *
 * @author Tommaso Borgato tborgato@redhat.com
 */
public class ClusteringHTTPRequestSamplerSSO extends AbstractJavaSamplerClient implements Serializable {


    private static final Logger LOG = LoggerFactory.getLogger(ClusteringHTTPRequestSamplerSSO.class);

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
    private String serial = "0";

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

    /**
     * Stores the current session id
     */
    private Cookie jSessionId;

    /**
     * Stores the current SSO session id
     */
    private Cookie jSessionIdSso;
    private Date jSessionIdSsoTimestamp = null;

    /**
     * Stores the SSO session id from previous request for comparison.
     */
    private Cookie previousJSessionIdSso;
    private long elapsedSecondsSinceLastAuthentication = 9999999;

    private static PoolingHttpClientConnectionManager poolingConnManager;
    private HttpClientBuilder httpClientBuilder;
    private Registry<CookieSpecProvider> cookieSpecProvider;

    // non-javadoc, see interface JavaSamplerClient
    public SampleResult runTest(JavaSamplerContext context) {

        LogLoader.loadLogs();

        threadName = context.getJMeterContext().getThread().getThreadName();

        SampleResult sampleResult = new SampleResult();
        sampleResult.setSampleLabel("ClusterbenchRequest");
        // we start positive ....
        sampleResult.setSuccessful(Boolean.TRUE);
        sampleResult.setResponseCodeOK();
        sampleResult.setResponseMessageOK();

        try {

            storePreviousCookie();

            HttpGet request;

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
            HttpResponse response = client.execute(request);
            sampleResult.sampleEnd();

            sampleResult.setConnectTime(sampleResult.getTime());
            sampleResult.setBodySize(response.getEntity().getContentLength());
            sampleResult.setDataType(response.getEntity().getContentType().toString());

            LOG.trace("{}: Serial {}: Response time: {}, Response status: {}", threadName, serial, sampleResult.getTime(), response.getStatusLine());

            String responseHeaders = getResponseHeadersString(response);
            LOG.trace("{}: Serial {}: Response headers: {}", threadName, serial, responseHeaders);
            sampleResult.setResponseHeaders(responseHeaders);

            String responseEntityString = getResponseEntityString(response);
            LOG.trace("{}: Serial {}: First Response \n{}", threadName, serial, responseEntityString);

            // Check whether authentication is needed
            if (requiresAuthentication(response.getStatusLine().getStatusCode(), responseEntityString)) {
                response = formAuth(request, sampleResult);
                responseEntityString = getResponseEntityString(response);

                sampleResult.setBodySize(response.getEntity().getContentLength());
                sampleResult.setDataType(response.getEntity().getContentType().toString());
                responseHeaders = getResponseHeadersString(response);
                sampleResult.setResponseHeaders(responseHeaders);
            }

            // If the request was successful we check the JSESSIONID cookie
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == HttpURLConnection.HTTP_OK || statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (!getBasicCookieStore().getCookies().isEmpty()) {
                    Cookie jSessionIdCookie = getJSessionIdCookie();
                    if (jSessionIdCookie != null) {
                        LOG.trace("{}: Serial {}: Cookie: {}={}", threadName, serial, jSessionIdCookie.getName(), jSessionIdCookie.getValue());
                        checkIfCookieMatches(jSessionIdCookie, sampleResult, statusCode);
                    } else {
                        throw new IllegalStateException(String.format("No JSESSIONID cookie from url %s: %s", url, response.getStatusLine().toString()));
                    }
                } else {
                    throw new IllegalStateException(String.format("No cookies in CookieStore from url %s: %s", url, response.getStatusLine().toString()));
                }
            } else {
                throw new IllegalStateException(String.format("Invalid StatusCode %d from url %s: %s", statusCode, url, response.getStatusLine().toString()));
            }

            serial = responseEntityString;

        } catch (Exception e) {

            LOG.error("{}: Serial {}: Error during request execution: {}", threadName, serial, e.getMessage(), e);

            // sampleResult.sampleEnd() cannot be called twice
            if(sampleResult.getStartTime() != 0 & sampleResult.getEndTime() == 0) {
                sampleResult.sampleEnd();
            }
            sampleResult.setResponseMessage(e.getMessage());
            sampleResult.setSuccessful(Boolean.FALSE);

            getBasicCookieStore().clear();
            serial = "0";
            previousSessionId = null;
            previousJvmRoute = null;
        }

        return sampleResult;
    }

    /**
     * AUTH Request
     * @param request
     * @return HTTP response
     * @throws UnsupportedEncodingException
     */
    private HttpResponse formAuth(HttpGet request, SampleResult sampleResult) throws UnsupportedEncodingException {
        // executing post method to /j_security_check
        String postUrl = request.getURI().toString().replaceFirst("[^/]+$", "j_security_check");
        LOG.trace("{}: Serial {}: About to authenticate with FORM authentication to {} with {}/{} ...", threadName, serial, postUrl, Constants.ssoUser, Constants.ssoPassw);
        HttpPost postMethod = new HttpPost(postUrl);
        postMethod.setHeader("Referer", request.getURI().toString());
        postMethod.setHeader("Content-Type", "application/x-www-form-urlencoded");

        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("j_username", Constants.ssoUser));
        params.add(new BasicNameValuePair("j_password", Constants.ssoPassw));
        postMethod.setEntity(new UrlEncodedFormEntity(params));

        HttpResponse authResponse;
        try{
            authResponse = client.execute(postMethod);
            boolean ssoCookieExists = jSessionIdSsoCookieExists();
            if (authResponse.getStatusLine().getStatusCode() >= 400) {
                sampleResult.setSuccessful(Boolean.FALSE);
                sampleResult.setResponseCode(String.valueOf(authResponse.getStatusLine().getStatusCode()));
                sampleResult.setResponseMessage(authResponse.getStatusLine().getReasonPhrase());
                throw new IllegalStateException(String.format("AUTH REQUEST ERROR: INVALID STATUS CODE: %s", authResponse.getStatusLine().toString()));
            } else if (!ssoCookieExists) {
                sampleResult.setSuccessful(Boolean.FALSE);
                sampleResult.setResponseCode(String.valueOf(authResponse.getStatusLine().getStatusCode()));
                sampleResult.setResponseMessage("No JSESSIONIDSSO cookie in authorization response!");
                throw new IllegalStateException(String.format("AUTH REQUEST ERROR: NO JSESSIONIDSSO COOKIE: %s", authResponse.getStatusLine().toString()));
            } else if (getElapsedSecondsSinceLastAuthentication(true) < 300) {
                sampleResult.setSuccessful(Boolean.FALSE);
                sampleResult.setResponseCode(String.valueOf(authResponse.getStatusLine().getStatusCode()));
                sampleResult.setResponseMessage("AUTH ERROR: Less than 5 minutes since last authentication!");
                LOG.error("{}: Serial {}: seconds {}: Less than 5 minutes since last authentication JSESSIONIDSSO OLD {} NEW {} JSESSIONID {}",
                        threadName, serial,
                        getElapsedSecondsSinceLastAuthentication(false), previousJSessionIdSso.getValue(), jSessionIdSso.getValue(), jSessionId.getValue());
            } else {
                LOG.trace("{}: Serial {}: AUTH OK: \nJSESSIONIDSSO {} / {} / {} \nJSESSIONID {} / {} / {}\nCODE: {}",
                        threadName, serial,
                        jSessionIdSso.getPath(), jSessionIdSso.getDomain(), jSessionIdSso.getValue(),
                        jSessionId.getPath(),  jSessionId.getDomain(), jSessionId.getValue(),
                        authResponse.getStatusLine().getStatusCode()
                );
            }
        } catch (IOException err) {
            throw new IllegalStateException(String.format("FORM AUTH ERROR REACHING %s: %s", postUrl, err.getMessage()));
        }
        return authResponse;
    }

    private boolean jSessionIdSsoCookieExists() {
        jSessionIdSso = null;
        jSessionId = null;
        List<Cookie> cookies = getBasicCookieStore().getCookies();
        if(!cookies.isEmpty()) {
            Iterator<Cookie> cookieIterator = cookies.iterator();
            while (cookieIterator.hasNext()) {
                Cookie cookie = cookieIterator.next();
                //check if the cookie name matches
                if (cookie.getName().equals("JSESSIONIDSSO")) {
                    jSessionIdSso = cookie;
                } else if (cookie.getName().equals("JSESSIONID")) {
                    jSessionId = cookie;
                }
            }
            if (jSessionIdSso != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check that more than five minutes elapsed since last authentication
     */
    private long getElapsedSecondsSinceLastAuthentication(boolean update) {
        if (update) {
            Date now = new Date(System.currentTimeMillis());
            if (jSessionIdSsoTimestamp != null) {
                long diffInMillies = Math.abs(now.getTime() - jSessionIdSsoTimestamp.getTime());
                elapsedSecondsSinceLastAuthentication = TimeUnit.SECONDS.convert(diffInMillies, TimeUnit.MILLISECONDS);
            }
            jSessionIdSsoTimestamp = now;
        }
        return elapsedSecondsSinceLastAuthentication;
    }

    private boolean requiresAuthentication(int statusCode, String  responseEntityString) {
        return responseEntityString != null && responseEntityString.toLowerCase().contains("j_security_check") && responseEntityString.toLowerCase().contains("<form ");
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

    private Cookie getJSessionIdCookie() {
        for (Cookie cookie : getBasicCookieStore().getCookies()) {
            if (cookie.getName().equals("JSESSIONID")) {
                return cookie;
            }
        }
        return null;
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
            for (Cookie cookie: getBasicCookieStore().getCookies()) {

                //check if the cookie name matches
                if (cookie.getName().equals("JSESSIONID")) {

                    //split sessioId.jvmRoute String
                    String[] cookieSplit = cookie.getValue().split("\\.", 2);

                    //"SESSION_ID.jvmRoute" format expected
                    if (cookieSplit.length != 2) {
                        LOG.warn(
                                String.format("%s: Serial %d: Malformed cookie from previous request: %s=%s", threadName, serial, cookie.getName(), cookie.getValue())
                        );
                        getBasicCookieStore().getCookies().remove(cookie);
                    } else {
                        LOG.trace("JSESSIONID {}", cookie.getValue());
                    }

                    //store values from cookie stored in a store == cookie from previous request
                    previousSessionId = cookieSplit[0];
                    previousJvmRoute = cookieSplit[1];

                } else if (cookie.getName().equals("JSESSIONIDSSO")) {
                    previousJSessionIdSso = cookie;
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
    private void checkIfCookieMatches(Cookie jSessionIdCookie, SampleResult sampleResult, int statusCode) {
        //split sessioId.jvmRoute String
        String[] cookieSplit = jSessionIdCookie.getValue().split("\\.",2);

        //"SESSION_ID.jvmRoute" format expected
        if (cookieSplit.length != 2) {
            throw new IllegalStateException(String.format("Unknown cookie stored in a cookie store: %s=%s", jSessionIdCookie.getName(), jSessionIdCookie.getValue()));
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
                LOG.trace("{}: Serial {}: JvmRoute assigned: {}", threadName, serial, newRoute);
            } else {
                LOG.trace("{}: Serial {}: JvmRoute changed. {} -> {}", threadName, serial, previousJvmRoute, newRoute);
                // =============================================================================
                // SSO CHECK:
                // When JvmRoute changes (e.g. because of failover), we should not to
                // re-authenticate because SSO info is replicated across the cluster
                // =============================================================================
                if (previousJSessionIdSso != null && jSessionIdSso != null && !previousJSessionIdSso.getValue().equalsIgnoreCase(jSessionIdSso.getValue())) {
                    sampleResult.setSuccessful(Boolean.FALSE);
                    sampleResult.setResponseCode(String.valueOf(statusCode));
                    sampleResult.setResponseMessage("JSESSIONIDSSO change after JvmRoute change!");
                    LOG.error("After JvmRoute changed. {} -> {}, current JSESSIONIDSSO {} does not match previous JSESSIONIDSSO {}", previousJvmRoute, newRoute, jSessionIdSso, previousJSessionIdSso);
                }
            }
            previousJvmRoute = newRoute;
        }
    }

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
            throw new IllegalArgumentException(String.format("%s: Serial %s: Expected 2 paths to be provided, but got %d: %s", threadName, serial, split.length, context.getParameter(PATH)));
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
        serial = "0";
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

    private String getCookieSpecProviderName() {
        String name = "easy";
        return name;
    }

    private Registry<CookieSpecProvider> getCookieSpecProvider() {
        if (cookieSpecProvider == null) {
            PublicSuffixMatcher publicSuffixMatcher = PublicSuffixMatcherLoader.getDefault();

            /*this.cookieSpecProvider = RegistryBuilder.<CookieSpecProvider>create()
                    .register(CookieSpecs.DEFAULT,
                            new DefaultCookieSpecProvider(publicSuffixMatcher))
                    .register(CookieSpecs.STANDARD,
                            new RFC6265CookieSpecProvider(publicSuffixMatcher))
                    .register(getCookieSpecProviderName(), new EasySpecProvider())
                    .build();*/
        }
        return this.cookieSpecProvider;
    }

    /**
     * client builder, and hence cookie store, must be thread specific
     * @param timeout
     * @return
     */
    private HttpClientBuilder getHttpClientBuilder(int timeout){
        if (this.httpClientBuilder==null){
            //Registry<CookieSpecProvider> cookieSpecProviderRegistry = getCookieSpecProvider();
            RequestConfig requestConfig;
            if (timeout > 0) {
                requestConfig = RequestConfig.custom()
                        .setConnectTimeout(timeout * 1000)
                        .setConnectionRequestTimeout(timeout * 1000)
                        .setSocketTimeout(timeout * 1000)
             //           .setCookieSpec(getCookieSpecProviderName())
                        .build();
            } else {
                requestConfig = RequestConfig.custom()
            //            .setCookieSpec(getCookieSpecProviderName())
                        .build();
            }
            this.httpClientBuilder = HttpClientBuilder.create()
                    .setDefaultRequestConfig(requestConfig)
            //        .setDefaultCookieSpecRegistry(cookieSpecProviderRegistry)
                    .setConnectionManager(getPoolingHttpClientConnectionManager());
            this.httpClientBuilder.setDefaultCookieStore(getBasicCookieStore());
            this.httpClientBuilder.setRedirectStrategy(new LaxRedirectStrategy()); // REDIRECT YES
            //this.httpClientBuilder.disableRedirectHandling(); // REDIRECT NO
        }
        return this.httpClientBuilder;
    }
}
