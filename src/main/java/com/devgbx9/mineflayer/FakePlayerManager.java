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
 * <p>Staying online takes three layers, because the server has more than one way
 * to remove a player and no single layer covers them all:
 * <ol>
 *   <li><b>Prevention.</b> Every server-side watchdog that ends in a disconnect is
 *       reset twice a second in {@link #harden}: the keepalive timeout, the
 *       client-load timeout, the idle timeout and the flying checks. A watchdog
 *       that never reaches its limit never kicks.</li>
 *   <li><b>Blocking.</b> {@link FakePlayerGuard} cancels {@code PlayerKickEvent},
 *       which is what {@code /kick}, other plugins and anti-cheat all go
 *       through.</li>
 *   <li><b>Recovery.</b> Anything that still manages to remove the player - an
 *       NMS-level disconnect that skips the Bukkit event entirely - is undone by
 *       the same timer, which notices the player is gone and registers it again.
 *       This is the layer that makes the guarantee hold without having to
 *       enumerate every removal path in advance.</li>
 * </ol>
 *
 * <p>All three stand down the moment {@link #stop} runs, which is the only way
 * the player is meant to leave.
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

    /** Reported as the connection latency, plausibly low and steady. */
    private static final int FAKE_LATENCY_MS = 8;

    /**
     * How many failed re-registrations are retried at full speed.
     *
     * <p>Not a give-up point. The upkeep timer runs twice a second, so five
     * failures can pass in two and a half seconds - a world still loading, or
     * another plugin throwing inside a join listener - and abandoning the player
     * on that basis would undo the guarantee this class exists to make. Past this
     * count the retries slow down instead, which is all a limit was ever
     * protecting: a server that refuses the join every time would otherwise fill
     * the log twice a second forever.
     */
    private static final int FAST_REVIVE_ATTEMPTS = 5;

    /**
     * The longest wait between slow retries, counted in upkeep passes.
     *
     * <p>120 passes at 10 ticks each is a minute, so a cause that clears on its
     * own is picked up within a minute however long it lasted.
     */
    private static final int MAX_REVIVE_WAIT_PASSES = 120;

    /** Failures between log lines once the retries have slowed down. */
    private static final int QUIET_REVIVE_LOG_INTERVAL = 20;

    private final Plugin plugin;

    private Object serverPlayer;
    private Object connection;
    private Object channel;
    private BukkitTask upkeep;
    /** False only while {@link #stop} runs, so the guard lets that removal through. */
    private volatile boolean protect;

    // Watchdog members, resolved once at join. Null means "not present in this
    // server build"; see harden().
    private Object listener;
    private Field keepAlivePending;
    private Field keepAliveTime;
    private Field closed;
    private Field closedListenerTime;
    private Field latency;
    private Field clientLoadedTimeoutTimer;
    private Field aboveGroundTickCount;
    private Field aboveGroundVehicleTickCount;
    private Field clientIsFloating;
    private Field clientVehicleIsFloating;
    private Method resetLastActionTime;
    /** Consecutive failed revives; reset by a successful one. */
    private int reviveFailures;
    /** Upkeep passes still to skip before the next revive attempt. */
    private int reviveWaitPasses;

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
     * Whether kicks aimed at {@code uuid} should be refused.
     *
     * <p>Read by {@link FakePlayerGuard} on the main thread; {@code volatile}
     * because {@link #stop} may clear it from a command thread.
     */
    public boolean isProtected(UUID uuid) {
        return protect && ID.equals(uuid);
    }

    /**
     * Called by {@link FakePlayerGuard} when the fake player left anyway.
     *
     * <p>Clears the stale references so the next upkeep pass sees a player that
     * needs re-registering, and resets the failure count: this is a fresh removal,
     * not a continuation of an earlier failed attempt.
     */
    void notifyRemoved() {
        if (!protect) {
            return;
        }
        this.serverPlayer = null;
        this.listener = null;
        this.reviveFailures = 0;
        // Clears the backoff as well as the count: this removal is new, so the
        // next pass should act on it immediately rather than serve out a wait
        // that an earlier, unrelated failure imposed.
        this.reviveWaitPasses = 0;
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

        cacheGuards();
        harden();
        protect = true;
        startUpkeep();
    }

    /**
     * Resolves the watchdog members once, at join time.
     *
     * <p>{@link #harden} runs twice a second, and every lookup here walks a class
     * hierarchy, so resolving them per run would be paying that cost forever.
     * Each one is optional: a member that a future drop renames costs one
     * protection instead of the whole plugin.
     */
    private void cacheGuards() throws ReflectiveOperationException {
        Object listener = NmsReflect.field(serverPlayer.getClass(), "connection").get(serverPlayer);
        if (listener == null) {
            throw new ReflectiveOperationException("the joined player has no packet listener");
        }
        this.listener = listener;

        Class<?> c = listener.getClass();
        // Declared on ServerCommonPacketListenerImpl; the keepalive timeout.
        this.keepAlivePending = NmsReflect.fieldOrNull(c, "keepAlivePending");
        this.keepAliveTime = NmsReflect.fieldOrNull(c, "keepAliveTime");
        // The 'unexpected query' timeout, armed once the listener is marked closed.
        this.closed = NmsReflect.fieldOrNull(c, "closed");
        this.closedListenerTime = NmsReflect.fieldOrNull(c, "closedListenerTime");
        // A latency of exactly zero is the clearest tell that nothing is really
        // connected, and it also decides whether a keepalive is sent at all.
        this.latency = NmsReflect.fieldOrNull(c, "latency");
        // Declared on ServerGamePacketListenerImpl: kicks a client that never
        // reports itself loaded, which a fake player never does.
        this.clientLoadedTimeoutTimer = NmsReflect.fieldOrNull(c, "clientLoadedTimeoutTimer");
        // The flying checks. Spectator already skips them, but a plugin can change
        // the game mode, so the counters are held down regardless.
        this.aboveGroundTickCount = NmsReflect.fieldOrNull(c, "aboveGroundTickCount");
        this.aboveGroundVehicleTickCount = NmsReflect.fieldOrNull(c, "aboveGroundVehicleTickCount");
        this.clientIsFloating = NmsReflect.fieldOrNull(c, "clientIsFloating");
        this.clientVehicleIsFloating = NmsReflect.fieldOrNull(c, "clientVehicleIsFloating");
        // The idle timeout compares against this, and player-idle-timeout is a
        // plain server.properties setting, so it cannot be assumed to be off.
        this.resetLastActionTime =
                NmsReflect.methodOrNull(serverPlayer.getClass(), "resetLastActionTime");
    }

    /**
     * Resets every server-side watchdog that ends in a disconnect.
     *
     * <p>These are the kicks no event can intercept, because the server issues
     * them against the connection directly. A counter that is put back to its
     * starting value twice a second never reaches a limit measured in seconds.
     */
    private void harden() {
        if (listener == null) {
            return;
        }
        long now = System.currentTimeMillis();

        NmsReflect.setQuietly(listener, keepAlivePending, Boolean.FALSE);
        NmsReflect.setQuietly(listener, keepAliveTime, now);

        NmsReflect.setQuietly(listener, closed, Boolean.FALSE);
        NmsReflect.setQuietly(listener, closedListenerTime, 0L);

        // Steady and plausible rather than random: a ping that jitters every half
        // second is itself unusual.
        NmsReflect.setQuietly(listener, latency, FAKE_LATENCY_MS);

        NmsReflect.setQuietly(listener, clientLoadedTimeoutTimer, 0);

        NmsReflect.setQuietly(listener, aboveGroundTickCount, 0);
        NmsReflect.setQuietly(listener, aboveGroundVehicleTickCount, 0);
        NmsReflect.setQuietly(listener, clientIsFloating, Boolean.FALSE);
        NmsReflect.setQuietly(listener, clientVehicleIsFloating, Boolean.FALSE);

        NmsReflect.invokeQuietly(resetLastActionTime, serverPlayer);
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

    /**
     * Drains queued packets, holds the watchdogs down and puts the player back if
     * something removed it.
     *
     * <p>Runs every 10 ticks. The watchdogs it resets are measured in seconds, so
     * twice a second is far inside every limit, and the work is a handful of
     * pre-resolved field writes.
     */
    private void startUpkeep() {
        // A revive re-enters spawn() from inside this very task, so without this
        // the old timer would keep running alongside the new one.
        if (upkeep != null) {
            upkeep.cancel();
        }
        upkeep = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!protect) {
                return;
            }
            // Keyed off protect rather than isOnline(): a revive that fails leaves
            // no player behind, and this task is what retries it.
            if (isOnline()) {
                releaseOutbound();
                harden();
            }
            reviveIfRemoved();
        }, 10L, 10L);
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
     * Registers the player again if it left the online list.
     *
     * <p>The watchdog resets above cover the timeouts and {@link FakePlayerGuard}
     * covers everything that fires {@code PlayerKickEvent}, but a plugin can also
     * reach past both and close the connection through NMS, where no event exists
     * to cancel. Rather than trying to name every such path, this notices the
     * outcome - the player is no longer online - and joins it back.
     *
     * <p>It never stops trying. A failing revive only slows down, because the
     * reasons a join fails are usually temporary - a world mid-load, a listener
     * throwing during shutdown - and a fake player that gave up an hour ago is
     * indistinguishable from one that was never started.
     *
     * <p>Only while {@link #protect} holds, so {@code stop} is not fought.
     */
    private void reviveIfRemoved() {
        if (!protect || Bukkit.getPlayer(ID) != null) {
            return;
        }
        if (reviveWaitPasses > 0) {
            reviveWaitPasses--;
            return;
        }

        if (reviveFailures == 0) {
            plugin.getLogger().info("The fake player was removed; registering it again.");
        }
        // Drop the dead connection first: spawn() builds a fresh one, and the old
        // channel is what a kick would have closed.
        discardConnection();
        serverPlayer = null;
        listener = null;

        try {
            spawn();
            reviveFailures = 0;
            reviveWaitPasses = 0;
        } catch (Throwable t) {
            reviveFailures++;
            serverPlayer = null;
            connection = null;
            listener = null;
            scheduleNextRevive(t);
        }
    }

    /**
     * Backs the retries off after a failure, and logs at the same cadence.
     *
     * <p>The wait doubles per failure once the fast attempts are used up, so a
     * server that refuses the join settles at one attempt a minute rather than
     * two a second. The log follows the retries instead of repeating a stack
     * trace that has not changed.
     */
    private void scheduleNextRevive(Throwable cause) {
        if (reviveFailures < FAST_REVIVE_ATTEMPTS) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not re-register the fake player; retrying", cause);
            return;
        }

        int slowAttempt = reviveFailures - FAST_REVIVE_ATTEMPTS;
        // Capped before the shift as well as after: past 31 doublings the shift
        // itself would wrap and hand back a short wait.
        reviveWaitPasses = slowAttempt >= 30
                ? MAX_REVIVE_WAIT_PASSES
                : Math.min(2 << slowAttempt, MAX_REVIVE_WAIT_PASSES);

        if (reviveFailures == FAST_REVIVE_ATTEMPTS
                || reviveFailures % QUIET_REVIVE_LOG_INTERVAL == 0) {
            plugin.getLogger().log(Level.WARNING,
                    "The fake player has failed to re-register " + reviveFailures
                            + " times; still retrying, now every "
                            + (reviveWaitPasses / 2) + "s", cause);
        }
    }

    /**
     * Removes the fake player.
     *
     * @return {@code null} on success, otherwise a message describing what failed
     */
    public String stop() {
        // Lowered first: everything below is a removal, and the guard and the
        // revive watchdog both exist to undo removals.
        protect = false;

        if (upkeep != null) {
            upkeep.cancel();
            upkeep = null;
        }

        Object player = this.serverPlayer;
        // Cleared up front so a failure below cannot leave a stale reference that
        // blocks a later start.
        this.serverPlayer = null;
        this.connection = null;
        this.listener = null;
        this.reviveFailures = 0;
        this.reviveWaitPasses = 0;

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

        discardConnection();
        return failure;
    }

    /** Closes the fake channel and forgets it. */
    private void discardConnection() {
        if (channel == null) {
            return;
        }
        try {
            NmsReflect.method(channel.getClass(), "close").invoke(channel);
        } catch (ReflectiveOperationException ignored) {
            // Nothing depends on the channel after this point.
        }
        channel = null;
        connection = null;
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
