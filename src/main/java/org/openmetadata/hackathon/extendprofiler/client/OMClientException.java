package org.openmetadata.hackathon.extendprofiler.client;

public class OMClientException extends RuntimeException {
    
    private final int statusCode;

    public OMClientException(String msg) {
        super(msg);
        this.statusCode = -1;
    }

    public OMClientException(int statusCode, String msg) {
        super(msg);
        this.statusCode = statusCode;
    }

    public OMClientException(String msg, Throwable cause) {
        super(msg, cause);
        this.statusCode = -1;
    }

    public int getStatusCode() {
        return statusCode;
    }
    
}
