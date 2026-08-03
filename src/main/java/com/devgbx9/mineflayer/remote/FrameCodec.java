package com.devgbx9.mineflayer.remote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * The wire format underneath every packet: length prefix, then compression,
 * then encryption.
 *
 * <p>Reading unwraps those in reverse - decrypt, read the length, decompress -
 * and the order is not a choice. Encryption wraps the whole stream including the
 * length prefix, so nothing can be read until it is undone; compression sits
 * inside the frame, so the length has to be known first.
 *
 * <p>Both layers start off and are switched on mid-stream, which is what the
 * protocol asks for: the server sends the compression threshold and the
 * encryption request as ordinary unencrypted, uncompressed packets, and every
 * packet after each of those is wrapped. Enabling a layer therefore has to take
 * effect on the packet after the one that announced it, never retroactively.
 *
 * <p>Not thread-safe. Reads happen on the bot's own reader thread; writes are
 * serialised by the caller through a lock on this object.
 */
final class FrameCodec {

    /** No compression until the server says otherwise; -1 means "off". */
    private static final int COMPRESSION_OFF = -1;

    /**
     * Guards against a hostile or broken length prefix. The vanilla server caps
     * frames well below this, so anything larger is a bad read rather than a big
     * packet, and allocating on it would be the wrong response.
     */
    private static final int MAX_FRAME_BYTES = 1 << 23;

    private final InputStream rawIn;
    private final OutputStream rawOut;

    /** Set once the server sends its compression threshold. */
    private int compressionThreshold = COMPRESSION_OFF;

    /** Both null until the encryption handshake completes, then both set. */
    private Cipher decrypt;
    private Cipher encrypt;

    FrameCodec(InputStream in, OutputStream out) {
        this.rawIn = in;
        this.rawOut = out;
    }

    /**
     * Turns on AES for both directions.
     *
     * <p>Minecraft uses AES/CFB8 with the secret key doubling as the IV. CFB8 is
     * a stream mode, so the two ciphers hold running state and must be created
     * once here rather than per packet: re-initialising either one would desync
     * the stream and turn every later packet into noise.
     */
    void enableEncryption(SecretKey key) throws IOException {
        try {
            IvParameterSpec iv = new IvParameterSpec(key.getEncoded());
            Cipher in = Cipher.getInstance("AES/CFB8/NoPadding");
            in.init(Cipher.DECRYPT_MODE, key, iv);
            Cipher out = Cipher.getInstance("AES/CFB8/NoPadding");
            out.init(Cipher.ENCRYPT_MODE, key, iv);
            // Assigned together: a half-enabled codec could neither read nor write.
            this.decrypt = in;
            this.encrypt = out;
        } catch (Exception e) {
            throw new IOException("could not enable encryption", e);
        }
    }

    /**
     * Sets the compression threshold, in bytes.
     *
     * <p>A negative threshold turns compression off again, which is what the
     * server means when it sends one.
     */
    void setCompressionThreshold(int threshold) {
        this.compressionThreshold = threshold < 0 ? COMPRESSION_OFF : threshold;
    }

    boolean compressionEnabled() {
        return compressionThreshold != COMPRESSION_OFF;
    }

    /**
     * Reads one packet body, with the packet id still at its head.
     *
     * @return the decoded body, never null
     * @throws EOFException if the stream ended, which is how a remote close
     *         arrives when the server drops the connection without a disconnect
     */
    byte[] readPacket() throws IOException {
        int length = readVarInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("frame length " + length + " is out of range");
        }
        byte[] frame = readFully(length);

