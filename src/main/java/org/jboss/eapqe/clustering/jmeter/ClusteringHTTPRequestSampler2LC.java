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
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.jboss.eapqe.clustering.jmeter.util.CircularList;
import org.jboss.eapqe.clustering.jmeter.util.InvalidCacheStateException;
import org.jboss.eapqe.clustering.jmeter.util.JSessionId;
import org.jboss.eapqe.clustering.jmeter.util.LogLoader;
import org.jboss.eapqe.clustering.jmeter.util.MiscHelpers;
import org.jboss.eapqe.clustering.jmeter.util.Node;
import org.jboss.eapqe.clustering.jmeter.util.Operation;
import org.jboss.eapqe.clustering.jmeter.util.OperationType;
import org.jboss.eapqe.clustering.jmeter.util.ResponseWrapper;
import org.jboss.eapqe.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h2>Second Level Cache Test:</h2>
 *
 * WildFly is configured as follows:
 * <code>
 *    <cache-container name="hibernate" module="org.infinispan.hibernate-cache">
 *        <transport lock-timeout="60000"/>
 *        <local-cache name="local-query">
 *            <object-memory size="10000"/>
 *            <expiration max-idle="100000"/>
 *        </local-cache>
 *        <invalidation-cache name="entity">
 *            <object-memory size="10000"/>
 *            <expiration max-idle="100000"/>
 *        </invalidation-cache>
 *        <replicated-cache name="timestamps"/>
 *    </cache-container>
 * </code>
 *
 * Test logic:
 * <ul>
 *     <li>create entity on one node and selects it on all nodes</li>
 *     <li>verify entity is in the cache of all nodes</li>
 *     <li>Update entity</li>
 *     <li>verify entity is evicted from all nodes except the one where it was updated</li>
 *     <li>delete entity</li>
 *     <li>verify entity is evicted from all nodes</li>
 * </ul>
 *
 * @author Michal Vinkler mvinkler@redhat.com
 */
public class ClusteringHTTPRequestSampler2LC extends AbstractJavaSamplerClient implements Serializable {


    private static final Logger log = LoggerFactory.getLogger(ClusteringHTTPRequestSampler2LC.class);

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
     *
     */
    private static final String REMOTE_PROG = "remote.prog";
    private String remoteProg = "0";

    /**
     * URL for request
     */
    private CircularList<Node> urls = null;
    private int urlIndex = 0;

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
    private int ENTITY_ID = 0;
    private static final AtomicInteger BASE_ENTITY_ID = new AtomicInteger(-1);

    /**
     * Name of this thread for logging purposes.
     * Format: Thread Group X-Y
     */
    private String threadName;

    /**
     * JSESSIONID
     */
    private JSessionId previousJSessionId = null;
    private JSessionId currentJSessionId = null;

    /**
     * JMeter Sample
     */
    private Boolean isSampleSuccessful = null;
    private String sampleLabel = null;
    private String sampleMessage = null;
    private String sampleResponseData = null;
    private String requestHeaders = null;
    private List<String> logs = new ArrayList<>();

    private static PoolingHttpClientConnectionManager poolingConnManager;
    private HttpClientBuilder httpClientBuilder;
    private static final int HTTP_EXPECTATION_FAILED = 417;

    /**
     * APIs in /second-level-cache-web
     */
    private final String dbApi = "api/db";
    private final String cacheApi = "api/cache";
    private OperationType operationType;

    private String hosts;
    private String ports;

    private MiscHelpers miscHelpers;
    private static boolean first = true;

    private MiscHelpers getMiscHelpers() {
        if (this.miscHelpers == null) {
            this.miscHelpers = new MiscHelpers();
        }
        return this.miscHelpers;
    }

