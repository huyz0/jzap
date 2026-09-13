package io.github.huyz0.jzap.wire;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framing both ends of the controller/minion protocol depend on.
 *
 * <p>Hand-rolled rather than a serialisation library, because this jar goes on the classpath of
 * the JVM running the user's tests and a library there could clash with their own dependencies.
 * The cost of that choice is that the framing is jzap's to get right, and a length prefix that
 * disagreed with its payload would present as a truncated-response error a long way from here.
 */
class ChannelTest {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /** A connected pair of channels, as the controller and the minion have. */
    private record Pair(Channel client, Channel server, ServerSocket listener)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            client.close();
            server.close();
            listener.close();
        }
    }

    private Pair connected() throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Future<Channel> accepted = pool.submit(() -> new Channel(listener.accept()));
        Channel client = new Channel(
                new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
        return new Pair(client, accepted.get(), listener);
    }

    @Test
    void everyPrimitiveSurvivesARoundTrip() throws Exception {
        try (Pair pair = connected()) {
            pair.client().writeByte(7);
            pair.client().writeInt(Integer.MIN_VALUE);
            pair.client().writeLong(Long.MAX_VALUE);
            pair.client().writeBool(true);
            pair.client().writeBool(false);
            pair.client().flush();

            assertEquals(7, pair.server().readByte());
            assertEquals(Integer.MIN_VALUE, pair.server().readInt());
            assertEquals(Long.MAX_VALUE, pair.server().readLong());
            assertTrue(pair.server().readBool());
            assertFalse(pair.server().readBool());
        }
    }

    @Test
    void stringsSurviveIncludingTheOnesJunitIdsAreMadeOf() throws Exception {
        // A JUnit unique id contains brackets, colons, slashes and parentheses, and a test
        // display name can contain anything at all.
        String uniqueId = "[engine:junit-jupiter]/[class:ex.FooTest]/[method:bar(java.lang.String)]";
        try (Pair pair = connected()) {
            pair.client().writeString(uniqueId);
            pair.client().writeString("");
            pair.client().writeString("emoji 🚀 and accents éàü");
            pair.client().flush();

            assertEquals(uniqueId, pair.server().readString());
            assertEquals("", pair.server().readString(), "an empty string is how absent is sent");
            assertEquals("emoji 🚀 and accents éàü", pair.server().readString(),
                    "the framing is byte-length prefixed, so multi-byte characters must not shift it");
        }
    }

    @Test
    void byteArraysSurvive() throws Exception {
        byte[] classFile = new byte[8192];
        for (int i = 0; i < classFile.length; i++) {
            classFile[i] = (byte) (i % 256);
        }
        try (Pair pair = connected()) {
            pair.client().writeBytes(classFile);
            pair.client().writeBytes(new byte[0]);
            pair.client().flush();

            assertArrayEquals(classFile, pair.server().readBytes(),
                    "mutant bytecode is shipped this way, so a single byte matters");
            assertArrayEquals(new byte[0], pair.server().readBytes());
        }
    }

    @Test
    void nothingIsReadableUntilTheWriterFlushes() throws Exception {
        try (Pair pair = connected()) {
            pair.client().writeInt(42);
            pair.server().readTimeout(200);

            assertThrows(SocketTimeoutException.class, () -> pair.server().readInt(),
                    "buffered output means an unflushed write has not been sent");

            pair.client().flush();
            pair.server().readTimeout(5_000);
            assertEquals(42, pair.server().readInt());
        }
    }

    @Test
    void aReadTimeoutIsHowAHungMinionIsDetected() throws Exception {
        try (Pair pair = connected()) {
            pair.server().readTimeout(100);

            assertThrows(SocketTimeoutException.class, () -> pair.server().readByte(),
                    "a thread cannot be stopped, so the controller has to give up on the read "
                            + "and kill the process");
        }
    }

    @Test
    void aClosedPeerEndsTheStreamRatherThanHanging() throws Exception {
        Pair pair = connected();
        pair.client().close();

        assertThrows(EOFException.class, () -> pair.server().readByte(),
                "the minion reads this as the controller having gone away");
        pair.server().close();
        pair.listener().close();
    }

    @Test
    void writingToAClosedChannelIsAWireException() throws Exception {
        Pair pair = connected();
        pair.client().close();

        assertThrows(WireException.class, () -> {
            // The first write may land in the buffer; the flush is what must fail.
            pair.client().writeInt(1);
            pair.client().flush();
        });
        pair.server().close();
        pair.listener().close();
    }

    @Test
    void settingATimeoutOnAClosedChannelIsAWireException() throws Exception {
        Pair pair = connected();
        pair.client().close();

        assertThrows(WireException.class, () -> pair.client().readTimeout(100));
        pair.server().close();
        pair.listener().close();
    }

    /**
     * Every write reports a broken channel rather than losing it.
     *
     * <p>Writes are buffered, so a small one lands in memory and only a flush notices the socket
     * has gone. These force the buffer past its capacity, which is what happens naturally when
     * the thing being written is a class file.
     */
    @Test
    void aBrokenChannelIsReportedByWhicheverWriteFillsTheBuffer() throws Exception {
        byte[] big = new byte[64 * 1024];

        Pair bytes = connected();
        bytes.client().close();
        assertThrows(WireException.class, () -> bytes.client().writeBytes(big));
        bytes.server().close();
        bytes.listener().close();

        Pair strings = connected();
        strings.client().close();
        assertThrows(WireException.class,
                () -> strings.client().writeString("x".repeat(64 * 1024)));
        strings.server().close();
        strings.listener().close();

        Pair ints = connected();
        ints.client().close();
        assertThrows(WireException.class, () -> {
            for (int i = 0; i < 64 * 1024; i++) {
                ints.client().writeInt(i);
            }
        });
        ints.server().close();
        ints.listener().close();

        Pair longs = connected();
        longs.client().close();
        assertThrows(WireException.class, () -> {
            for (int i = 0; i < 64 * 1024; i++) {
                longs.client().writeLong(i);
            }
        });
        longs.server().close();
        longs.listener().close();

        Pair singleBytes = connected();
        singleBytes.client().close();
        assertThrows(WireException.class, () -> {
            for (int i = 0; i < 64 * 1024; i++) {
                singleBytes.client().writeByte(i);
            }
        });
        singleBytes.server().close();
        singleBytes.listener().close();
    }

    @Test
    void aTruncatedPayloadIsAnEndOfStreamRatherThanASilentShortRead() throws Exception {
        try (Pair pair = connected()) {
            // A length prefix promising more than follows is what a killed minion leaves behind.
            pair.client().writeInt(1024);
            pair.client().flush();
            pair.client().close();

            assertThrows(EOFException.class, () -> pair.server().readBytes(),
                    "readFully has to fail rather than hand back a half-filled array");
        }
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        Pair pair = connected();
        pair.client().close();
        pair.client().close();
        pair.server().close();
        pair.listener().close();
    }
}
