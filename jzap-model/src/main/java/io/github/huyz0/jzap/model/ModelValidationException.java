package io.github.huyz0.jzap.model;

/**
 * Thrown when a project model is syntactically valid JSON but not a usable model.
 *
 * <p>An {@link IllegalArgumentException}, because that is what it is: the file a build-tool
 * adapter handed over does not describe a project. It also has to be, in practice. Every caller
 * already treats a bad model as a usage error and catches {@code IllegalArgumentException} to
 * print the message and exit accordingly; extending {@code RuntimeException} meant this one
 * slipped past all of them, and a malformed model reached the user as a Jackson stack trace and
 * exit code 1 -- the code that means the mutation score was below the threshold.
 */
public class ModelValidationException extends IllegalArgumentException {

    public ModelValidationException(String message) {
        super(message);
    }

    public ModelValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
