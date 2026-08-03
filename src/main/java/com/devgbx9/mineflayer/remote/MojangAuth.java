package com.devgbx9.mineflayer.remote;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Answers a premium server's encryption request.
 *
 * <p>A server with {@code online-mode=true} does not take a login at its word.
 * It sends an RSA public key and a challenge, and expects three things back: the
 * challenge returned encrypted (proving the client holds the matching session),
 * a fresh AES key for the rest of the connection, and - out of band - a record
 * at Mojang saying this account is joining this server. The server asks Mojang
 * separately, and if the answer disagrees with the login name, it disconnects.
 *
 * <p>The shared secret is what both sides derive the server hash from, so the
 * order matters: generate the key, tell Mojang, then send the key. Telling
 * Mojang after sending would race the server's own lookup.
 *
 * <p>This is the account holder authenticating as themselves. It cannot be used
 * to log in as an account whose token you do not have - the token is exactly
 * what Mojang checks.
 */
final class MojangAuth {

    private static final URI JOIN_ENDPOINT =
            URI.create("https://sessionserver.mojang.com/session/minecraft/join");

    /** Minecraft's AES key is 128-bit, and the server rejects any other size. */
    private static final int AES_KEY_BITS = 128;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private MojangAuth() {
    }

    /** A fresh AES key for the connection, generated per login. */
    static SecretKey newSharedSecret() throws IOException {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(AES_KEY_BITS, new SecureRandom());
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("this JVM has no AES key generator", e);
        }
    }

    /** Reads the server's RSA public key out of its encryption request. */
    static PublicKey parsePublicKey(byte[] encoded) throws IOException {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IOException("the server sent an unreadable public key", e);
        }
    }

    /**
     * Encrypts {@code data} to the server's public key.
     *
     * <p>Used for both the shared secret and the challenge, which is why it takes
     * raw bytes rather than either one specifically.
     */
    static byte[] encryptToServer(PublicKey serverKey, byte[] data) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, serverKey);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IOException("could not encrypt the login response", e);
        }
    }

    /**
     * Tells Mojang the account is joining this server.
     *
     * <p>The identifier both sides compute independently: SHA-1 over the server's
     * own id string, then the shared secret, then its public key. It is rendered
     * as a signed, non-zero-padded hex integer, which is unusual enough to be
     * worth stating - it is why {@link BigInteger#toString(int)} is used here
     * instead of formatting the digest bytes.
     *
     * @throws IOException if Mojang rejects the token, which is what a stale or
     *         wrong token looks like from here
     */
    static void joinServer(BotAccount account, String serverId, SecretKey secret,
            PublicKey serverKey) throws IOException {
        String hash = serverHash(serverId, secret, serverKey);

        // Hand-built rather than pulled through a JSON library: three known string
        // fields, none of them user-supplied in a way that could need escaping.
        String body = "{\"accessToken\":\"" + account.accessToken()
                + "\",\"selectedProfile\":\"" + account.undashedUuid()
                + "\",\"serverId\":\"" + hash + "\"}";

        HttpRequest request = HttpRequest.newBuilder(JOIN_ENDPOINT)
                .header("Content-Type", "application/json")
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        int status;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()) {
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while authenticating with Mojang", e);
        }

        // 204 is the documented success; 200 is accepted too rather than assuming
        // the response body is empty forever.
        if (status != 204 && status != 200) {
            throw new IOException("Mojang refused the session (HTTP " + status
                    + "). The access token is probably expired or does not own '"
                    + account.name() + "'.");
        }
    }

    /** The shared server identifier, as both the client and the server derive it. */
    private static String serverHash(String serverId, SecretKey secret, PublicKey serverKey)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(secret.getEncoded());
            digest.update(serverKey.getEncoded());
            return new BigInteger(digest.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("this JVM has no SHA-1 implementation", e);
        }
    }
}