    private boolean isHttpStatusCodeIn(final int expectedHttpStatusCode, final int ... errorHttpStatusCodes) {
        for (int code : errorHttpStatusCodes) {
            if (code == expectedHttpStatusCode) return true;
        }
        return false;
    }

    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    private ResponseWrapper executeGet(final int index, final Node node, final String url) throws IOException {
        ResponseWrapper responseWrapper = new ResponseWrapper();
        responseWrapper.node = node;
        HttpGet request = new HttpGet(url);
        request.addHeader("User-Agent", "Mozilla/5.0 (compatible; AcmeInc/1.0");
        requestHeaders = getRequestHeadersString(request);
        CloseableHttpResponse response = null;
        try {
            response = client.execute(request);
            responseWrapper.responseCode = response.getStatusLine().getStatusCode();
            responseWrapper.statusLine = response.getStatusLine().toString();
            responseWrapper.currentJSessionId = getJSessionIdCookie(getBasicCookieStore());
            responseWrapper.bodySize = response.getEntity().getContentLength();
            responseWrapper.dataType = response.getEntity().getContentType().toString();
            responseWrapper.responseHeaders = getResponseHeadersString(response);
            responseWrapper.responseBody = getResponseEntityString(response);
        } finally {
            HttpClientUtils.closeQuietly(response);
            //log.trace("{}: ID {}: {}: {}: {}", threadName, ENTITY_ID, url, responseWrapper.responseCode, responseWrapper.responseBody);
            logs.add(
                    String.format("%s: ID %d: %s[%d]: %d: %s", threadName, ENTITY_ID, url, index, responseWrapper.responseCode, responseWrapper.responseBody)
            );
        }
        return responseWrapper;
    }

    /**
     * Executes GET and fails if HTTP Status Code isn't the expected one
     * @param url
     * @param expectedHttpStatusCode
     * @return
     * @throws IOException
     */
    private ResponseWrapper executeGet(final int index, final Operation op, final Node node, final String url, final int expectedHttpStatusCode, final int ... errorHttpStatusCodes) throws IOException, InvalidCacheStateException {
        ResponseWrapper responseWrapper = executeGet(index, node, url);
        if (responseWrapper.responseCode != expectedHttpStatusCode) {
            // these codes have to categorized differently: they mean the service was not available: they have to make an exception pop up
            if (isHttpStatusCodeIn(responseWrapper.responseCode, errorHttpStatusCodes)) {
                throw new IOException(
                        String.format("HTTP-Error with url (index %d) '%s': %s: %s", index, url, responseWrapper.statusLine, responseWrapper.responseBody)
                );
            }
            //log.trace("GOT: {} FROM: {} EXPECTED: {} ERRROR CODES: {}", responseWrapper.responseCode, url, expectedHttpStatusCode, joinIntArray(",", errorHttpStatusCodes));
            // If the node cache isn't in correct state because the last cache refresh was not successful, we tolerate the error
            if (
                    !node.isCacheRefreshed()
                    &&
                    (op.type.equals(OperationType.EXISTS_CACHE)
                            || op.type.equals(OperationType.EXISTS_CACHE_ALL)
                            || op.type.equals(OperationType.NOT_EXISTS_CACHE_ALL)
                            || op.type.equals(OperationType.NOT_EXISTS_CACHE_OTHERS))
            ) {
                log.warn(
                        String.format("2LC-Error (but node cache not refreshed) with url '%s': expected %d got %d :%s: %s", url, expectedHttpStatusCode, responseWrapper.responseCode, responseWrapper.statusLine, responseWrapper.responseBody)
                );
            } else {
                throw new InvalidCacheStateException(
                        String.format("2LC-Error with url '%s': expected %d got %d :%s: %s", url, expectedHttpStatusCode, responseWrapper.responseCode, responseWrapper.statusLine, responseWrapper.responseBody)
                );
            }
        }
        return responseWrapper;
    }

