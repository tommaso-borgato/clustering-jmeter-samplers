package org.jboss.eapqe.config;

public final class Constants {
    /**
     * SSO Credentials
     */
    public static final String ssoUser = "ssoUser";
    public static final String ssoPassw = "ssoPassw";
    /**
     * keystore and key-pair passwords
     */
    public static final String keypass = "123PIPPOBAUDO";
    public static final String storepass = "123PIPPOBAUDO";
    /**
     * JSESSIONID cookie separators e.g. JSESSIONID=HMrxfyBy-MX11Xl9CYZtyu7sYLSQrl6Y82-tI78Z.WFL1:WFL2
     */
    public static final String JSESSIONID_SESSION_ID_SEPARATOR = ".";
    public static final String JSESSIONID_JVM_ROUTES_SEPARATOR = ":";

    public static final String CLUSTERING_LIFECYCLE_MARKER = "CLUSTERING_LIFECYCLE_MARKER";

    public static final String CLUSTERING_LIFECYCLE_MARKER_FAIL = "FAIL";
    public static final String CLUSTERING_LIFECYCLE_MARKER_RESTORE = "RESTORE";
    public static final String CLUSTERING_LIFECYCLE_MARKER_NORMAL = "NORMAL";
    /**
     * WildFly logs sub-folder
     */
    public static final String WILDFLY_LOGS_SUB_FOLDER = "wildfly";
    /**
     * JMeter logs sub-folder
     */
    public static final String JMETER_LOGS_SUB_FOLDER = "jm";
    /**
     * String serched in console log by the 'text-finder' Jenkins plugin to mark the test failed
     */
    public static final String CLUSTERING_TEST_FAIL = "__CLUSTERING_TEST_FAIL__";
    /**
     * Field delimiter in JMeter CSV perf file (jmeter_results-perf.csv)
     */
    public static final String JMETER_CSV_FIELD_DELIMITER = ",";
}
