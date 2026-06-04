package com.truholdem.service.wallet.av;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.config.AppProperties;
import com.truholdem.service.wallet.WalletExceptions.KycMediaRejectedException;

@DisplayName("KYC AV scanners (clamd INSTREAM + no-op)")
class KycAvScannerTest {

    /** A minimal fake clamd: reads one INSTREAM exchange and replies with a configurable verdict line. */
    private static final class FakeClamd implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<byte[]> received = new AtomicReference<>();

        FakeClamd(String reply) throws IOException {
            this.server = new ServerSocket(0);
            this.thread = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    StringBuilder cmd = new StringBuilder();
                    int b;
                    while ((b = in.read()) != -1 && b != 0) {
                        cmd.append((char) b);
                    }
                    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                    while (true) {
                        int len = in.readInt();
                        if (len == 0) {
                            break;
                        }
                        byte[] chunk = new byte[len];
                        in.readFully(chunk);
                        body.write(chunk);
                    }
                    received.set(body.toByteArray());
                    OutputStream out = s.getOutputStream();
                    out.write(reply.getBytes(StandardCharsets.US_ASCII));
                    out.write(0);
                    out.flush();
                } catch (IOException ignored) {
                    // test socket closing
                }
            });
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        byte[] received() {
            return received.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private AppProperties propsFor(int port) {
        AppProperties props = new AppProperties();
        props.getPayments().setClamavHost("localhost");
        props.getPayments().setClamavPort(port);
        return props;
    }

    private FakeClamd clamd;

    @AfterEach
    void tearDown() throws IOException {
        if (clamd != null) {
            clamd.close();
        }
    }

    @Test
    @DisplayName("clean upload (OK) passes and the bytes reach clamd intact")
    void cleanPasses() throws Exception {
        clamd = new FakeClamd("stream: OK");
        byte[] payload = "hello kyc video".getBytes(StandardCharsets.UTF_8);

        ClamAvKycScanner scanner = new ClamAvKycScanner(propsFor(clamd.port()));
        assertThatCode(() -> scanner.scan(payload)).doesNotThrowAnyException();

        clamd.thread.join(2000);
        assertThat(clamd.received()).isEqualTo(payload);
    }

    @Test
    @DisplayName("infected upload (FOUND) is rejected")
    void infectedRejected() throws Exception {
        clamd = new FakeClamd("stream: Eicar-Test-Signature FOUND");
        ClamAvKycScanner scanner = new ClamAvKycScanner(propsFor(clamd.port()));

        assertThatThrownBy(() -> scanner.scan(new byte[] { 1, 2, 3 }))
                .isInstanceOf(KycMediaRejectedException.class)
                .hasMessageContaining("FOUND");
    }

    @Test
    @DisplayName("unreachable clamd fails closed (not a media rejection)")
    void unreachableFailsClosed() {
        AppProperties props = new AppProperties();
        props.getPayments().setClamavHost("localhost");
        props.getPayments().setClamavPort(1); // nothing listening
        ClamAvKycScanner scanner = new ClamAvKycScanner(props);

        assertThatThrownBy(() -> scanner.scan(new byte[] { 1 }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("no-op scanner accepts anything")
    void noopAcceptsAll() {
        assertThatCode(() -> new NoopKycAvScanner().scan(new byte[] { 9, 9, 9 }))
                .doesNotThrowAnyException();
    }
}
