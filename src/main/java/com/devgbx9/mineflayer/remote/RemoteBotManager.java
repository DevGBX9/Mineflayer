package com.devgbx9.mineflayer.remote;

import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import com.devgbx9.mineflayer.FakePlayerManager;

/**
 * Owns the remote bot: starting it, keeping it connected, and stopping it.
 *
 * <p>One bot at a time. The connection itself lives in
 * {@link RemoteBotConnection}; this class is what decides when a new one is
 * opened, when a lost one is retried, and when the whole thing is done.
 *
 * <p>A bot that was settled on the target and then lost its link - kicked,
 * restarted, dropped - reconnects with no wait at all, because the point of the
 * feature is that it is back before anyone notices. Everything else backs off:
 * doubling from a second up to a ceiling, and never stopping while the bot is
 * meant to be running. Giving up was considered and rejected: the reasons a
 * target refuses a login are overwhelmingly temporary - the server is
 * restarting, asleep, or full - and a bot that abandoned the target ten minutes
 * ago looks exactly like one that was never started. The ceiling is what keeps
 * an unreachable target from filling the log, and repeated failures are logged
 * at a widening interval rather than on every attempt.
 *
 * <p>All networking runs on this class's own thread. Nothing here touches the
 * Bukkit API from that thread except through {@link #post}, because the server
 * is not thread-safe and a plugin that ignores that corrupts state in ways that
 * surface much later.
 */
public final class RemoteBotManager {

    private static final long FIRST_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 30_000;

    /** Failures between log lines once the backoff has reached its ceiling. */
    private static final int QUIET_LOG_INTERVAL = 20;

    /**
     * How long a session must have lasted for its loss to be reconnected to
     * immediately.
     *
     * <p>A bot that played for a while and then went is one the target removed -
     * a kick, a restart, a dropped link - and the point of this feature is that it
     * is back before anyone notices. But a target that kicks on sight also
     * reaches the play phase, for a second, and treating that as "was playing"
     * would reconnect with no wait, forever, as fast as the network allows. Ten
     * seconds separates the two without a false negative that matters: a real
     * session that ends inside ten seconds waits one second instead of none.
     */
    private static final long GENUINE_SESSION_MS = 10_000;

    private final Plugin plugin;
    /** Used for the handoff: the local fake player steps aside while the bot is away. */
    private final FakePlayerManager localPlayer;

    /** Guards every field below, all of which are touched from two threads. */
    private final Object lock = new Object();

    private Thread worker;
    private RemoteBotConnection connection;
    /** False once {@link #stop} runs, which is what ends the retry loop. */
    private boolean running;
    private String host;
    private int port;
    /** Whether the local fake player was online before the bot took over. */
    private boolean restoreLocalPlayer;

    public RemoteBotManager(Plugin plugin, FakePlayerManager localPlayer) {
        this.plugin = plugin;
        this.localPlayer = localPlayer;
    }

    /** Whether a bot is running, connected or between retries. */
    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** Whether the bot is currently in the target server's play phase. */
    public boolean isConnected() {
        synchronized (lock) {
            RemoteBotConnection c = connection;
            return c != null && c.isInPlay();
        }
    }

    /** The target, as {@code host:port}, or {@code null} if no bot is running. */
    public String target() {
        synchronized (lock) {
            return running ? host + ":" + port : null;
        }
    }

    /**
     * Starts a bot against {@code host:port}.
     *
     * @return {@code null} once the attempt is under way, otherwise a message
     *         describing why it could not be started
     */
    public String start(String host, int port) {
        synchronized (lock) {
            if (running) {
                return "a bot is already connected to " + this.host + ":" + this.port;
            }

            PacketIds ids;
            try {
                // Read once per start rather than cached for the plugin's lifetime:
                // this is the check that fails loudly on an unsupported build, and
                // it should fail on every start rather than only the first.
                ids = PacketIds.load();
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not read the packet id table", e);
                return "this server build's packet tables could not be read: " + describe(e);
            }

            BotAccount account = readAccount();
            if (account == null) {
                return "the account in config.yml is incomplete; see the console";
            }

            this.host = host;
            this.port = port;
            this.running = true;
            this.restoreLocalPlayer = false;

            boolean relayChat = plugin.getConfig().getBoolean("remote.relay-chat", true);

            Thread t = new Thread(() -> connectLoop(host, port, account, ids, relayChat),
                    "Mineflayer-remote-bot");
            // Daemon so a stuck socket cannot keep the JVM alive past shutdown;
            // onDisable stops it cleanly in the normal case.
            t.setDaemon(true);
            this.worker = t;
            t.start();
            return null;
        }
    }