    private List<ResponseWrapper> executeGetOnAllNodes(
            final Operation op,
            final List<Node> allowedUrls,
            final boolean stopOnFirstSuccess,
            final String path,
            final int expectedHttpStatusCode,
            final int ... errorHttpStatusCodes
    ) throws InvalidCacheStateException, IOException {
        List<ResponseWrapper> res = new ArrayList<>();
        List<String> urlErrs = new ArrayList<>();

        // try on all nodes: we have to try once for each node, starting with the current url
        int cnt = 0;
        int indexInitial = urlIndex - 1;
        while (cnt++ < urls.size() && indexInitial++ >= -1) {
            int index = indexInitial % urls.size();
            try {
                if (allowedUrls != null && !allowedUrls.contains(urls.get(index))) continue;
                ResponseWrapper responseWrapper = executeGet(
                          index
                        , op
                        , urls.get(index)
                        , String.format("%s/%s", urls.get(index).getUrl(), path)
                        , expectedHttpStatusCode
                        , errorHttpStatusCodes
                );
                res.add(responseWrapper);
                switch (op.type) {
                    case CREATE:
                        urls.getAllExcept(urls.get(index)).forEach(node -> node.setCreateNode(false));
                        urls.get(index).setCreateNode(true);
                        break;
                    case FIND_DB_ALL:
                        urls.get(index).setCacheRefreshed(true);
                        break;
                    case UPDATE:
                        urls.getAllExcept(urls.get(index)).forEach(node -> node.setUpdateNode(false));
                        urls.get(index).setUpdateNode(true);
                        urls.get(index).setCacheRefreshed(true);
                        break;
                    case DELETE:
                        urls.getAllExcept(urls.get(index)).forEach(node -> node.setDeleteNode(false));
                        urls.get(index).setDeleteNode(true);
                        urls.get(index).setCacheRefreshed(true);
                        break;
                    default:
                        break;
                }
                if (stopOnFirstSuccess) {
                    break;
                }
            } catch (IOException urlErr) {
                switch (op.type) {
                    case CREATE:
                        urls.get(index).setCreateNode(false);
                        break;
                    case FIND_DB_ALL:
                        urls.get(index).setCacheRefreshed(false);
                        break;
                    case UPDATE:
                        urls.get(index).setUpdateNode(false);
                        break;
                    case DELETE:
                        urls.get(index).setDeleteNode(false);
                        break;
                    default:
                        break;
                }
                // if we were hitting the current global url, we move it to the following url
                if (index == urlIndex) urlIndex++;
                // if a node is down we ignore it as long as cache state is fine
                String errStr = getStackTrace(urlErr);
                //log.trace("ERROR executeGet('{}'): {}", String.format("%s/%s", urls.get(index).getUrl(), path), errStr);
                urlErrs.add(errStr);
            }
        }
        // if no node was successful, we fail op
        if (res.size() == 0) {
            throw new IOException(String.format("'%s' on Entity %s had 0 successful nodes, errors: %s", path, ENTITY_ID, String.join(" --- ", urlErrs)));
        }
        return res;
    }

    /**
     * CREATE Entity
     * @return
     * @throws IOException
     */
    private List<ResponseWrapper> createEntity(Operation op) throws InvalidCacheStateException, IOException {
        return executeGetOnAllNodes(
                  op
                , null
                , true
                , String.format("%s/create/%d", dbApi, ENTITY_ID)
                , HttpURLConnection.HTTP_OK
                , HttpURLConnection.HTTP_NOT_FOUND
                , HttpURLConnection.HTTP_UNAVAILABLE);
    }

    /**
     * UPDATE Entity
     * @return
     * @throws IOException
     */
    private List<ResponseWrapper> updateEntity(Operation op) throws InvalidCacheStateException, IOException {
        return executeGetOnAllNodes(
                  op
                , null
                , true
                , String.format("%s/update/%d/1" , dbApi, ENTITY_ID)
                , HttpURLConnection.HTTP_OK
                , HttpURLConnection.HTTP_NOT_FOUND
                , HttpURLConnection.HTTP_UNAVAILABLE);
    }

