package com.devgbx9.mineflayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Creates and removes a fake player that the server treats as genuinely online.
 *
 * <p>The player is registered through {@code PlayerList.placeNewPlayer}, the same
 * entry point a real login uses, so it appears in {@code Bukkit.getOnlinePlayers()},
 * raises the player count by one and keeps the server out of its idle state. It is
 * placed in spectator mode, which leaves it with no collidable body and nothing to
 * interact with, and it is tied to no real player.
 *
 * <p>Three details keep it alive once registered:
 * <ul>
 *   <li>It needs a live {@code Connection}. {@code PlayerList.tick} periodically
 *       calls {@code broadcastAll}, which sends to every player, so a null or
 *       closed connection would throw inside the server tick loop. The connection
 *       here is backed by a netty {@code EmbeddedChannel}, which reports itself
 *       open but goes nowhere.</li>
 *   <li>That channel queues everything written to it, so it is drained on a timer
 *       instead of growing without bound.</li>
 *   <li>The server disconnects players that ignore keepalive pings. This one never
 *       answers, so the keepalive state is reset on the same timer.</li>
 * </ul>
 *
 * <p>Every server type is reached by reflection rather than a compile-time NMS
 * dependency, which is what keeps a single jar loadable on Bukkit, Spigot, Paper
 * and forks across both the 26.1.x and 26.2.x series. The tradeoff is real:
 * mismatches surface at runtime, not at compile time, so failures are reported
 * back to the caller instead of being swallowed.
 */
public final class FakePlayerManager {

    private static final String NAME = "Mineflayer";
    /** Fixed so the same fake player identity is reused across restarts. */
    private static final UUID ID = UUID.nameUUIDFromBytes("Mineflayer:monitor".getBytes());

    private final Plugin plugin;

    private Object serverPlayer;
    private Object connection;
    private Object channel;
    private BukkitTask upkeep;

    public FakePlayerManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isOnline() {
        return serverPlayer != null;
    }

    public String name() {
        return NAME;
    }

    /**
     * Registers the fake player.
     *
     * @return {@code null} on success, otherwise a message describing what failed
     */
    public String start() {
        if (isOnline()) {
            return "already running";
        }
        try {
            spawn();
            return null;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Could not register the fake player", t);
            // Leave nothing half-registered behind.
            try {
                stop();
            } catch (Throwable ignored) {
                // Reported through the original failure instead.
            }
            return describe(t);
        }
    }

    private void spawn() throws ReflectiveOperationException {
        Object nmsServer = nmsServer();
        Class<?> serverClass = nmsServer.getClass();

        Object level = NmsReflect.method(serverClass, "overworld").invoke(nmsServer);

        Class<?> profileClass = NmsReflect.clazz("com.mojang.authlib.GameProfile");
        Object profile = NmsReflect.construct(profileClass, ID, NAME);

        Class<?> clientInfoClass = NmsReflect.clazz("net.minecraft.server.level.ClientInformation");
        Object clientInfo = NmsReflect.method(clientInfoClass, "createDefault").invoke(null);

        Class<?> serverPlayerClass = NmsReflect.clazz("net.minecraft.server.level.ServerPlayer");
        Object player = NmsReflect.construct(
                serverPlayerClass, nmsServer, level, profile, clientInfo);

        Object conn = fakeConnection();

        Class<?> cookieClass = NmsReflect.clazz("net.minecraft.server.network.CommonListenerCookie");
        Object cookie = NmsReflect.method(cookieClass, "createInitial", profile, Boolean.FALSE)
                .invoke(null, profile, Boolean.FALSE);

        Object playerList = NmsReflect.getterReturning(
                        serverClass, NmsReflect.clazz("net.minecraft.server.players.PlayerList"))
                .invoke(nmsServer);

        // Publish state before the join so a listener firing during placeNewPlayer
        // sees a consistent manager.
        this.serverPlayer = player;
        this.connection = conn;

        NmsReflect.method(playerList.getClass(), "placeNewPlayer", conn, player, cookie)
                .invoke(playerList, conn, player, cookie);

        if (Bukkit.getPlayer(ID) == null) {
            this.serverPlayer = null;
            this.connection = null;
            throw new ReflectiveOperationException(
                    "the server accepted the join but the player is not listed as online");
        }

        // Spectator leaves it with no solid body and nothing to interact with.
        // Done through the Bukkit API rather than NMS: it is stable across forks.
        Player bukkitPlayer = Bukkit.getPlayer(ID);
        if (bukkitPlayer != null) {
            bukkitPlayer.setGameMode(GameMode.SPECTATOR);
        }

        startUpkeep();
    }

