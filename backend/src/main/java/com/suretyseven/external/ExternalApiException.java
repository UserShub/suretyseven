package com.suretyseven.external;

/** Wraps any failure talking to the external Applicant API (timeout, 5xx, malformed body). */
public class ExternalApiException extends RuntimeException {
    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
    }
    public ExternalApiException(String message) {
        super(message);
    }
}
