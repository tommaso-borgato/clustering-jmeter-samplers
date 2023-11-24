package org.jboss.eapqe.clustering.jmeter.util;

import java.util.Arrays;

public class JSessionId {
    private String sessionId;
    private String[] jvmRoutes;

    public JSessionId() {
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String[] getJvmRoutes() {
        return jvmRoutes;
    }

    public void setJvmRoutes(String[] jvmRoutes) {
        this.jvmRoutes = jvmRoutes;
    }

    @Override
    public String toString() {
        return "JSessionId{" +
                "sessionId='" + sessionId + '\'' +
                ", jvmRoutes=" + Arrays.toString(jvmRoutes) +
                '}';
    }
}
