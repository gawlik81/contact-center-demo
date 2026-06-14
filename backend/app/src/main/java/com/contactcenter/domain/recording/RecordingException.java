package com.contactcenter.domain.recording;

/**
 * Wyjątek rzucany przy błędach operacji na nagraniach.
 */
public class RecordingException extends RuntimeException {
    public RecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
