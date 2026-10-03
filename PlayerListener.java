package com.steelrework.playerprogress.listener;

import com.steelrework.playerprogress.PlayerProgressPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Вход, выход, смена мира и возрождение. */
public final class PlayerListener implements Listener {

    private final PlayerProgressPlugin plugin;

    public PlayerListener(PlayerProgressPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.progress().data(p);
        plugin.progress().updateAttributes(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        plugin.data().saveAsync(plugin.progress().data(p));
        plugin.forgetPlayer(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.progress().updateAttributes(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.progress().updateAttributes(p);
        });
    }
}