    /**
     * DELETE Entity
     * @return
     * @throws IOException
     */
    private List<ResponseWrapper> deleteEntity(Operation op) throws InvalidCacheStateException, IOException {
        return executeGetOnAllNodes(
                  op
                , null
                , true
                , String.format("%s/delete/%d" , dbApi, ENTITY_ID)
                , HttpURLConnection.HTTP_OK
                , HttpURLConnection.HTTP_NOT_FOUND
                , HttpURLConnection.HTTP_UNAVAILABLE);
    }

    /**
     * FIND Entity on DB
     * @param urlList
     * @param expectEntityToExist
     * @return
     * @throws IOException
     */
    private List<ResponseWrapper> findEntityOnDB(Operation op, List<Node> urlList, boolean expectEntityToExist) throws InvalidCacheStateException, IOException {
        return executeGetOnAllNodes(
                  op
                , urlList
                , false
                , String.format("%s/find/%d/version" , dbApi, ENTITY_ID)
                , expectEntityToExist ? HttpURLConnection.HTTP_OK : HTTP_EXPECTATION_FAILED
                , HttpURLConnection.HTTP_NOT_FOUND
                , HttpURLConnection.HTTP_UNAVAILABLE);
    }

    /**
     * EXISTS Entity in CACHE
     * @param urlList
     * @param expectEntityToExist
     * @return
     * @throws IOException
     */
    private List<ResponseWrapper> existsEntityInCache(Operation op, List<Node> urlList, boolean expectEntityToExist) throws IOException, InvalidCacheStateException {
        return executeGetOnAllNodes(
                  op
                , urlList
                , false
                , String.format("%s/exists/%d" , cacheApi, ENTITY_ID)
                , expectEntityToExist ? HttpURLConnection.HTTP_OK : HTTP_EXPECTATION_FAILED
                , HttpURLConnection.HTTP_NOT_FOUND
                , HttpURLConnection.HTTP_UNAVAILABLE);
    }

    // "Prenderei la vita dai 18 ai 24 e la metterei in loop ... Ma che ne sanno i 2000"
    private final CircularList<Operation> ops = new CircularList<Operation>() {
        {
            add(new Operation(OperationType.NEXT_ENTITY_ID, true, true));

            // if CREATE fails we want to try the same op on another node
            add(new Operation(OperationType.CREATE,false, false));
            add(new Operation(OperationType.FIND_DB_ALL,false, false));
            add(new Operation(OperationType.EXISTS_CACHE_ALL,false, false));

            add(new Operation(OperationType.UPDATE,false, false));
            add(new Operation(OperationType.EXISTS_CACHE,false, false));
            add(new Operation(OperationType.NOT_EXISTS_CACHE_OTHERS,false, false));

            add(new Operation(OperationType.DELETE,false, false));
            add(new Operation(OperationType.NOT_EXISTS_CACHE_ALL,false, false));

            add(new Operation(OperationType.NEXT_URL,true, true));
        }
    };
    private int opsIndex = 0;

    private Node getUpdateNode() {
        for (Node node: urls) {
            if (node.isUpdateNode()) return node;
        }
        return null;
    }

