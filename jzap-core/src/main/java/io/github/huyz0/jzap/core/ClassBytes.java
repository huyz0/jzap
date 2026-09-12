package io.github.huyz0.jzap.core;

/**
 * One compiled class as found on a code path.
 *
 * @param binaryName  e.g. {@code com.example.Foo$Inner}
 * @param bytes       the class file contents
 * @param origin      where it was read from, for diagnostics
 */
public record ClassBytes(String binaryName, byte[] bytes, String origin) {
}
