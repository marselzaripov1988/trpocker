package com.truholdem.service.wallet.av;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.service.wallet.WalletExceptions.KycMediaRejectedException;

/**
 * Scans KYC uploads with a ClamAV daemon (clamd) over its INSTREAM protocol — a raw socket, no client
 * dependency. Active when {@code app.payments.kyc-av-scan-enabled=true}. A {@code FOUND} response rejects the
 * upload; an unreachable/erroring clamd fails closed (the upload is not stored).
 */
@Component
@ConditionalOnProperty(name = "app.payments.kyc-av-scan-enabled", havingValue = "true")
public class ClamAvKycScanner implements KycAvScanner {

    private static final int CHUNK = 65536;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final AppProperties appProperties;

    public ClamAvKycScanner(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void scan(byte[] content) {
        AppProperties.Payments p = appProperties.getPayments();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(p.getClamavHost(), p.getClamavPort()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int off = 0; off < content.length; off += CHUNK) {
                int len = Math.min(CHUNK, content.length - off);
                out.write(ByteBuffer.allocate(4).putInt(len).array());
                out.write(content, off, len);
            }
            out.write(new byte[] { 0, 0, 0, 0 }); // zero-length chunk terminates the stream
            out.flush();

            String reply = readReply(socket.getInputStream());
            if (reply.contains("FOUND")) {
                throw new KycMediaRejectedException("KYC upload rejected by AV scan: " + reply.trim());
            }
            if (!reply.contains("OK")) {
                throw new IllegalStateException("KYC AV scan returned an unexpected response: " + reply.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("KYC AV scan failed (clamd unreachable?)", e);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0) {
                break; // clamd null-terminates the response in z-mode
            }
            buf.write(b);
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }
}