    /**
     * Actual test logic
     * @param javaSamplerContext
     * @return
     */
    public SampleResult runTest(JavaSamplerContext javaSamplerContext) {

        LogLoader.loadLogs();

        SampleResult sampleResult = new SampleResult();

        try {
            Operation op = ops.get(opsIndex);
            //log.info("[runTest] op {} ID {} url {}", op.type, ENTITY_ID, urls.get(urlIndex).getUrl());

            threadName = javaSamplerContext.getJMeterContext().getThread().getThreadName();
            previousJSessionId = currentJSessionId;
            isSampleSuccessful = Boolean.FALSE;
            sampleLabel = null;
            sampleMessage = null;
            sampleResponseData = null;
            requestHeaders = null;
            List<ResponseWrapper> responseWrapperList = null;

            sampleResult.sampleStart();
            switch (op.type) {
                case CREATE:
                    operationType = op.type;
                    responseWrapperList = createEntity(op);
                    break;
                case FIND_DB_ALL:
                    operationType = op.type;
                    responseWrapperList = findEntityOnDB(op, urls, true);
                    break;
                case EXISTS_CACHE_ALL:
                    operationType = op.type;
                    responseWrapperList = existsEntityInCache(op, urls, true);
                    break;
                case UPDATE:
                    operationType = op.type;
                    responseWrapperList = updateEntity(op);
                    break;
                case EXISTS_CACHE:
                    operationType = op.type;
                    responseWrapperList = existsEntityInCache(op, Arrays.asList(getUpdateNode()), true);
                    break;
                case NOT_EXISTS_CACHE_OTHERS:
                    operationType = op.type;
                    responseWrapperList = existsEntityInCache(op, urls.getAllExcept(getUpdateNode()), false);
                    break;
                case DELETE:
                    operationType = op.type;
                    responseWrapperList = deleteEntity(op);
                    break;
                case NOT_EXISTS_CACHE_ALL:
                    operationType = op.type;
                    responseWrapperList = existsEntityInCache(op, urls, false);
                    break;
                case NEXT_URL:
                    operationType = op.type;
                    urlIndex++;
                    break;
                case NEXT_ENTITY_ID:
                    operationType = op.type;
                    //log.trace("{}: ID {}: OK", threadName, ENTITY_ID);
                    ENTITY_ID = BASE_ENTITY_ID.getAndIncrement();
                    break;
                default:
                    throw new IllegalStateException(String.format("Operation '%s' does not exist!", op));
            }
            if (op.ignoreSample) {
                sampleResult.setIgnore();
            } else {
                sampleResult.sampleEnd();
                sampleResult.setResponseCodeOK();
                sampleMessage = "OK";
                isSampleSuccessful = Boolean.TRUE;
                sampleLabel = "2LC";
                sampleResult.setRequestHeaders(requestHeaders);
                sampleResult.setConnectTime(sampleResult.getTime());
                sampleResponseData = responseWrapperList.size() > 0 ? responseWrapperList.get(responseWrapperList.size()-1).responseBody : "?";
                sampleResult.setURL((new URL(urls.get(urlIndex).getUrl())));
            }
            if (op.clearLogs) {
                logs.clear();
            }

            // by default, if everything is ok, we move to the next operation
            opsIndex++;

        } catch (InvalidCacheStateException err) {
            handleCacheStateError(err);
        } catch (Exception err) {
            handleGlobalError(err);
        } finally {
            //sampleResult.sampleEnd() cannot be called twice
            if(sampleResult.getStartTime() != 0 & sampleResult.getEndTime() == 0) { sampleResult.sampleEnd(); }
            sampleResult.setSuccessful(isSampleSuccessful);
            sampleResult.setSampleLabel(sampleLabel == null ? "?" : sampleLabel);
            sampleResult.setResponseMessage((sampleMessage == null) ? "?" : sampleMessage);
            if (sampleResponseData != null) {sampleResult.setResponseData(sampleResponseData, null);}
        }

        return sampleResult;
    }

    /**
     * In the event of an url error (e.g. 404 or 503) we behave accordingly to the op settings
     * @param err
     */
    private void handleGlobalError(Exception err){
        // "^([ 0-9\\-:,.]+) +([A-Z]+) +([^ ]+) +(.*)"
        log.error(
                    "{}: Entity-ID {} operationType {} Global Error: {}\n" +
                    "*********** Log stack: ***********\n" +
                    "{}\n" +
                    "==================================================",
                threadName,
                ENTITY_ID,
                operationType,
                getStackTrace(err),
                String.join("\n", logs)
        );
        logs.clear();
        /*
            We spotted an error, let's start over with a different entity
         */
        opsIndex = 0;
        /*
            DEFAULTS
         */
        isSampleSuccessful = Boolean.FALSE;
        if (sampleLabel == null) { sampleLabel = "GLOBAL ERROR"; }
        if (sampleMessage == null) { sampleMessage = err.getMessage(); }
        /*
            RESET
         */
        getBasicCookieStore().clear();
        currentJSessionId = null;
        previousJSessionId = null;
    }