        return compressionEnabled() ? decompress(frame) : frame;
    }

    /**
     * Reads the frame's length prefix, decrypting as it goes.
     *
     * <p>A varint has to be read one byte at a time, because only its high bit
     * says whether another byte follows. That rules out reading ahead: a buffered
     * reader would consume bytes belonging to the frame body, and under a stream
     * cipher those bytes cannot be put back.
     */
    private int readVarInt() throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = readByte();
            value |= (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("frame length prefix is longer than 5 bytes");
    }

    /** Reads one byte, decrypted if encryption is on. */
    private int readByte() throws IOException {
        int b = rawIn.read();
        if (b == -1) {
            throw new EOFException("stream closed while reading a frame length");
        }
        if (decrypt == null) {
            return b;
        }
        return decryptExact(new byte[] {(byte) b})[0] & 0xFF;
    }

    /** Reads exactly {@code length} bytes, decrypted if encryption is on. */
    private byte[] readFully(int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = rawIn.read(buffer, read, length - read);
            if (n == -1) {
                throw new EOFException("stream closed " + read + " bytes into a "
                        + length + " byte frame");
            }
            read += n;
        }
        return decrypt == null ? buffer : decryptExact(buffer);
    }

    /**
     * Decrypts {@code input} in place of the stream wrappers in {@code javax.crypto}.
     *
     * <p>CFB8 is a stream mode with a one-byte segment, so it emits exactly one
     * output byte per input byte and holds nothing back. That is what makes a
     * plain {@code update} call safe here where a {@code CipherInputStream} is
     * not: the stream wrapper reads ahead into its own buffer, and any bytes it
     * buffered past the current frame would be lost with it, desyncing every
     * packet after.
     */
    private byte[] decryptExact(byte[] input) throws IOException {
        byte[] out;
        try {
            out = decrypt.update(input);
        } catch (Exception e) {
            throw new IOException("could not decrypt an incoming packet", e);
        }
        if (out == null || out.length != input.length) {
            throw new IOException("the cipher returned "
                    + (out == null ? "nothing" : out.length + " bytes")
                    + " for " + input.length + " bytes of input");
        }
        return out;
    }

    /**
     * Undoes the compression layer.
     *
     * <p>A compressed frame carries the uncompressed size in front. Zero is not a
     * size but a flag: it means the packet was below the threshold and was sent
     * as-is, so the remainder is already plain.
     */
    private byte[] decompress(byte[] frame) throws IOException {
        PacketBuf.Reader reader = new PacketBuf.Reader(frame);
        int uncompressedSize = reader.readVarInt();
        byte[] rest = reader.readBytes(reader.remaining());

        if (uncompressedSize == 0) {
            return rest;
        }
        if (uncompressedSize > MAX_FRAME_BYTES) {
            throw new IOException("declared uncompressed size " + uncompressedSize
                    + " is out of range");
        }

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(rest);
            byte[] out = new byte[uncompressedSize];
            int written = inflater.inflate(out);
            if (written != uncompressedSize) {
                throw new IOException("compressed packet declared " + uncompressedSize
                        + " bytes but produced " + written);
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("malformed compressed packet", e);
        } finally {
            inflater.end();
        }
    }

    /**
     * Writes one packet: {@code id} followed by {@code body}.
     *
     * <p>Flushed on every packet. The bot sends little and what it does send is
     * time-sensitive - a keepalive reply held in a buffer is a keepalive the
     * server never sees, and it disconnects on that.
     */
    void writePacket(int id, byte[] body) throws IOException {
        byte[] payload = new PacketBuf.Writer()
                .writeVarInt(id)
                .writeBytes(body)
                .toByteArray();

        byte[] frame = compressionEnabled() ? compress(payload) : payload;

        byte[] out = new PacketBuf.Writer()
                .writeVarInt(frame.length)
                .writeBytes(frame)
                .toByteArray();

        if (encrypt != null) {
            try {
                out = encrypt.update(out);
            } catch (Exception e) {
                throw new IOException("could not encrypt an outgoing packet", e);
            }
        }

        // Written in one call so a partial frame cannot interleave with another
        // thread's, and because CFB8 output is order-dependent.
        rawOut.write(out);
        rawOut.flush();
    }

    /**
     * Applies the compression layer.
     *
     * <p>Below the threshold the packet is sent uncompressed behind a zero size
     * marker, which is cheaper than deflating a handful of bytes and is what the
     * protocol requires - a server rejects a small packet that was compressed
     * anyway.
     */
    private byte[] compress(byte[] payload) throws IOException {
        if (payload.length < compressionThreshold) {
            return new PacketBuf.Writer()
                    .writeVarInt(0)
                    .writeBytes(payload)
                    .toByteArray();
        }

        Deflater deflater = new Deflater();
        try {
            deflater.setInput(payload);
            deflater.finish();

            ByteArrayOutputStream deflated = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                deflated.write(chunk, 0, deflater.deflate(chunk));
            }

            return new PacketBuf.Writer()
                    .writeVarInt(payload.length)
                    .writeBytes(deflated.toByteArray())
                    .toByteArray();
        } finally {
            deflater.end();
        }
    }
}
