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
package dev.skyrealms.level;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.BlockRules;
import dev.skyrealms.island.Flag;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Role;
import dev.skyrealms.util.Msg;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.time.Duration;
import java.util.*;

/** Earns XP from building, farming and visits, and shows a banner when you arrive on an island. */
public final class XpListener implements Listener {
    private final SkyRealmsPlugin plugin;
    private final Map<UUID, Integer> currentIsland = new HashMap<>();

    public XpListener(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    private Island islandFor(Player p, org.bukkit.Location loc) {
        if (!loc.getWorld().equals(plugin.skyWorld())) return null;
        return plugin.islands().at(loc.getX(), loc.getZ());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        Island i = islandFor(e.getPlayer(), e.getBlock().getLocation());
        if (i == null || !i.roleOf(e.getPlayer().getUniqueId()).manages()) return;
        plugin.xp().blockPlaced(i);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Island i = islandFor(e.getPlayer(), e.getBlock().getLocation());
        if (i == null || !i.roleOf(e.getPlayer().getUniqueId()).manages()) return;
        if (BlockRules.forBreak(e.getBlock()) == Flag.HARVEST && BlockRules.isCrop(e.getBlock().getType())) plugin.xp().cropHarvested(i);
        else plugin.xp().blockBroken(i);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        arrive(e.getPlayer(), e.getTo());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> arrive(e.getPlayer(), e.getPlayer().getLocation()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { currentIsland.remove(e.getPlayer().getUniqueId()); }

    private void arrive(Player p, org.bukkit.Location to) {
        Island i = islandFor(p, to);
        Integer now = i == null ? null : i.index;
        Integer before = currentIsland.get(p.getUniqueId());
        if (Objects.equals(now, before)) return;
        if (now == null) { currentIsland.remove(p.getUniqueId()); return; }
        currentIsland.put(p.getUniqueId(), now);

        Role role = i.roleOf(p.getUniqueId());
        String owner = Objects.toString(Bukkit.getOfflinePlayer(i.owner).getName(), "Someone");
        String shown = i.name != null ? plugin.cosmetics().displayName(i) : role == Role.OWNER ? "Your Island" : owner + "'s Island";
        p.showTitle(Title.title(Msg.c("<white>" + shown),
                Msg.c("<gold>" + plugin.xp().badge(i)),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1800), Duration.ofMillis(600))));
        if (role == Role.VISITOR) plugin.xp().visited(i, p.getUniqueId());
    }
}
