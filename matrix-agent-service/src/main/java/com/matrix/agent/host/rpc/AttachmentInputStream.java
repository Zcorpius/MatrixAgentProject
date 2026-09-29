package com.matrix.agent.host.rpc;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns an incoming descriptor. Polling bounds idle waits; an interruptible channel closes in-flight reads. */
public final class AttachmentInputStream extends InputStream {
    private final ParcelFileDescriptor descriptor;
    private final long deadline;
    private final FileChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AttachmentInputStream(ParcelFileDescriptor descriptor, long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("positive timeout required");
        this.descriptor = java.util.Objects.requireNonNull(descriptor);
        deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        channel = new FileInputStream(descriptor.getFileDescriptor()).getChannel();
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.requireNonNull(bytes);
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        while (true) {
            ensureActive();
            var poll = new StructPollfd(); poll.fd = descriptor.getFileDescriptor(); poll.events = (short) OsConstants.POLLIN;
            try {
                if (Os.poll(new StructPollfd[]{poll}, 50) == 0) continue;
                ensureActive();
                int count = channel.read(ByteBuffer.wrap(bytes, offset, length));
                if (count < 0) descriptor.checkError();
                return count;
            } catch (ErrnoException failure) {
                if (failure.errno == OsConstants.EAGAIN || failure.errno == OsConstants.EINTR) continue;
                throw new IOException("attachment read failed", failure);
            }
        }
    }

    private void ensureActive() throws IOException {
        if (closed.get()) throw new IOException("attachment closed");
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) {
            throw new InterruptedIOException("attachment read deadline or cancellation");
        }
    }

    @Override public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try { channel.close(); } finally { descriptor.close(); }
        }
    }
}
