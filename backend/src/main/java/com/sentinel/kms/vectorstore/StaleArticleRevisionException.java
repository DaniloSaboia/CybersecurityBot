package com.sentinel.kms.vectorstore;

/** Thrown when an article revision changes or becomes unreadable before vector replacement commits. */
public class StaleArticleRevisionException extends IllegalStateException {

    public StaleArticleRevisionException(String message) {
        super(message);
    }
}
