package org.jboss.eapqe.clustering.jmeter.util;

public class ResponseWrapper {
    public Node node = null;
    public int responseCode = -1;
    public String statusLine = null;
    public String requestHeaders = null;
    public String responseHeaders = null;
    public String responseBody = null;
    public long bodySize = -1;
    public String dataType = null;
    public JSessionId previousJSessionId = null;
    public JSessionId currentJSessionId = null;

    public ResponseWrapper() {
    }
}
