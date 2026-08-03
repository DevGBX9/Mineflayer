package com.devgbx9.mineflayer.remote;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PublicKey;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import com.devgbx9.mineflayer.NmsReflect;

/**
 * One connection to a remote server, from opening the socket to leaving.
 *
 * <p>This is an ordinary Minecraft client, written small. It speaks the same
 * protocol any client speaks, which is the whole point: the target server needs
 * no plugin, no configuration and no knowledge of this one. It sees a client
 * connect, and that is all there is to see.
 *
 * <p>The four phases run in a fixed order - handshake, login, configuration,
 * play - and each ends when the server says so. Only the packets the bot has to
 * answer are decoded; everything else has its body skipped. That is what keeps
 * this class independent of the packet layouts that shift between versions, and
 * it is enough, because the bot's job is to arrive and stay rather than to model
 * the world.
 *
 * <p>Runs on its own thread. {@link #close} may be called from any thread and
 * closes the socket, which is what unblocks the read loop.
 */
final class RemoteBotConnection {

    /** {@code minecraft:} names, resolved to numeric ids through {@link PacketIds}. */
    private static final String INTENTION = "minecraft:intention";
    private static final String HELLO = "minecraft:hello";
    private static final String KEY = "minecraft:key";
    private static final String LOGIN_COMPRESSION = "minecraft:login_compression";
    private static final String LOGIN_FINISHED = "minecraft:login_finished";
    private static final String LOGIN_DISCONNECT = "minecraft:login_disconnect";
    private static final String LOGIN_ACKNOWLEDGED = "minecraft:login_acknowledged";
    private static final String CUSTOM_QUERY = "minecraft:custom_query";
    private static final String CUSTOM_QUERY_ANSWER = "minecraft:custom_query_answer";
    private static final String SELECT_KNOWN_PACKS = "minecraft:select_known_packs";
    private static final String FINISH_CONFIGURATION = "minecraft:finish_configuration";
    private static final String CODE_OF_CONDUCT = "minecraft:code_of_conduct";
    private static final String ACCEPT_CODE_OF_CONDUCT = "minecraft:accept_code_of_conduct";
    private static final String CLIENT_INFORMATION = "minecraft:client_information";
    private static final String KEEP_ALIVE = "minecraft:keep_alive";
    private static final String PING = "minecraft:ping";
    private static final String PONG = "minecraft:pong";
    private static final String DISCONNECT = "minecraft:disconnect";
    private static final String PLAYER_POSITION = "minecraft:player_position";
    private static final String ACCEPT_TELEPORTATION = "minecraft:accept_teleportation";
    private static final String SYSTEM_CHAT = "minecraft:system_chat";
    private static final String START_CONFIGURATION = "minecraft:start_configuration";
    private static final String CONFIGURATION_ACKNOWLEDGED = "minecraft:configuration_acknowledged";

    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * How long to wait on a silent socket before giving up.
     *
     * <p>Well above the server's keepalive interval, so an idle-but-healthy
     * connection is never mistaken for a dead one.
     */
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String host;
    private final int port;
    private final BotAccount account;
    private final PacketIds ids;
    /** Status lines for the console and the command sender. */
    private final Consumer<String> log;
    /** Run once the bot reaches the play phase, for the local-player handoff. */
    private final Runnable onJoined;
    /** Whether target-server messages are echoed to this server's console. */
    private final boolean relayChat;

    private final Object writeLock = new Object();

    private volatile Socket socket;
    private volatile boolean closing;
    private FrameCodec codec;

    /** Which phase's id table applies to what is being read and written. */
    private PacketIds.Phase phase = PacketIds.Phase.HANDSHAKE;

    /** Set once the play phase is reached, which is what "connected" means here. */
    private volatile boolean inPlay;

    RemoteBotConnection(String host, int port, BotAccount account, PacketIds ids,
            boolean relayChat, Consumer<String> log, Runnable onJoined) {
        this.host = host;
        this.port = port;
        this.account = account;
        this.ids = ids;
        this.relayChat = relayChat;
        this.log = log;
        this.onJoined = onJoined;
    }

    boolean isInPlay() {
        return inPlay;
    }

