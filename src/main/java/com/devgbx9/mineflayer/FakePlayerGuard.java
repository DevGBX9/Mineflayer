package com.devgbx9.mineflayer;

import java.util.Locale;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Keeps the fake player out of reach of everything except its own command.
 *
 * <p>Two jobs. The first is refusing removals: {@code PlayerKickEvent} is the
 * single funnel for {@code /kick}, bans, plugin calls to
 * {@code Player.kickPlayer} and every anti-cheat built on the Bukkit API, so
 * cancelling it there covers all of them at once.
 *
 * <p>The second is refusing interference short of removal - a command that
 * names the player, damage, a teleport, a change out of spectator. Those do not
 * end the session, but they are the ways the player stops being what it is meant
 * to be: an identity in the player list with no body and no state worth changing.
 *
 * <p>Neither job covers a plugin that reaches into NMS and closes the connection
 * directly, because no event fires on that path. {@code FakePlayerManager}'s
 * revive handles that case by re-registering the player afterwards.
 */
public final class FakePlayerGuard implements Listener {

    private final FakePlayerManager manager;

    public FakePlayerGuard(FakePlayerManager manager) {
        this.manager = manager;
    }

    /**
     * Cancels the kick.
     *
     * <p>{@code MONITOR} is normally read-only, but the priority is deliberate:
     * this has to be the last word. A plugin listening at {@code HIGHEST} that
     * un-cancels the event would otherwise win, and the point of this class is
     * that nothing wins. {@code ignoreCancelled} is left off for the same reason -
     * an already-cancelled kick still has to reach here so it stays cancelled.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        if (manager.isProtected(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Reports a quit that got through.
     *
     * <p>Cancelling the kick above stops the ordinary paths, but a quit can also
     * arrive from an NMS-level disconnect that fires no cancellable event.
     * {@code PlayerQuitEvent} has no cancel of its own, so this only records that
     * it happened; the manager schedules the rejoin for the next tick.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (manager.isProtected(event.getPlayer().getUniqueId())) {
            manager.notifyRemoved();
        }
    }

    /** Refuses damage, so nothing can kill the player out of the world. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && manager.isProtected(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Refuses teleports, so the player cannot be dragged around the world. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        if (manager.isProtected(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Holds the player in spectator.
     *
     * <p>Spectator is what leaves it with no collidable body, so a change out of
     * it is refused. A change <em>into</em> it is allowed, because that is the
     * manager's own call during a join or a revive - and letting it through here
     * is simpler and less brittle than a flag saying "this one is mine".
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (manager.isProtected(event.getPlayer().getUniqueId())
                && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    /** Refuses a command a player typed that names the fake player. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (targetsFakePlayer(event.getMessage())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    "§c" + manager.name() + " is managed by Mineflayer; "
                            + "use /mineflayer m stop to remove it.");
        }
    }

    /** The same for the console and command blocks, which bypass the event above. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        if (targetsFakePlayer(event.getCommand())) {
            event.setCancelled(true);
            event.getSender().sendMessage(
                    manager.name() + " is managed by Mineflayer; "
                            + "use /mineflayer m stop to remove it.");
        }
    }

    /**
     * Whether a command line names the fake player as an argument.
     *
     * <p>Deliberately narrow. It matches the name as a whole word anywhere in the
     * arguments, which is what {@code /kill}, {@code /ban}, {@code /tp} and
     * {@code /gamemode} all look like, and it leaves the plugin's own commands
     * alone so the player can still be stopped the intended way.
     *
     * <p>What it does not do is guess at selectors. A {@code /kill @a} is not
     * cancelled, because refusing every selector command would break the server
     * for its actual players to protect one fake one. The event handlers above
     * are what cover that case: the effects a selector could have are already
     * individually refused.
     */
    private boolean targetsFakePlayer(String commandLine) {
        if (!manager.isOnline()) {
            return false;
        }

        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        if (isOwnCommand(parts[0])) {
            return false;
        }

        String name = manager.name();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the command is this plugin's, which is the one way out. */
    private static boolean isOwnCommand(String label) {
        // Namespaced forms such as 'mineflayer:mf' reach the same command.
        String name = label.toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name.equals("mineflayer") || name.equals("mf");
    }
}
