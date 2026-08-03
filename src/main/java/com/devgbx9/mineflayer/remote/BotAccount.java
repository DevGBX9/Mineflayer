package com.devgbx9.mineflayer.remote;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.devgbx9.mineflayer.RandomIdentity;

/**
 * Who the bot logs in as, and how it proves it.
 *
 * <p>Two modes, because the target server decides which one is possible rather
 * than we do:
 * <ul>
 *   <li><b>Offline.</b> The name and uuid are taken at face value, so the bot
 *       draws a new pair per connection attempt - see {@link #randomOffline} -
 *       and every join is a stranger to the target.</li>
 *   <li><b>Premium.</b> A real Microsoft account, identified by its access token
 *       and profile uuid. Required by any server with {@code online-mode=true}:
 *       that server asks Mojang whether the login is genuine, and only the
 *       account holder's own token produces a yes. This is the one identity that
 *       cannot rotate, because the token authenticates that name and no other.</li>
 * </ul>
 *
 * <p>There is no third mode. A premium server cannot be joined without an
 * account that owns the name being claimed - that check happens on Mojang's
 * side, not the target server's, so nothing in this plugin can stand in for it.
 */
record BotAccount(String name, UUID uuid, String accessToken) {

    /** Prefix Minecraft itself uses when deriving an offline uuid. */
    private static final String OFFLINE_PREFIX = "OfflinePlayer:";

    /**
     * An offline account with the uuid a server derives from the name.
     *
     * <p>Matches what a server with {@code online-mode=false} would assign, so a
     * bot logging in this way reclaims the player entry - and the inventory and
     * position - that the name already owns there.
     *
     * <p>Held for a configured name that has not been overridden. The connect
     * loop draws {@link #randomOffline} per attempt instead, so this is what the
     * name in {@code config.yml} means rather than what is presented.
     */
    static BotAccount offline(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(
                (OFFLINE_PREFIX + name).getBytes(StandardCharsets.UTF_8));
        return new BotAccount(name, uuid, null);
    }

    /**
     * A fresh offline account with a name and uuid seen nowhere before.
     *
     * <p>The uuid is random rather than derived from the name, so the target has
     * nothing tying this login to the last one. Drawn once per connection
     * attempt, which is what stops a name the target refused from being presented
     * a second time.
     *
     * <p>What it costs, since it is not free: every join is a stranger. No
     * inventory, no position, no permission and no whitelist entry carries over,
     * and a target that lists its players sees the count climb with names that
     * never repeat. It only applies where the target runs
     * {@code online-mode=false}; a premium login is the account it is, and
     * nothing here can change that.
     */
    static BotAccount randomOffline() {
        return new BotAccount(RandomIdentity.name(), UUID.randomUUID(), null);
    }

    /** A premium account, authenticated against Mojang with {@code accessToken}. */
    static BotAccount premium(String name, UUID uuid, String accessToken) {
        return new BotAccount(name, uuid, accessToken);
    }

    /** Whether this account can answer an encryption request. */
    boolean isPremium() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * The {@code name:uuid} form Mojang's session server expects.
     *
     * <p>Undashed, unlike every other place a uuid appears in this plugin.
     */
    String undashedUuid() {
        return uuid.toString().replace("-", "");
    }

    /** Deliberately omits the token so it cannot reach a log or a chat message. */
    @Override
    public String toString() {
        return "BotAccount[" + name + ", " + uuid + ", "
                + (isPremium() ? "premium" : "offline") + "]";
    }
}
