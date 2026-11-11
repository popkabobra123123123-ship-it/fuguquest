package com.research.fuguroot;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * Minimal adb-over-TCP client for the on-device self-connect (127.0.0.1:5555).
 *
 * Implements the classic v1 protocol: CNXN handshake, legacy RSA AUTH (SHA1withRSA token
 * signature + android_pubkey-format public key — on first use the headset shows the usual
 * "Allow USB debugging?" RSA confirmation), and the `shell:` service with live output
 * streaming. That is all the front-end needs: it unpacks fuguquest + targets/ from its
 * own APK into /data/local/tmp and then drives `fuguquest --local`.
 *
 * Plain socket on purpose: the classic tcpip adbd on port 5555 is unencrypted. The TLS /
 * SPAKE2 wireless-debugging pairing flow is intentionally out of scope.
 */
public class AdbShell {

    /** streaming callback for shell output */
    public interface Sink { void onOut(String chunk); }
    /** one-line status callback (connect / auth progress) */
    public interface Status { void onStatus(String s); }

    // atransport packet commands (four ASCII chars, little-endian u32 on the wire)
    private static final int CNXN = 0x4e584e43;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257;
    private static final int AUTH = 0x48545541;

    private static final int AUTH_TOKEN = 1, AUTH_SIG = 2, AUTH_KEY = 3;

    private final File keyDir;
    private final Status status;
    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private RSAPrivateKey priv;
    private RSAPublicKey pub;

    public AdbShell(File keyDir, Status status) {
        this.keyDir = keyDir;
        this.status = status;
    }

    // ------------------------------------------------------------------ wire IO

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private void send(int cmd, int a0, int a1, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        byte[] hdr = new byte[24];
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        CRC32 crc = new CRC32();
        crc.update(payload);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(payload.length)
         .putInt((int) crc.getValue()).putInt(cmd ^ 0xffffffff);
        out.write(hdr);
        if (payload.length > 0) out.write(payload);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hdr = new byte[24];
        readFully(hdr);
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.a0 = h.getInt();
        m.a1 = h.getInt();
        int len = h.getInt();
        int magic = h.getInt();
        if (magic != (m.cmd ^ 0xffffffff)) throw new IOException("adb: bad packet magic");
        m.data = new byte[len];
        if (len > 0) readFully(m.data);
        return m;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("adb: connection closed");
            off += n;
        }
    }

    // ------------------------------------------------------------------ session

    /** Connect (with the legacy RSA AUTH exchange) to a classic tcpip adbd. */
    public void connect(String host, int port) throws Exception {
        loadKey();
        status.onStatus("connect " + host + ":" + port + " ...");
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 5000);
        sock.setTcpNoDelay(true);
        in = sock.getInputStream();
        out = sock.getOutputStream();

        send(CNXN, 0x01000000, 4096, "host::\0".getBytes("US-ASCII"));

        int tokens = 0;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) { status.onStatus("adbd connected/authorized"); return; }
            if (m.cmd == AUTH && m.a0 == AUTH_TOKEN) {
                if (++tokens == 1) {
                    byte[] sig = signToken(m.data);
                    if (sig != null) { send(AUTH, AUTH_SIG, 0, sig); continue; }
                }
                // our key is not in /data/misc/adb/adb_keys (first use): offer it — the
                // headset shows "Allow USB debugging?" and the user taps Allow.
                status.onStatus("waiting for the Allow-USB-debugging confirmation on the headset ...");
                send(AUTH, AUTH_KEY, 0, (pubkeyString() + "\0").getBytes("US-ASCII"));
                continue;
            }
            throw new IOException("adb: unexpected packet 0x" + Integer.toHexString(m.cmd));
        }
    }

    /** Run `shell:<cmd>` and stream its output; returns when the stream closes. */
    public void shell(String cmd, Sink sink) throws IOException {
        final int local = 1;
        send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes("US-ASCII"));
        int remote = -1;
        StringBuilder buf = new StringBuilder();
        while (true) {
            Msg m = read();
            if (m.cmd == OKAY) { remote = m.a0; continue; }
            if (m.cmd == WRTE) {
                if (remote < 0) remote = m.a0;
                if (m.data.length > 0) {
                    buf.append(new String(m.data, 0, m.data.length));
                    int nl;
                    while ((nl = buf.indexOf("\n")) >= 0) {
                        sink.onOut(buf.substring(0, nl + 1));
                        buf.delete(0, nl + 1);
                    }
                }
                send(OKAY, local, remote, null); // flow-control ack for every WRTE
                continue;
            }
            if (m.cmd == CLSE) break;
            throw new IOException("adb: unexpected packet 0x" + Integer.toHexString(m.cmd));
        }
        if (buf.length() > 0) sink.onOut(buf.toString());
    }

    public void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignore) {}
        sock = null; in = null; out = null;
    }

    // ------------------------------------------------------------------ RSA key

    private void loadKey() throws Exception {
        File pf = new File(keyDir, "adbkey.pk8");
        File qf = new File(keyDir, "adbkey.pub");
        if (pf.isFile() && qf.isFile()) {
            KeyFactory f = KeyFactory.getInstance("RSA");
            priv = (RSAPrivateKey) f.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(readFile(pf))));
            pub = (RSAPublicKey) f.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(readFile(qf))));
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        priv = (RSAPrivateKey) kp.getPrivate();
        pub = (RSAPublicKey) kp.getPublic();
        writeFile(pf, Base64.getEncoder().encodeToString(priv.getEncoded()));
        writeFile(qf, Base64.getEncoder().encodeToString(pub.getEncoded()));
    }

    /** legacy adb token signature: SHA1withRSA over the 20-byte token */
    private byte[] signToken(byte[] token) {
        try {
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign(priv);
            s.update(token);
            return s.sign();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Public key in adbd's android_pubkey format (system/libcrypto_utils):
     * u32 modulus_size_words(=64), u32 n0inv, u8 modulus[256] LE,
     * u8 rr[256] LE (= 2^4096 mod n), u32 exponent — then base64 + " <name>",
     * i.e. exactly one line of an adb_keys file.
     */
    private String pubkeyString() {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue()).put(toLE(n, 256)).put(toLE(rr, 256)).putInt(e.intValue());
        return Base64.getEncoder().encodeToString(b.array()) + " fuguroot";
    }

    private static byte[] toLE(BigInteger v, int len) {
        byte[] be = v.toByteArray(); // big-endian, may carry a sign byte
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    private static String readFile(File f) throws IOException {
        try (FileInputStream i = new FileInputStream(f)) {
            try (java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                for (int r; (r = i.read(buf)) > 0; ) o.write(buf, 0, r);
                return o.toString("US-ASCII").trim();
            }
        }
    }

    private static void writeFile(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes("US-ASCII"));
        }
    }
}