    /**
     * Stops the bot and puts the local fake player back if it was displaced.
     *
     * @return {@code null} on success, otherwise a message describing what failed
     */
    public String stop() {
        return stop(true);
    }

    /**
     * Stops the bot.
     *
     * @param restoreLocal whether to bring the local fake player back if the bot
     *        displaced it. False on shutdown, where the local player is going
     *        offline anyway and reviving it first would only be undone.
     * @return {@code null} on success, otherwise a message describing what failed
     */
    private String stop(boolean restoreLocal) {
        Thread t;
        RemoteBotConnection c;
        boolean restore;
        synchronized (lock) {
            if (!running) {
                return "no bot is running";
            }
            // Lowered first: the retry loop checks this, so clearing it before
            // closing the socket is what stops a reconnect from racing the stop.
            running = false;
            t = worker;
            c = connection;
            restore = restoreLocalPlayer && restoreLocal;
            worker = null;
            connection = null;
            restoreLocalPlayer = false;
        }

        if (c != null) {
            c.close();
        }
        if (t != null) {
            // Brief: the close above is what unblocks the read, and this only
            // waits for the loop to notice. Not joined indefinitely, because this
            // runs on the main thread and must not hang the server.
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (restore) {
            String failure = localPlayer.start();
            if (failure != null) {
                return "the bot stopped, but the local fake player did not come back: " + failure;
            }
        }
        return null;
    }

    /** Called from {@code onDisable}; stops the bot without reporting anything. */
    public void shutdown() {
        synchronized (lock) {
            if (!running) {
                return;
            }
        }
        stop(false);
    }

    /**
     * Connects, and reconnects while the bot is meant to be running.
     *
     * <p>Runs on the worker thread for as long as the bot lives.
     */
    private void connectLoop(String host, int port, BotAccount account, PacketIds ids,
            boolean relayChat) {
        long retryDelay = FIRST_RETRY_MS;
        int failures = 0;
        // The wait before the next attempt, decided by how the last one went.
        // Zero here because the first attempt is the command the user just ran,
        // and that should not sit waiting for a backoff nothing has earned yet.
        long nextDelay = 0;

        while (isRunning()) {
            RemoteBotConnection c = new RemoteBotConnection(
                    host, port, account, ids, relayChat, this::post, this::onJoined);
            synchronized (lock) {
                if (!running) {
                    return;
                }
                connection = c;
            }

            try {
                c.run();
                // Returned without throwing: the server closed the link, or stop()
                // did. Either way this is not a failure, so the backoff resets.
                if (!isRunning()) {
                    return;
                }
                post("the connection to " + host + ":" + port + " closed; reconnecting.");
                retryDelay = FIRST_RETRY_MS;
                failures = 0;
                // Removed from a server it had settled on: back at once, which is
                // the whole point. A link that never got in, or that lasted only
                // moments, waits its turn.
                nextDelay = heldASession(c) ? 0 : retryDelay;
            } catch (Exception e) {
                if (!isRunning()) {
                    return;
                }
                failures++;
                if (heldASession(c)) {
                    // It was connected and working, so this is a fresh problem
                    // rather than a target that keeps refusing.
                    failures = 1;
                    retryDelay = FIRST_RETRY_MS;
                    nextDelay = 0;
                } else {
                    nextDelay = retryDelay;
                }
                // Every attempt is reported until the backoff reaches its
                // ceiling, then one in twenty: past that point the message stops
                // being news and the failure is usually the same one.
                if (retryDelay < MAX_RETRY_MS || failures % QUIET_LOG_INTERVAL == 0) {
                    post("connection to " + host + ":" + port + " failed: " + describe(e)
                            + (failures > 1 ? " (attempt " + failures + ")" : ""));
                }

                if (retryDelay >= MAX_RETRY_MS) {
                    // The target has been refusing for a while. Put the local
                    // player back so the sending server is not left with no
                    // player at all, and keep retrying: if the target returns,
                    // onJoined takes the local player down again.
                    bringLocalPlayerBack();
                }
            }

            if (!sleepBetweenAttempts(nextDelay)) {
                return;
            }
            // Only a waited-out attempt widens the window. An immediate retry
            // after a kick must not, or a target that kicks on sight would walk
            // the delay up without a single real wait in between.
            if (nextDelay > 0) {
                retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
            }
            nextDelay = retryDelay;
        }
    }

    /**
     * Whether the attempt amounted to a real stay on the target, as opposed to
     * never getting in or being turned round on arrival.
     *
     * <p>This is the one distinction the retry timing turns on: a lost session
     * is reconnected to with no wait, anything else backs off.
     */
    private static boolean heldASession(RemoteBotConnection c) {
        return c.playedForMillis() >= GENUINE_SESSION_MS;
    }

    /**
     * Waits before the next attempt.
     *
     * @return {@code false} if the bot was stopped while waiting
     */
    private boolean sleepBetweenAttempts(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return isRunning();
    }

    /**
     * Puts the local fake player back while the bot keeps retrying a target that
     * is not answering.
     *
     * <p>The handoff assumes the bot arrives somewhere. When it cannot, leaving
     * neither player online is the one outcome worse than either, so the local
     * player comes back and the bot carries on trying in the background. Clearing
     * the flag is what makes this run once rather than every attempt, and a later
     * successful join sets it again through {@link #onJoined}.
     *
     * <p>Called from the worker thread, so the start itself is bounced to the
     * main thread.
     */
    private void bringLocalPlayerBack() {
        synchronized (lock) {
            if (!restoreLocalPlayer) {
                return;
            }
            restoreLocalPlayer = false;
        }
        post("the target is still refusing; bringing the local fake player back "
                + "while the bot keeps trying.");
        Bukkit.getScheduler().runTask(plugin, localPlayer::start);
    }

    /**
     * Reads the bot's account out of {@code config.yml}.
     *
     * @return the account, or {@code null} if the configuration is unusable
     */
    private BotAccount readAccount() {
        FileConfiguration config = plugin.getConfig();
        String name = config.getString("remote.username", "").trim();
        if (name.isEmpty()) {
            // Falls back rather than failing: the bot and the local fake player
            // are meant to be one identity, so the local name is the right
            // default, and a config.yml written before this key existed should
            // not stop the bot from starting.
            name = localPlayer.name();
            plugin.getLogger().info(
                    "remote.username is not set in config.yml; using '" + name + "'");
        }

        String token = config.getString("remote.access-token", "").trim();
        if (token.isEmpty()) {
            // Offline is the default because it needs nothing else, and a premium
            // target reports the missing token clearly when it asks for one.
            return BotAccount.offline(name);
        }

        String rawUuid = config.getString("remote.uuid", "").trim();
        if (rawUuid.isEmpty()) {
            plugin.getLogger().severe(
                    "remote.access-token is set but remote.uuid is not; both are needed "
                            + "to authenticate with Mojang");
            return null;
        }

        try {
            return BotAccount.premium(name, UUID.fromString(rawUuid), token);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("remote.uuid is not a valid UUID: " + rawUuid);
            return null;
        }
    }

    /**
     * Called by the connection once it reaches the play phase.
     *
     * <p>Takes the local fake player offline, and remembers that it was online so
     * {@link #stop} can bring it back. Deliberately not done at start: if the
     * target refuses the login, taking the local player down first would leave no
     * player anywhere.
     */
    private void onJoined() {
        if (!localPlayer.isOnline()) {
            return;
        }
        synchronized (lock) {
            restoreLocalPlayer = true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            String failure = localPlayer.stop();
            if (failure != null) {
                plugin.getLogger().warning(
                        "The remote bot joined but the local fake player did not leave: "
                                + failure);
            }
        });
    }

    /**
     * Logs a line from the worker thread.
     *
     * <p>The logger is thread-safe, so this does not hop threads.
     */
    private void post(String message) {
        plugin.getLogger().info("[remote] " + message);
    }

    /** The root cause, named, without a stack trace the caller cannot use. */
    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : message;
    }
}
