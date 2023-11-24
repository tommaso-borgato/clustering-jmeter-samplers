package org.jboss.eapqe.clustering.jmeter.util;

public class InvalidCacheStateException extends Exception {
    /**
     * Constructs an {@code InvalidCacheStateException} with the specified detail message.
     *
     * @param message
     *        The detail message (which is saved for later retrieval
     *        by the {@link #getMessage()} method)
     */
    public InvalidCacheStateException(String message) { super(message); }
}
