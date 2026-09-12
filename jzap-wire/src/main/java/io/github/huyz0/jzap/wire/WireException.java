package io.github.huyz0.jzap.wire;

/** A protocol-level failure: the minion reported an error, or the channel broke. */
public class WireException extends RuntimeException {
    public WireException(String message) {
        super(message);
    }

    public WireException(String message, Throwable cause) {
        super(message, cause);
    }
}
