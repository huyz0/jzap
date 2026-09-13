package io.github.huyz0.jzap.wire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Framed read/write over a socket, used identically by both ends. */
public final class Channel implements AutoCloseable {

    /**
     * Largest single string or byte array a frame may declare. Well above anything real -- the
     * biggest thing sent is a transformed class -- and far below what exhausts a heap.
     */
    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public Channel(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        InputStream rawIn = socket.getInputStream();
        OutputStream rawOut = socket.getOutputStream();
        this.in = new DataInputStream(new BufferedInputStream(rawIn));
        this.out = new DataOutputStream(new BufferedOutputStream(rawOut));
    }

    /**
     * Bounds how long a single read may block. The controller uses this to detect a mutant
     * that has hung the minion, which is the only way to deal with an infinite loop in Java:
     * the process has to be killed, because a thread cannot be.
     */
    public void readTimeout(int millis) {
        try {
            socket.setSoTimeout(millis);
        } catch (IOException e) {
            throw new WireException("cannot set read timeout", e);
        }
    }

    public void writeByte(int b) {
        try {
            out.writeByte(b);
        } catch (IOException e) {
            throw new WireException("write failed", e);
        }
    }

    public void writeInt(int v) {
        try {
            out.writeInt(v);
        } catch (IOException e) {
            throw new WireException("write failed", e);
        }
    }

    public void writeLong(long v) {
        try {
            out.writeLong(v);
        } catch (IOException e) {
            throw new WireException("write failed", e);
        }
    }

    public void writeBool(boolean v) {
        writeByte(v ? 1 : 0);
    }

    public void writeString(String s) {
        try {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        } catch (IOException e) {
            throw new WireException("write failed", e);
        }
    }

    public void writeBytes(byte[] b) {
        try {
            out.writeInt(b.length);
            out.write(b);
        } catch (IOException e) {
            throw new WireException("write failed", e);
        }
    }

    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new WireException("flush failed", e);
        }
    }

    public int readByte() throws IOException {
        return in.readByte();
    }

    public int readInt() throws IOException {
        return in.readInt();
    }

    public long readLong() throws IOException {
        return in.readLong();
    }

    public boolean readBool() throws IOException {
        return in.readByte() != 0;
    }

    public String readString() throws IOException {
        byte[] b = new byte[readLength("a string")];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public byte[] readBytes() throws IOException {
        byte[] b = new byte[readLength("a byte array")];
        in.readFully(b);
        return b;
    }

    /**
     * A length prefix, checked before it is used to size an array.
     *
     * <p>Not about untrusted input -- both ends of this channel are processes jzap started, on the
     * loopback interface. It is about what a desynchronised stream does next. A length read from
     * the middle of some other message is an arbitrary int, and using it directly either throws
     * {@link NegativeArraySizeException}, which says nothing about the real problem, or asks for
     * gigabytes and takes the whole run down with an {@link OutOfMemoryError}. Failing here names
     * the actual fault instead, and costs one comparison per frame.
     */
    private int readLength(String what) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw new WireException("refusing to read " + what + " of " + len + " bytes: the "
                    + "channel is out of step with the other end");
        }
        return len;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing a broken channel is not an error worth reporting
        }
    }
}