    /**
     * Opens the connection and reads until it ends.
     *
     * <p>Returns normally when the server closed the link or {@link #close} was
     * called; throws when the connection failed in a way worth reporting and
     * retrying.
     */
    void run() throws IOException {
        Socket s = new Socket();
        this.socket = s;
        try {
            s.setSoTimeout(READ_TIMEOUT_MS);
            // Nagle would hold back the small packets this bot sends, and a
            // keepalive reply that arrives late is treated as one that never came.
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

            this.codec = new FrameCodec(s.getInputStream(), s.getOutputStream());

            sendHandshake();
            sendLoginStart();
            readLoop();
        } finally {
            inPlay = false;
            closeSocket();
        }
    }

    /** Ends the connection. Safe to call from any thread, and more than once. */
    void close() {
        closing = true;
        closeSocket();
    }

    private void closeSocket() {
        Socket s = this.socket;
        if (s == null) {
            return;
        }
        try {
            // Closing under a blocked read is the intended way out of readLoop:
            // it raises an IOException there rather than waiting for a timeout.
            s.close();
        } catch (IOException ignored) {
            // Already closed, or never opened; either way nothing is left to do.
        }
    }

    /**
     * Reads packets until the connection ends.
     *
     * <p>Every packet is fully consumed or explicitly skipped, so a body this bot
     * does not understand cannot leave the stream misaligned.
     */
    private void readLoop() throws IOException {
        while (!closing) {
            byte[] packet;
            try {
                packet = codec.readPacket();
            } catch (IOException e) {
                if (closing) {
                    // Expected: close() shut the socket from under the read.
                    return;
                }
                throw e;
            }

            PacketBuf.Reader in = new PacketBuf.Reader(packet);
            int id = in.readVarInt();
            String name = ids.clientbound(phase, id);

            if (name == null) {
                // Unknown in this phase. The frame has already been read in full,
                // so dropping it costs nothing and keeps the stream aligned.
                continue;
            }
            handle(name, in);
        }
    }

    /** Routes one recognised packet to the phase that owns it. */
    private void handle(String name, PacketBuf.Reader in) throws IOException {
        switch (phase) {
            case LOGIN -> handleLogin(name, in);
            case CONFIGURATION -> handleConfiguration(name, in);
            case PLAY -> handlePlay(name, in);
            // The server never sends in the handshake phase.
            case HANDSHAKE -> { }
        }
    }

    // ---------------------------------------------------------------- handshake

    /**
     * Opens the conversation.
     *
     * <p>The protocol version is read from this server rather than hard-coded,
     * because it differs between releases (775 on 26.1.x, 776 on 26.2). Sending
     * the wrong one gets an "outdated client" rejection.
     */
    private void sendHandshake() throws IOException {
        int protocolVersion = protocolVersion();
        int loginIntent = loginIntentId();

        byte[] body = new PacketBuf.Writer()
                .writeVarInt(protocolVersion)
                .writeString(host)
                .writeShort(port)
                .writeVarInt(loginIntent)
                .toByteArray();

        send(INTENTION, body);
        // Both sides switch tables the moment the intention is sent.
        phase = PacketIds.Phase.LOGIN;
    }

    private static int protocolVersion() throws IOException {
        try {
            Class<?> shared = NmsReflect.clazz("net.minecraft.SharedConstants");
            Object version = NmsReflect.method(shared, "getProtocolVersion").invoke(null);
            return (Integer) version;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IOException("could not read this server's protocol version", e);
        }
    }

    /** The {@code ClientIntent.LOGIN} ordinal, read rather than assumed. */
    private static int loginIntentId() throws IOException {
        try {
            Class<?> intent = NmsReflect.clazz(
                    "net.minecraft.network.protocol.handshake.ClientIntent");
            Object login = NmsReflect.staticField(intent, "LOGIN");
            return (Integer) NmsReflect.method(intent, "id").invoke(login);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IOException("could not read the login intent id", e);
        }
    }

    // -------------------------------------------------------------------- login

    private void sendLoginStart() throws IOException {
        byte[] body = new PacketBuf.Writer()
                .writeString(account.name())
                .writeUuid(account.uuid())
                .toByteArray();
        send(HELLO, body);
    }

    private void handleLogin(String name, PacketBuf.Reader in) throws IOException {
        switch (name) {
            case LOGIN_COMPRESSION -> {
                // Applies from the next packet on, which is why the codec is told
                // before anything else is read or written.
                int threshold = in.readVarInt();
                codec.setCompressionThreshold(threshold);
            }
            case HELLO -> answerEncryptionRequest(in);
            case CUSTOM_QUERY -> answerCustomQuery(in);
            case LOGIN_FINISHED -> {
                // The profile that follows is not read: its layout gained a field
                // in 26.2, and the bot already knows who it logged in as.
                send(LOGIN_ACKNOWLEDGED, new byte[0]);
                phase = PacketIds.Phase.CONFIGURATION;
                sendClientInformation();
            }
            case LOGIN_DISCONNECT -> throw new IOException(
                    "the server refused the login: " + describeReason(in));
            default -> {
                // Recognised but not acted on.
            }
        }
    }

