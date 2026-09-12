package io.github.huyz0.jzap.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Content hashing for cache keys. */
public final class Hashes {

    private Hashes() {
    }

    /**
     * First 16 hex characters of the SHA-256.
     *
     * <p>Truncated because these appear in a file people are meant to read, and 64 bits is far
     * beyond what a per-project cache needs: a collision would require two different versions of
     * the same class in one project's history.
     */
    public static String of(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }

    public static String of(String content) {
        return of(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Hash over a list, order-sensitive. Callers sort first when order should not matter. */
    public static String ofLines(List<String> lines) {
        return of(String.join("\n", lines));
    }

    /**
     * Fingerprint of the JVM that produced the bytecode being analysed.
     *
     * <p>Recorded so a cache refuses to be used under a different toolchain. Classes produced by
     * different javac versions, or on different platforms, differ; a cache that ignored that
     * would serve verdicts for bytecode that no longer exists.
     */
    public static String toolchain() {
        return System.getProperty("java.vm.version", "?")
                + "/" + System.getProperty("java.version", "?")
                + "/" + System.getProperty("os.name", "?")
                + "/" + System.getProperty("os.arch", "?");
    }
}
