package io.github.muntashirakon.adb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Real local ADB packet transport; only Android logging is stubbed by the JVM runner. */
public class AdbStreamRegressionTest {
    private ServerSocket server;
    private Socket peer;
    private AdbConnection connection;
    private AdbStream stream;
    private ExecutorService jobs;

    @Before public void setUp() throws Exception {
        jobs = Executors.newCachedThreadPool();
        server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        Future<?> accepted = jobs.submit(() -> {
            try {
                peer = server.accept();
                AdbProtocol.Message message = AdbProtocol.Message.parse(peer.getInputStream(), AdbProtocol.A_VERSION_SKIP_CHECKSUM, 4096);
                assertEquals(AdbProtocol.A_CNXN, message.command);
                peer.getOutputStream().write(AdbProtocol.generateMessage(AdbProtocol.A_CNXN,
                        AdbProtocol.A_VERSION_SKIP_CHECKSUM, 4096, "device::\0".getBytes(StandardCharsets.UTF_8)));
                peer.getOutputStream().flush();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        PrivateKey key = new PrivateKey() {
            public String getAlgorithm() { return "RSA"; }
            public String getFormat() { return "PKCS#8"; }
            public byte[] getEncoded() { return new byte[0]; }
        };
        Certificate certificate = new Certificate("X.509") {
            public byte[] getEncoded() { return new byte[0]; }
            public void verify(PublicKey key) { }
            public void verify(PublicKey key, String provider) { }
            public String toString() { return "No authentication required by this test peer"; }
            public PublicKey getPublicKey() { return null; }
        };
        connection = AdbConnection.create("127.0.0.1", server.getLocalPort(), key, certificate, 30);
        assertTrue(connection.connect(2, TimeUnit.SECONDS, true));
        accepted.get(2, TimeUnit.SECONDS);
        stream = new AdbStream(connection, 42);
    }

    private String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[3]; int count;
        while ((count = input.read(bytes)) != -1) out.write(bytes, 0, count);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    @Test public void queuedOutputFollowedByCloseMustReachEof() throws Exception {
        stream.addPayload("armed\nPAD_EXIT:test\n".getBytes(StandardCharsets.UTF_8));
        stream.notifyClose(true);
        Future<String> read = jobs.submit(() -> readAll(stream.openInputStream()));
        assertEquals("armed\nPAD_EXIT:test\n", read.get(500, TimeUnit.MILLISECONDS));
    }

    @Test public void closingWhileReaderWaitsIsNormalEof() throws Exception {
        Future<Integer> read = jobs.submit(() -> stream.openInputStream().read(new byte[8]));
        Thread.sleep(30);
        stream.notifyClose(true);
        assertEquals(Integer.valueOf(-1), read.get(500, TimeUnit.MILLISECONDS));
    }

    @Test public void peerCloseMustNotDiscardRemainingReadBuffer() throws Exception {
        stream.addPayload("123456789".getBytes(StandardCharsets.UTF_8));
        InputStream input = stream.openInputStream();
        byte[] first = new byte[3]; assertEquals(3, input.read(first));
        stream.notifyClose(true);
        assertEquals("456789", readAll(input));
    }

    @Test public void singleByteReadReturnsUnsignedByte() throws Exception {
        stream.addPayload(new byte[]{(byte) 255});
        assertEquals(255, stream.openInputStream().read());
    }

    @Test public void localAbortUnblocksWaitingCommandReader() throws Exception {
        Future<Integer> read = jobs.submit(() -> stream.openInputStream().read(new byte[8]));
        Thread.sleep(30);
        stream.abort();
        try { read.get(500, TimeUnit.MILLISECONDS); fail("An aborted command must report an error"); }
        catch (java.util.concurrent.ExecutionException e) { assertTrue(e.getCause() instanceof IOException); }
    }

    /** Inject an ACK during OPEN's send, reproducing a response arriving before wait(). */
    private void respondDuringOpen(boolean acknowledge, String output) throws Exception {
        Field transport = AdbConnection.class.getDeclaredField("mPlainOutputStream");
        transport.setAccessible(true);
        OutputStream original = (OutputStream) transport.get(connection);
        Field opened = AdbConnection.class.getDeclaredField("mOpenedStreams");
        opened.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer, AdbStream> streams = (Map<Integer, AdbStream>) opened.get(connection);
        transport.set(connection, new OutputStream() {
            @Override public void write(int b) throws IOException { original.write(b); }
            @Override public void write(byte[] b, int offset, int length) throws IOException {
                ByteBuffer packet = ByteBuffer.wrap(b, offset, length).order(ByteOrder.LITTLE_ENDIAN);
                if (length >= 24 && packet.getInt(offset) == AdbProtocol.A_OPEN) {
                    AdbStream pending = streams.get(packet.getInt(offset + 4));
                    if (acknowledge) { pending.updateRemoteId(7); pending.readyForWrite(); }
                    if (output != null) pending.addPayload(output.getBytes(StandardCharsets.UTF_8));
                    if (!acknowledge || output != null) pending.notifyClose(true);
                    synchronized (pending) { pending.notifyAll(); }
                } else original.write(b, offset, length);
            }
        });
    }

    @Test public void earlyOpenAcknowledgementCannotBeLost() throws Exception {
        respondDuringOpen(true, null);
        Future<AdbStream> opened = jobs.submit(() -> connection.open("shell:true"));
        AdbStream result = opened.get(500, TimeUnit.MILLISECONDS);
        assertTrue(result.isOpenAcknowledged()); result.close();
    }

    @Test public void fastCommandCanFinishBeforeOpenCallerStartsReading() throws Exception {
        respondDuringOpen(true, "armed\nPAD_EXIT:0\n");
        Future<String> command = jobs.submit(() -> {
            try (AdbStream result = connection.open("shell:true")) { return readAll(result.openInputStream()); }
        });
        assertEquals("armed\nPAD_EXIT:0\n", command.get(500, TimeUnit.MILLISECONDS));
    }

    @Test public void earlyRejectedOpenReturnsErrorInsteadOfHanging() throws Exception {
        respondDuringOpen(false, null);
        Future<AdbStream> opened = jobs.submit(() -> connection.open("shell:true"));
        try { opened.get(500, TimeUnit.MILLISECONDS); fail("Rejected stream must fail"); }
        catch (java.util.concurrent.ExecutionException e) { assertTrue(e.getCause() instanceof java.net.ConnectException); }
    }

    @Test public void connectionAbortClosesTheTlsWrapper() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        Socket tls = new Socket() { @Override public synchronized void close() { closed.set(true); } };
        Field wrapper = AdbConnection.class.getDeclaredField("mTlsSocket"); wrapper.setAccessible(true); wrapper.set(connection, tls);
        connection.close();
        assertTrue("Timeout must close the actual TLS wrapper, not only the plain socket", closed.get());
    }

    @After public void tearDown() throws Exception {
        if (stream != null) stream.notifyClose(false);
        if (connection != null) connection.close();
        if (peer != null) peer.close();
        if (server != null) server.close();
        if (jobs != null) { jobs.shutdownNow(); jobs.awaitTermination(2, TimeUnit.SECONDS); }
    }
}
