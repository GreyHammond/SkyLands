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
package dev.skyrealms.economy;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Role;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.*;

/**
 * Visiting someone's island tips both the owner and the visitor, once the visitor has stayed a
 * little while. Capped per day so it can't be farmed.
 */
public final class VisitorTips implements Listener {
    private final SkyRealmsPlugin plugin;
    private final boolean enabled;
    private final double ownerTip, visitorTip, perLevel, maxMultiplier;
    private final int staySeconds, maxPerVisitor, maxPerOwner;
    private final File file;

    private String day = "";
    private final Set<String> pairs = new HashSet<>();               // "visitor:owner" tipped today
    private final Map<UUID, Integer> visitorCounts = new HashMap<>(); // tips received as a visitor today
    private final Map<UUID, Integer> ownerCounts = new HashMap<>();   // tips received as an owner today
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public VisitorTips(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        enabled = cfg.getBoolean("enabled", true);
        ownerTip = Math.max(0, cfg.getDouble("owner-tip", 50));
        visitorTip = Math.max(0, cfg.getDouble("visitor-tip", 20));
        perLevel = Math.max(0, cfg.getDouble("per-island-level", 0.02));
        maxMultiplier = Math.max(1, cfg.getDouble("max-multiplier", 2.0));
        staySeconds = Math.max(0, cfg.getInt("min-stay-seconds", 30));
        maxPerVisitor = Math.max(0, cfg.getInt("max-tips-per-visitor", 5));
        maxPerOwner = Math.max(0, cfg.getInt("max-tips-per-owner", 20));
        file = new File(plugin.getDataFolder(), "tips.yml");
        load();
    }

    public boolean enabled() { return enabled; }

    /** Call after a visit teleport succeeds. Pays out if the visitor is still there after the stay time. */
    public void startVisit(Player visitor, Island island) {
        if (!enabled || island == null) return;
        UUID id = visitor.getUniqueId();
        cancel(id);
        if (reason(visitor, island) != null) return;
        UUID owner = island.owner;
        pending.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            Island now = plugin.islands().ofOwner(owner);
            if (now != island || !visitor.isOnline()) return;
            Location l = visitor.getLocation();
            if (!l.getWorld().equals(plugin.skyWorld()) || !island.contains(l.getX(), l.getZ())) return;
            pay(visitor, island);
        }, Math.max(1, staySeconds * 20L)));
    }

    /** Why this visit can't tip, or null if it can. */
    private String reason(Player visitor, Island island) {
        rollDay();
        UUID v = visitor.getUniqueId();
        if (island.roleOf(v) != Role.VISITOR) return "member";
        if (pairs.contains(v + ":" + island.owner)) return "already today";
        if (visitorCounts.getOrDefault(v, 0) >= maxPerVisitor) return "visitor cap";
        if (ownerCounts.getOrDefault(island.owner, 0) >= maxPerOwner) return "owner cap";
        Player owner = Bukkit.getPlayer(island.owner);
        if (owner != null && sameIp(owner, visitor)) return "same ip";
        return null;
    }

    private static boolean sameIp(Player a, Player b) {
        InetSocketAddress x = a.getAddress(), y = b.getAddress();
        return x != null && y != null && x.getAddress() != null && x.getAddress().equals(y.getAddress());
    }

    private void pay(Player visitor, Island island) {
        if (reason(visitor, island) != null) return;
        double mult = Math.min(maxMultiplier, 1 + perLevel * plugin.xp().level(island));
        double toOwner = Math.round(ownerTip * mult), toVisitor = Math.round(visitorTip * mult);
        UUID v = visitor.getUniqueId();
        pairs.add(v + ":" + island.owner);
        visitorCounts.merge(v, 1, Integer::sum);
        ownerCounts.merge(island.owner, 1, Integer::sum);
        save();

        var ownerPlayer = Bukkit.getOfflinePlayer(island.owner);
        if (toOwner > 0) {
            plugin.money().refund(ownerPlayer, toOwner);
            Player online = ownerPlayer.getPlayer();
            if (online != null) {
                Msg.send(online, "<white>" + visitor.getName() + "</white> visited your island: <gold>+" + plugin.money().format(toOwner));
                online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
            }
        }
        if (toVisitor > 0) {
            plugin.money().refund(visitor, toVisitor);
            Msg.send(visitor, "Thanks for visiting: <gold>+" + plugin.money().format(toVisitor));
            visitor.playSound(visitor.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
        }
    }

    private void cancel(UUID id) {
        BukkitTask t = pending.remove(id);
        if (t != null) t.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { cancel(e.getPlayer().getUniqueId()); }

    // ---- daily state ----------------------------------------------------------------------

    private void rollDay() {
        String today = LocalDate.now().toString();
        if (today.equals(day)) return;
        day = today;
        pairs.clear();
        visitorCounts.clear();
        ownerCounts.clear();
        save();
    }

    private void load() {
        if (!file.exists()) { rollDay(); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        day = y.getString("day", "");
        pairs.addAll(y.getStringList("pairs"));
        readCounts(y.getConfigurationSection("visitors"), visitorCounts);
        readCounts(y.getConfigurationSection("owners"), ownerCounts);
        rollDay();
    }

    private static void readCounts(ConfigurationSection s, Map<UUID, Integer> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try { into.put(UUID.fromString(k), s.getInt(k)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("day", day);
        y.set("pairs", new ArrayList<>(pairs));
        visitorCounts.forEach((k, v) -> y.set("visitors." + k, v));
        ownerCounts.forEach((k, v) -> y.set("owners." + k, v));
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Could not save tips.yml: " + e.getMessage()); }
    }
}