    /** Builds a {@code Connection} whose channel is open but discards traffic. */
    private Object fakeConnection() throws ReflectiveOperationException {
        Class<?> flowClass = NmsReflect.clazz("net.minecraft.network.protocol.PacketFlow");
        Object serverbound = NmsReflect.staticField(flowClass, "SERVERBOUND");

        Class<?> connectionClass = NmsReflect.clazz("net.minecraft.network.Connection");
        Object conn = NmsReflect.construct(connectionClass, serverbound);

        Class<?> channelClass = NmsReflect.clazz("io.netty.channel.embedded.EmbeddedChannel");
        this.channel = NmsReflect.construct(channelClass);

        Object pipeline = NmsReflect.method(channelClass, "pipeline").invoke(this.channel);

        // A fresh EmbeddedChannel has an empty pipeline, but placeNewPlayer calls
        // setupInboundProtocol, which swaps the handlers registered under the
        // 'inbound_config' and 'outbound_config' names. Missing handlers make that
        // swap throw, so the pipeline is populated using the server's own helper
        // for connections that never touch a socket.
        NmsReflect.method(connectionClass, "configureInMemoryPipeline", pipeline, serverbound)
                .invoke(null, pipeline, serverbound);
        NmsReflect.method(connectionClass, "configurePacketHandler", pipeline)
                .invoke(conn, pipeline);

        // isConnected() reports true for an open channel, which is what keeps
        // broadcasts from the server tick loop from failing on this player.
        Field channelField = NmsReflect.fieldOfType(
                connectionClass, NmsReflect.clazz("io.netty.channel.Channel"));
        channelField.set(conn, this.channel);

        Field addressField = NmsReflect.fieldOfType(connectionClass, java.net.SocketAddress.class);
        addressField.set(conn, new java.net.InetSocketAddress("127.0.0.1", 0));

        return conn;
    }

    /** Drains queued packets and keeps the keepalive watchdog from kicking. */
    private void startUpkeep() {
        upkeep = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isOnline()) {
                return;
            }
            releaseOutbound();
            resetKeepAlive();
        }, 20L, 20L);
    }

    private void releaseOutbound() {
        if (channel == null) {
            return;
        }
        try {
            NmsReflect.method(channel.getClass(), "releaseOutbound").invoke(channel);
        } catch (ReflectiveOperationException e) {
            // Not fatal on its own; the queue simply keeps growing.
            plugin.getLogger().log(Level.FINE, "Could not drain the fake channel", e);
        }
    }

    /**
     * Clears the pending keepalive so the timeout check never trips. A real client
     * would answer the ping; this one cannot, and an unanswered ping is a kick.
     */
    private void resetKeepAlive() {
        try {
            Object listener = NmsReflect.field(serverPlayer.getClass(), "connection")
                    .get(serverPlayer);
            if (listener == null) {
                return;
            }
            Class<?> common = listener.getClass();
            NmsReflect.setQuietly(listener,
                    NmsReflect.field(common, "keepAlivePending"), Boolean.FALSE);
            NmsReflect.setQuietly(listener,
                    NmsReflect.field(common, "keepAliveTime"), System.currentTimeMillis());
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.FINE, "Could not refresh the keepalive state", e);
        }
    }

    /**
     * Removes the fake player.
     *
     * @return {@code null} on success, otherwise a message describing what failed
     */
    public String stop() {
        if (upkeep != null) {
            upkeep.cancel();
            upkeep = null;
        }

        Object player = this.serverPlayer;
        // Cleared up front so a failure below cannot leave a stale reference that
        // blocks a later start.
        this.serverPlayer = null;
        this.connection = null;

        String failure = null;
        if (player != null) {
            try {
                Object nmsServer = nmsServer();
                Object playerList = NmsReflect.getterReturning(nmsServer.getClass(),
                                NmsReflect.clazz("net.minecraft.server.players.PlayerList"))
                        .invoke(nmsServer);
                NmsReflect.method(playerList.getClass(), "remove", player)
                        .invoke(playerList, player);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not unregister the fake player", t);
                failure = describe(t);
                // The player list rejected the removal, so strip the entry directly
                // rather than leaving a ghost in the online list.
                forceRemove(player);
            }
        }

        if (channel != null) {
            try {
                NmsReflect.method(channel.getClass(), "close").invoke(channel);
            } catch (ReflectiveOperationException ignored) {
                // Nothing depends on the channel after this point.
            }
            channel = null;
        }

        return failure;
    }

    /** Last resort: drop the player straight out of the player list collections. */
    @SuppressWarnings("unchecked")
    private void forceRemove(Object player) {
        try {
            Object nmsServer = nmsServer();
            Object playerList = NmsReflect.getterReturning(nmsServer.getClass(),
                            NmsReflect.clazz("net.minecraft.server.players.PlayerList"))
                    .invoke(nmsServer);

            Object players = NmsReflect.field(playerList.getClass(), "players").get(playerList);
            if (players instanceof Collection<?> collection) {
                ((Collection<Object>) collection).remove(player);
            }

            Object byUuid = NmsReflect.field(playerList.getClass(), "playersByUUID").get(playerList);
            if (byUuid instanceof Map<?, ?> map) {
                ((Map<UUID, Object>) map).remove(ID);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not force-remove the fake player", t);
        }
    }

    /** Bridges from the Bukkit server to the underlying {@code MinecraftServer}. */
    private Object nmsServer() throws ReflectiveOperationException {
        Object bukkitServer = Bukkit.getServer();
        Class<?> minecraftServer = NmsReflect.clazz("net.minecraft.server.MinecraftServer");
        // Resolved by return type: CraftServer sits in a versioned package on
        // Spigot and an unversioned one on modern Paper, and the accessor has been
        // named both getServer() and getHandle() over time.
        Method accessor = NmsReflect.getterReturning(bukkitServer.getClass(), minecraftServer);
        return accessor.invoke(bukkitServer);
    }

    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