    /**
     * In the event of a cache state error, we log everything and start over with a brand new entity
     * @param err
     */
    private void handleCacheStateError(Exception err){
        log.error(
                    "{}: Entity-ID {} operationType {} 2LC Error: {}\n" +
                    "==================================================\n" +
                    "{}\n" +
                    "==================================================",
                threadName,
                ENTITY_ID,
                operationType,
                getStackTrace(err),
                String.join("\n", logs)
                );
        logs.clear();
        /*
            We spotted an error, let's start over with a different entity
         */
        opsIndex = 0;
        /*
            DEFAULTS
         */
        isSampleSuccessful = Boolean.FALSE;
        if (sampleLabel == null) { sampleLabel = "2LC ERROR"; }
        if (sampleMessage == null) { sampleMessage = err.getMessage(); }
        /*
            RESET
         */
        getBasicCookieStore().clear();
        currentJSessionId = null;
        previousJSessionId = null;
    }

    /**
     * Read response content as String
     * @param response
     * @return
     * @throws IOException
     */
    private String getResponseEntityString(CloseableHttpResponse response) throws IOException {
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
                            log.warn("{}: Malformed cookie from previous request: {}={}", threadName, cookie.getName(), cookie.getValue());
                            cookieStore.getCookies().remove(cookie);
                        } else {
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
        defaultParameters.addArgument(PATH, "${__P(" + PATH + ",/second-level-cache-web)}");
        defaultParameters.addArgument(TIMEOUT, "${__P(" + TIMEOUT + ",-1)}");
        defaultParameters.addArgument(REMOTE_PROG, "${__P(" + REMOTE_PROG + ",0)}");

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
        remoteProg = context.getJMeterProperties().getProperty(REMOTE_PROG);
        if (remoteProg == null) {
            remoteProg = context.getParameter(REMOTE_PROG);
        }
        String msg1 = String.format(
                "hosts: %s ports: %s prog %s",
                hosts, ports, remoteProg
        );

        // define url e.g. http://10.0.153.188:8080/second-level-cache-web
        urls = getMiscHelpers().getNodes(hosts, ports, context.getParameter(PATH), threadName, ",");

        // timeout
        int timeout = Integer.parseInt(context.getParameter(TIMEOUT));

        // initialize ENTITY_ID number to be received from server: given about 130_000 samples x client, 250_000 should be a good window
        if (BASE_ENTITY_ID.get() < 0) BASE_ENTITY_ID.set(250_000 * Integer.parseInt(remoteProg));
        ENTITY_ID = BASE_ENTITY_ID.getAndIncrement();

        StringBuilder sb = new StringBuilder();
        for (Node node: urls){
            sb.append(node.getUrl()).append(" ");
        }
        if (first) {
            log.info("{} --> ENTITY_ID {} JMETER-URLS: {} TIMEOUT {}", msg1, ENTITY_ID, sb.toString(), timeout);
            first = false;
        }

        // initialize HTTP client
        client = getHttpClientBuilder(timeout).build();
    }

    /**
     * Close the client.
     */
    @Override
    public void teardownTest(JavaSamplerContext context) {
        HttpClientUtils.closeQuietly(client);
        super.teardownTest(context);
    }

    /**
     * There must be just one per VM
     * @return
     */
    private static synchronized PoolingHttpClientConnectionManager getPoolingHttpClientConnectionManager() {
        if (poolingConnManager == null) {
            poolingConnManager = new PoolingHttpClientConnectionManager();
            poolingConnManager.setDefaultMaxPerRoute(200);
            poolingConnManager.setMaxTotal(800);
            poolingConnManager.setValidateAfterInactivity(1000);
            poolingConnManager.closeIdleConnections(10, TimeUnit.SECONDS);
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
