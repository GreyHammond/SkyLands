/*
 * SkyLands (SkyRealms) - floating-island survival for Paper
 * Copyright (C) 2026 Hammond Digital Studios
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License version 3, together with the additional terms in the NOTICE
 * file (section 7: you must keep the attribution "SkyLands by Hammond Digital Studios" and mark
 * modified versions). This program is distributed WITHOUT ANY WARRANTY. See the LICENSE and
 * NOTICE files, or <https://www.gnu.org/licenses/>.
 */
package dev.skyrealms.player;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Tier;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Brand-new players: the welcome (an automatic free island, or the claim-island flow) on first
 * join. Everyone with an island: respawning there when they have no bed or anchor.
 */
public final class FirstJoin implements Listener {
    private final SkyRealmsPlugin plugin;
    private final boolean enabled, autoIsland, islandRespawn;
    private final String autoBiome;
    private final long delay;

    /** What a brand-new player sees when no automatic island is made. The journal replaces this. */
    public Consumer<Player> onFirstJoin;

    public FirstJoin(SkyRealmsPlugin plugin) {
        this.plugin = plugin;
        var cfg = plugin.getConfig();
        ConfigurationSection fj = section(cfg.getConfigurationSection("first-join"));
        ConfigurationSection fi = section(cfg.getConfigurationSection("first-island"));
        this.enabled = fj.getBoolean("enabled", true);
        this.delay = Math.max(1, fj.getLong("delay-ticks", 40));
        this.autoIsland = fi.getBoolean("auto", false);
        this.autoBiome = fi.getString("auto-biome", "plains").toLowerCase(Locale.ROOT);
        this.islandRespawn = cfg.getBoolean("spawn.island-fallback", true);
        this.onFirstJoin = p -> plugin.menus().claimFirstIsland(p);
    }

    private static ConfigurationSection section(ConfigurationSection s) { return s != null ? s : new MemoryConfiguration(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        var prof = plugin.profiles().get(p.getUniqueId());
        if (prof.seenFirstJoin) return;
        prof.seenFirstJoin = true;
        plugin.profiles().dirty();
        if (!enabled || plugin.islands().of(p.getUniqueId()) != null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> welcome(p), delay);
    }

    /** Players given an automatic island who haven't landed on it yet. */
    private final java.util.Set<java.util.UUID> pendingArrival = new java.util.HashSet<>();
    /** Runs when a player with an automatic island first lands on it (the story opens here). */
    public Consumer<Player> onAutoArrive = p -> {};

    /** Call when a player lands on a newly claimed island (CLAIMED_ISLAND). */
    public void arrived(Player p) {
        if (pendingArrival.remove(p.getUniqueId()) && onAutoArrive != null) onAutoArrive.accept(p);
    }

    /** Runs the welcome now. Also usable to replay it for a player. */
    public void welcome(Player p) {
        if (!p.isOnline() || plugin.islands().of(p.getUniqueId()) != null) return;
        if (autoIsland) {
            String biome = plugin.biomes().startable(autoBiome) ? autoBiome : null;
            if (biome == null) {
                plugin.getLogger().warning("first-island.auto-biome '" + autoBiome + "' can't start an island; showing the claim menu instead.");
                onFirstJoin.accept(p);
                return;
            }
            if (plugin.service().buy(p, Tier.SMALL, biome)) pendingArrival.add(p.getUniqueId());
            return;
        }
        if (onFirstJoin != null) onFirstJoin.accept(p);
    }

    /** No bed or anchor? Respawn on the island the player owns or co-owns. End-exit respawns stay vanilla. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!islandRespawn || e.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return;
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        Island i = plugin.islands().of(e.getPlayer().getUniqueId());
        if (i == null) return;
        e.setRespawnLocation(i.home(plugin.skyWorld()));
    }
}
