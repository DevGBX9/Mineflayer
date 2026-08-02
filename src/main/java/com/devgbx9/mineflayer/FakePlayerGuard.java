package com.devgbx9.mineflayer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Refuses kicks aimed at the fake player.
 *
 * <p>{@code PlayerKickEvent} is the single funnel for {@code /kick}, bans, plugin
 * calls to {@code Player.kickPlayer} and every anti-cheat built on the Bukkit API,
 * so cancelling it there covers all of them at once.
 *
 * <p>It does not cover a plugin that reaches into NMS and closes the connection
 * directly, because no event fires on that path. {@code FakePlayerManager}'s own
 * timer handles that case by re-registering the player after the fact.
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
     * it happened; the manager's timer notices the player is gone and puts it
     * back within half a second.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (manager.isProtected(event.getPlayer().getUniqueId())) {
            manager.notifyRemoved();
        }
    }
}
