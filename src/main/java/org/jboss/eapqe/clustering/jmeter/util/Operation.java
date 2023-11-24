package org.jboss.eapqe.clustering.jmeter.util;

public class Operation {

    public OperationType type;
    public boolean ignoreSample;
    public boolean clearLogs;

    public Operation(OperationType type, boolean ignoreSample, boolean clearLogs) {
        this.type = type;
        this.ignoreSample = ignoreSample;
        this.clearLogs = clearLogs;
    }
}
