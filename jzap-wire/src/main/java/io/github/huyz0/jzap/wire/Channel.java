package io.github.huyz0.jzap.wire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** Framed read/write over a socket, used identically by both ends. */
public final class Channel implements AutoCloseable {

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
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] readBytes() throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
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
