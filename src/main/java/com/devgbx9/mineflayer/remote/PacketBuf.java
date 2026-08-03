package com.devgbx9.mineflayer.remote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Reads and writes the primitive types the Minecraft protocol is built from.
 *
 * <p>Two roles in one class, because they share the varint format: a
 * {@link Reader} over a byte array holding one decoded packet, and a
 * {@link Writer} that accumulates one packet before it is framed.
 *
 * <p>Only the types this bot actually exchanges are implemented. The bot
 * connects and stays connected; it does not decode world state, so the great
 * majority of incoming packets are skipped rather than parsed. That is what
 * keeps this file short and independent of the version-specific packet layouts.
 */
final class PacketBuf {

    private PacketBuf() {
    }

    /** A varint is 7 bits per byte, so 32 bits never needs more than 5. */
    private static final int MAX_VARINT_BYTES = 5;

    /** Reads primitives out of a decoded packet body. */
    static final class Reader {

        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - pos;
        }

        byte readByte() throws IOException {
            if (pos >= data.length) {
                throw new EOFException("packet ended early");
            }
            return data[pos++];
        }

        boolean readBoolean() throws IOException {
            return readByte() != 0;
        }

        int readUnsignedByte() throws IOException {
            return readByte() & 0xFF;
        }

        int readVarInt() throws IOException {
            int value = 0;
            for (int i = 0; i < MAX_VARINT_BYTES; i++) {
                int b = readUnsignedByte();
                value |= (b & 0x7F) << (i * 7);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("varint longer than " + MAX_VARINT_BYTES + " bytes");
        }

        long readLong() throws IOException {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | readUnsignedByte();
            }
            return value;
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || length > remaining()) {
                throw new IOException("declared length " + length + " exceeds the packet");
            }
            byte[] out = new byte[length];
            System.arraycopy(data, pos, out, 0, length);
            pos += length;
            return out;
        }

        /** A length-prefixed byte array, which is how keys and payloads travel. */
        byte[] readByteArray() throws IOException {
            return readBytes(readVarInt());
        }

        String readString() throws IOException {
            return new String(readByteArray(), StandardCharsets.UTF_8);
        }

        UUID readUuid() throws IOException {
            return new UUID(readLong(), readLong());
        }
    }

    /** Builds a packet body. The packet id is prepended by the caller. */
    static final class Writer {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer writeByte(int value) {
            out.write(value);
            return this;
        }

        Writer writeBoolean(boolean value) {
            return writeByte(value ? 1 : 0);
        }

        Writer writeVarInt(int value) {
            // Logical shift: a negative varint must not sign-extend forever.
            int v = value;
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
            return this;
        }

        Writer writeShort(int value) {
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
            return this;
        }

        Writer writeLong(long value) {
            for (int i = 7; i >= 0; i--) {
                out.write((int) (value >>> (i * 8)) & 0xFF);
            }
            return this;
        }

        Writer writeBytes(byte[] value) {
            out.writeBytes(value);
            return this;
        }

        Writer writeByteArray(byte[] value) {
            writeVarInt(value.length);
            return writeBytes(value);
        }

        Writer writeString(String value) {
            return writeByteArray(value.getBytes(StandardCharsets.UTF_8));
        }

        Writer writeUuid(UUID value) {
            return writeLong(value.getMostSignificantBits()).writeLong(value.getLeastSignificantBits());
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