    /**
     * Completes the premium handshake.
     *
     * <p>Reached only when the target runs {@code online-mode=true}. An offline
     * account cannot answer this: the server will ask Mojang about the login, and
     * without a token there is nothing for Mojang to confirm.
     */
    private void answerEncryptionRequest(PacketBuf.Reader in) throws IOException {
        String serverId = in.readString();
        byte[] publicKeyBytes = in.readByteArray();
        byte[] challenge = in.readByteArray();

        if (!account.isPremium()) {
            throw new IOException("this server requires a premium account "
                    + "(online-mode=true), but no access token is configured");
        }

        PublicKey serverKey = MojangAuth.parsePublicKey(publicKeyBytes);
        SecretKey secret = MojangAuth.newSharedSecret();

        // Told to Mojang before the key is sent: the server checks with Mojang as
        // soon as it has the key, so the record has to already be there.
        MojangAuth.joinServer(account, serverId, secret, serverKey);

        byte[] body = new PacketBuf.Writer()
                .writeByteArray(MojangAuth.encryptToServer(serverKey, secret.getEncoded()))
                .writeByteArray(MojangAuth.encryptToServer(serverKey, challenge))
                .toByteArray();

        // Sent in the clear, then everything after it is encrypted. Doing this
        // under the write lock keeps another thread from writing a plaintext
        // packet in between.
        synchronized (writeLock) {
            sendLocked(KEY, body);
            codec.enableEncryption(secret);
        }
    }

    /**
     * Declines a login plugin request.
     *
     * <p>Proxies such as Velocity and modded servers ask questions here that a
     * vanilla client would not understand either. The protocol has an answer for
     * that - the same transaction id with no payload, meaning "I do not know this
     * one" - and servers that only offer it optionally accept the refusal and
     * continue. Ignoring the packet instead would stall the login, because the
     * server waits for a reply.
     */
    private void answerCustomQuery(PacketBuf.Reader in) throws IOException {
        int transactionId = in.readVarInt();
        byte[] body = new PacketBuf.Writer()
                .writeVarInt(transactionId)
                .writeBoolean(false)
                .toByteArray();
        send(CUSTOM_QUERY_ANSWER, body);
    }

    // ------------------------------------------------------------ configuration

    private void handleConfiguration(String name, PacketBuf.Reader in) throws IOException {
        switch (name) {
            case SELECT_KNOWN_PACKS -> {
                // An empty list means "I have none of your packs, send them all".
                // The server blocks here until it gets a reply, so this cannot be
                // skipped even though the pack contents are then ignored.
                send(SELECT_KNOWN_PACKS, new PacketBuf.Writer().writeVarInt(0).toByteArray());
            }
            case CODE_OF_CONDUCT -> send(ACCEPT_CODE_OF_CONDUCT, new byte[0]);
            case KEEP_ALIVE -> answerKeepAlive(in);
            case PING -> answerPing(in);
            case FINISH_CONFIGURATION -> {
                send(FINISH_CONFIGURATION, new byte[0]);
                phase = PacketIds.Phase.PLAY;
                inPlay = true;
                log.accept("joined " + host + ":" + port + " as " + account.name() + ".");
                onJoined.run();
            }
            case DISCONNECT -> throw new IOException(
                    "disconnected during configuration: " + describeReason(in));
            default -> {
                // Registry data, feature flags, tags: all skipped.
            }
        }
    }

    /**
     * Reports the bot's client settings.
     *
     * <p>Sent unprompted on entering configuration. Some servers wait for it
     * before finishing, and a client that never sends it looks stuck.
     */
    private void sendClientInformation() throws IOException {
        byte[] body = new PacketBuf.Writer()
                .writeString("en_us")
                .writeByte(2)          // view distance, deliberately small
                .writeVarInt(0)        // chat mode: enabled
                .writeBoolean(true)    // chat colours
                .writeByte(0)          // no skin layers
                .writeVarInt(1)        // main hand: right
                .writeBoolean(false)   // no text filtering
                .writeBoolean(false)   // keep the bot out of the public player list
                .writeVarInt(0)        // particle status: all
                .toByteArray();
        send(CLIENT_INFORMATION, body);
    }

