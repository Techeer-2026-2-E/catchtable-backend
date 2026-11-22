package com.catchtable.notification.job;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Spring이 만든 SSE 프레임을 준비된 서블릿 출력에만 쓴다. 소켓 대기는 컨테이너의 WriteListener가 담당한다. */
final class SseServletOutput extends ServletOutputStream implements WriteListener {
    private static final int CHUNK = 8192;

    private final ServletOutputStream socket;
    private final long timeoutNanos;
    private final ByteArrayOutputStream opening = new ByteArrayOutputStream();
    private boolean armed;
    private boolean openingWritten;
    private boolean open;
    private IOException failure;
    private Runnable register;
    private Runnable abort;

    SseServletOutput(ServletOutputStream socket, Duration timeout) {
        this.socket = socket;
        this.timeoutNanos = timeout.toNanos();
    }

    synchronized void arm(Runnable register, Runnable abort) {
        this.register = register;
        this.abort = abort;
        armed = true;
        socket.setWriteListener(this);
    }

    @Override
    public synchronized void onWritePossible() throws IOException {
        try {
            if (failure != null) return;
            if (!openingWritten && socket.isReady()) {
                // 연결을 공개한 뒤 인사를 보낸다. 같은 모니터가 이후 업무 프레임의 추월을 막는다.
                register.run();
                openingWritten = true;
                opening.writeTo(socket);
                opening.reset();
            }
            if (openingWritten && !open && socket.isReady()) {
                socket.flush();
                open = true;
            }
        } catch (IOException ex) {
            failure = ex;
            throw ex;
        } finally {
            notifyAll();
        }
    }

    @Override
    public synchronized void onError(Throwable cause) {
        if (failure == null) failure = new IOException("SSE 연결 종료", cause);
        notifyAll();
    }

    @Override
    public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        try {
            writeReady(bytes, offset, length);
        } catch (SocketTimeoutException timedOut) {
            abort.run();
            throw timedOut;
        }
    }

    private synchronized void writeReady(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (!armed) {
            // 초기 MVC 디스패치에서는 connect 인사만 준비한다. 첫 onWritePossible은 디스패치 뒤에 온다.
            if (length > CHUNK - opening.size()) throw new IOException("SSE 연결 인사 크기 초과");
            opening.write(bytes, offset, length);
            return;
        }
        long deadline = SseConnectionWriter.deadlineOr(timeoutNanos);
        while (length > 0) {
            awaitWritable(deadline);
            int count = Math.min(length, CHUNK);
            socket.write(bytes, offset, count);
            offset += count;
            length -= count;
        }
    }

    @Override
    public void flush() throws IOException {
        try {
            flushReady();
        } catch (SocketTimeoutException timedOut) {
            abort.run();
            throw timedOut;
        }
    }

    private synchronized void flushReady() throws IOException {
        if (!armed) return;
        long deadline = SseConnectionWriter.deadlineOr(timeoutNanos);
        awaitWritable(deadline);
        socket.flush();
        // flush가 컨테이너 버퍼에 남긴 데이터도 다음 쓰기 가능 시점까지 확인한다.
        awaitWritable(deadline);
    }

    private void awaitWritable(long deadline) throws IOException {
        while (true) {
            if (failure != null) throw failure;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                failure = new SocketTimeoutException("SSE 연결 쓰기 기한 초과");
                notifyAll();
                throw failure;
            }
            if (open && socket.isReady()) return;
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("SSE 쓰기 중단");
            }
        }
    }

    @Override
    public synchronized boolean isReady() {
        return open && failure == null && socket.isReady();
    }

    @Override
    public void setWriteListener(WriteListener listener) {
        throw new IllegalStateException("SSE 출력 리스너는 이미 관리되고 있습니다.");
    }
}
