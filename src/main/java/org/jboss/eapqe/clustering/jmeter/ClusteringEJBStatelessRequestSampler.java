package org.jboss.eapqe.clustering.jmeter;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.jboss.eapqe.clustering.jmeter.util.LogLoader;
import org.jboss.eapqe.clustering.jmeter.util.MiscHelpers;
import org.jboss.ejb.client.EJBClient;
import org.jboss.test.clusterbench.ejb.stateful.RemoteStatefulSB;
import org.jboss.test.clusterbench.ejb.stateless.RemoteStatelessSB;
import org.jboss.test.clusterbench.ejb.stateless.RemoteStatelessSBImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.naming.client.WildFlyInitialContextFactory;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.MatchRule;
import org.wildfly.security.sasl.SaslMechanismSelector;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.io.Serializable;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Custom sampler for clustering scenarios. This sampler uses JBoss EJB client.
 * <p>
 * Each thread defined in JMeter test plan corresponds to one ClusteringEJBRequestSampler instance.
 * Each ClusteringEJBRequestSampler instance creates its own InitialContext and looks up {@link RemoteStatefulSB} bean.
 * <p>
 * Specific results validation is used.
 *
 * @author Michal Vinkler mvinkler@redhat.com
 */
public class ClusteringEJBStatelessRequestSampler extends AbstractJavaSamplerClient implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(ClusteringEJBStatelessRequestSampler.class);

    /**
     * Host parameter defined in JMeter test plan
     */
    private static final String HOST = "host";

    /**
     * Port parameter defined in JMeter test plan
     */
    private static final String PORT = "port";

    /**
     * Username parameter defined in JMeter test plan
     */
    private static final String USERNAME = "username";

    /**
     * Password parameter defined in JMeter test plan
     */
    private static final String PASSWORD = "password";

    /**
     * URL for request (protocol+host+port)
     */
    private static String urlOfHttpRemotingConnector = null;

    /**
     * Name of this thread for logging purposes.
     * Format: Thread Group X-Y
     */
    private String threadName;

    /**
     * The application name of the deployed EJBs. This is typically the ear name without the .ear suffix.
     * However, the application name could be overridden in the application.xml of the EJB deployment on the server.
     */
    private String appName = "clusterbench-ee8";

    /**
     * This is the module name of the deployed EJBs on the server. This is typically the jar name of the
     * EJB deployment, without the .jar suffix, but can be overridden via the ejb-jar.xml.
     */
    private String moduleName = "clusterbench-ee8-ejb";

    /**
     * AS7 allows each deployment to have an (optional) distinct name. We haven't specified a distinct name for
     * our EJB deployment, so this is an empty string
     */
    private String distinctName = "";

    /**
     * Name of the user to be used for secured connection. The user needs to be added on EAP.
     */
    private String username;

    /**
     * Password for the username to be used for secured connection. The user needs to be added on EAP.
     */
    private String password;

    /**
     * Custom invocation timeout to be set for the EJB calls. If not set, default invocation timeout will be used.
     */
    private int invocationTimeout = 0;
    private RemoteStatelessSB session = null;
    private InitialContext ejbCtx;
    private MiscHelpers miscHelpers;

    private MiscHelpers getMiscHelpers() {
        if (this.miscHelpers == null) {
            this.miscHelpers = new MiscHelpers();
        }
        return this.miscHelpers;
    }

    // non-javadoc, see interface JavaSamplerClient
    public SampleResult runTest(JavaSamplerContext javaSamplerContext) {

        LogLoader.loadLogs();

        SampleResult sampleResult = new SampleResult();
        sampleResult.setSampleLabel("ClusterbenchEJBRequestStateless");

        if (session == null) {
            LOG.debug("{}: Creating SLSB.", threadName);
            if(!initSession(sampleResult)) {
                return sampleResult;
            }
        }

        String gotNodeName;

        LOG.trace("{}: Invoking the SLSB.", threadName);

        try {
            sampleResult.sampleStart();
            gotNodeName = session.getNodeName();
            sampleResult.sampleEnd();
            LOG.trace("{}: After successful bean call: StartTime: {} TimeStamp: {} EndTime: {} ElapsedTime: {}", threadName, sampleResult.getStartTime(), sampleResult.getTimeStamp(), sampleResult.getEndTime(), sampleResult.getTime());
        } catch (Exception e) {
            //sampleResult.sampleEnd() cannot be called twice
            if(sampleResult.getStartTime() != 0 & sampleResult.getEndTime() == 0) {
                sampleResult.sampleEnd();
                sampleResult.setConnectTime(sampleResult.getTime());
            }

            LOG.error("{}: Error getting response.", threadName, e);
            LOG.info("{}: Invalid response: Resetting session.", threadName);
            session = null;

            sampleResult.setResponseMessage(String.format("%s: Could not get valid response.", threadName));
            sampleResult.setSuccessful(false);
            return sampleResult;
        }

        sampleResult.setConnectTime(sampleResult.getTime());
        LOG.trace("{}: Response time: {}, Received response: {}", threadName, sampleResult.getTime(), gotNodeName);

        //no need to do some extensive validation
        sampleResult.setResponseMessage("OK: " + gotNodeName);
        sampleResult.setSuccessful(true);

        return sampleResult;
    }

    private boolean initSession(SampleResult sampleResult) {
        // The EJB name which by default is the simple class name of the bean implementation class
        String beanName = RemoteStatelessSBImpl.class.getSimpleName();

        // the remote view fully qualified class name
        String viewClassName = RemoteStatelessSB.class.getName();

        final Properties properties = new Properties();
        properties.put(Context.INITIAL_CONTEXT_FACTORY, WildFlyInitialContextFactory.class.getName());
        properties.put(Context.PROVIDER_URL, urlOfHttpRemotingConnector);

        AuthenticationConfiguration common = AuthenticationConfiguration.empty()
                .setSaslMechanismSelector(SaslMechanismSelector.fromString("DIGEST-MD5"));
        AuthenticationContext authCtxEmpty = AuthenticationContext.empty();
        AuthenticationConfiguration authCfg = common.useName(username).usePassword(password);
        final AuthenticationContext authCtx = authCtxEmpty.with(MatchRule.ALL, authCfg);
        AuthenticationContext.getContextManager().setThreadDefault(authCtx);

        LOG.trace("{}: Creating initialContext.", threadName);
        try {
            ejbCtx = new InitialContext(properties);
            LOG.trace("{}: Context created: {}.", threadName, ejbCtx.getNameInNamespace());
            // let's do the lookup (notice the ?stateful string as the last part of the jndi name for stateful bean lookup)
            session = (RemoteStatelessSB) ejbCtx.lookup("ejb:" + appName + "/" + moduleName + "/" + distinctName + "/" + beanName + "!" + viewClassName);
        } catch (NamingException e) {
            LOG.error("{}: Error during initialContext creation. ", threadName, e);

            //we need to set timestamps, otherwise the corresponding log will have timestamp set to 0
            sampleResult.sampleStart();
            sampleResult.sampleEnd();
            sampleResult.setResponseMessage(String.format("%s: Error during initialContext creation. Caused by: %s", threadName, e.getMessage()));
            sampleResult.setSuccessful(false);
            return false;
        }

        if (invocationTimeout > 0) {
            //EJB Client internal API usage, will work only with EJBClient 4.x
            LOG.debug("{}: Setting the invocation timeout for EJB client proxy to: {} seconds", threadName, invocationTimeout);
            EJBClient.setInvocationTimeout(session, invocationTimeout, TimeUnit.SECONDS);
        }

        return true;
    }

    /**
     * Define default parameters in JMeter GUI test plan.
     */
    @Override
    public Arguments getDefaultParameters() {
        Arguments defaultParameters = new Arguments();
        defaultParameters.addArgument(HOST, "${__P(" + HOST + ",localhost)}");
        defaultParameters.addArgument(PORT, "${__P(" + PORT + ",8080)}");

        defaultParameters.addArgument(USERNAME, "${__P(" + USERNAME + ",joe)}");
        defaultParameters.addArgument(PASSWORD, "${__P(" + PASSWORD + ",secret-Passw0rd)}");

        return defaultParameters;
    }

    /**
     * Prepare data.
     */
    @Override
    public void setupTest(JavaSamplerContext context) {

        super.setupTest(context);

        threadName = context.getJMeterContext().getThread().getThreadName();

        username = context.getParameter(USERNAME);
        password = context.getParameter(PASSWORD);
        LOG.trace("{}: Setting the credentials for secured connection - username: {} password: {}", threadName, username, password);

        //port is the same for each host
        String port = context.getParameter(PORT);

        // define urlOfHttpRemotingConnector - we expect a comma-delimited list of 1..n hosts in HOST property
        StringBuilder sb = new StringBuilder();
        for (String host : context.getParameter(HOST).split(",")) {
            sb.append("remote+http://").append(getMiscHelpers().formatPossibleIPv6Address(host, threadName)).append(":").append(port).append(",");
        }

        sb.setLength(sb.length() - 1); //delete the last comma

        urlOfHttpRemotingConnector = sb.toString();
        LOG.trace("{}: Setting the URL of HTTP Remoting Connector: {}", threadName, urlOfHttpRemotingConnector);
    }

    /**
     * Close the EJB client context.
     */
    @Override
    public void teardownTest(JavaSamplerContext context) {
        super.teardownTest(context);

        getMiscHelpers().safeCloseEjbClientContext(ejbCtx, threadName);
    }
}
