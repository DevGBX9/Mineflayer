package com.devgbx9.mineflayer.remote;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Who the bot logs in as, and how it proves it.
 *
 * <p>Two modes, because the target server decides which one is possible rather
 * than we do:
 * <ul>
 *   <li><b>Offline.</b> The name is taken at face value and the uuid is derived
 *       from it the same way a server with {@code online-mode=false} derives it,
 *       so the bot keeps one identity across reconnects.</li>
 *   <li><b>Premium.</b> A real Microsoft account, identified by its access token
 *       and profile uuid. Required by any server with {@code online-mode=true}:
 *       that server asks Mojang whether the login is genuine, and only the
 *       account holder's own token produces a yes.</li>
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
     * An offline account.
     *
     * <p>The uuid matches what a server with {@code online-mode=false} would
     * assign, which is what lets the bot reclaim the same player entry - and its
     * inventory and position - when it reconnects.
     */
    static BotAccount offline(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(
                (OFFLINE_PREFIX + name).getBytes(StandardCharsets.UTF_8));
        return new BotAccount(name, uuid, null);
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
