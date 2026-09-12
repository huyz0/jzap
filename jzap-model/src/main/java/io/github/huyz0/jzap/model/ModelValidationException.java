package io.github.huyz0.jzap.model;

/** Thrown when a project model is syntactically valid JSON but not a usable model. */
public class ModelValidationException extends RuntimeException {
    public ModelValidationException(String message) {
        super(message);
    }

    public ModelValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
