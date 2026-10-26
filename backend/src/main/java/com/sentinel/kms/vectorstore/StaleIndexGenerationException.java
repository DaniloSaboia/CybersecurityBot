package com.sentinel.kms.vectorstore;

/** Raised when a durable worker tries to write a job generation it no longer owns. */
public class StaleIndexGenerationException extends IllegalStateException {

    public StaleIndexGenerationException(String message) {
        super(message);
    }
}