    // --------------------------------------------------------------------- play

    private void handlePlay(String name, PacketBuf.Reader in) throws IOException {
        switch (name) {
            case KEEP_ALIVE -> answerKeepAlive(in);
            case PING -> answerPing(in);
            case PLAYER_POSITION -> confirmTeleport(in);
            case SYSTEM_CHAT -> relayChat(in);
            case START_CONFIGURATION -> {
                // The server is moving the connection back to configuration, which
                // is how it hands a client to another world or resource pack set.
                // It has to be acknowledged before the server will continue, and
                // the phase switches with it.
                send(CONFIGURATION_ACKNOWLEDGED, new byte[0]);
                phase = PacketIds.Phase.CONFIGURATION;
                inPlay = false;
            }
            case DISCONNECT -> throw new IOException(
                    "disconnected by the server: " + describeReason(in));
            default -> {
                // Chunks, entities, inventory: all skipped.
            }
        }
    }

    /**
     * Acknowledges a teleport.
     *
     * <p>Every position the server sends carries an id it wants echoed back.
     * Until it is, the server treats the client as not yet in place and will keep
     * resending - and some anti-cheats read a missing confirmation as a client
     * that is ignoring corrections.
     */
    private void confirmTeleport(PacketBuf.Reader in) throws IOException {
        int teleportId = in.readVarInt();
        send(ACCEPT_TELEPORTATION, new PacketBuf.Writer().writeVarInt(teleportId).toByteArray());
    }

    /**
     * Puts a server message from the target onto this server's console.
     *
     * <p>Best effort by design. The message is a chat component in Minecraft's
     * binary form, and decoding one properly needs the registries a real client
     * assembles during login - which this bot deliberately skips. What the text
     * scan recovers is the readable part, which is the part worth logging; if it
     * recovers nothing, the line is dropped rather than logged as noise.
     */
    private void relayChat(PacketBuf.Reader in) {
        if (!relayChat) {
            return;
        }
        try {
            String text = readableText(in.readBytes(in.remaining()));
            if (!text.isBlank()) {
                log.accept("[chat] " + text);
            }
        } catch (IOException ignored) {
            // A message that cannot be read is not worth ending the connection over.
        }
    }

    // ------------------------------------------------------------------ shared

    /** Keepalive ids are longs, and the same value has to come back unchanged. */
    private void answerKeepAlive(PacketBuf.Reader in) throws IOException {
        long id = in.readLong();
        send(KEEP_ALIVE, new PacketBuf.Writer().writeLong(id).toByteArray());
    }

    /** Ping ids are ints, and are answered with pong rather than the same name. */
    private void answerPing(PacketBuf.Reader in) throws IOException {
        int id = in.readVarInt();
        send(PONG, new PacketBuf.Writer().writeVarInt(id).toByteArray());
    }

    private void send(String name, byte[] body) throws IOException {
        synchronized (writeLock) {
            sendLocked(name, body);
        }
    }

    /** Caller already holds {@link #writeLock}. */
    private void sendLocked(String name, byte[] body) throws IOException {
        codec.writePacket(ids.serverbound(phase, name), body);
    }

    /**
     * Best-effort text for a disconnect reason.
     *
     * <p>The reason is a chat component, and decoding one properly needs the
     * registries a real client builds at login. Since this only ever feeds a log
     * line, the raw bytes are scanned for readable text instead, and a failure to
     * find any is not worth propagating - the disconnect itself is the news.
     */
    private static String describeReason(PacketBuf.Reader in) {
        try {
            byte[] rest = in.readBytes(in.remaining());
            String text = readableText(rest);
            return text.isBlank() ? "no reason given" : text;
        } catch (IOException e) {
            return "no reason given";
        }
    }

    /** Pulls printable ASCII runs out of an undecoded component. */
    private static String readableText(byte[] data) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : data) {
            if (b >= 0x20 && b < 0x7F) {
                run.append((char) b);
                continue;
            }
            appendRun(out, run);
        }
        appendRun(out, run);
        return out.toString().trim();
    }

    /** Keeps runs of four or more characters, which filters out field tags. */
    private static void appendRun(StringBuilder out, StringBuilder run) {
        if (run.length() >= 4) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(run);
        }
        run.setLength(0);
    }
}
