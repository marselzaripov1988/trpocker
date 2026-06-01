package com.truholdem.service.cluster;

/** Raised when forwarding an action to the owning node fails (unknown address, timeout, non-2xx). */
public class ClusterForwardException extends RuntimeException {

    public ClusterForwardException(String message) {
        super(message);
    }

    public ClusterForwardException(String message, Throwable cause) {
        super(message, cause);
    }
}
