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
package dev.skyrealms.contracts;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.economy.VoidCommands;
import dev.skyrealms.economy.VoidItems;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Daily contracts: each player gets a few tasks a day that pay out money (and sometimes Void Shards).
 * Progress lives in plugins/SkyRealms/contracts.yml.
 */
public final class ContractService implements Listener {
    private final SkyRealmsPlugin plugin;
    private final File file;
    private final boolean enabled;
    private final int perDay, resetHour;
    private final double recycleBonus;
    private final List<ContractType.Template> pool = new ArrayList<>();
    private final NamespacedKey corruptedKey;

    private static final class Daily {
        String day;
        final List<Contract> list = new ArrayList<>();
    }

    private final Map<UUID, Daily> players = new HashMap<>();
    private boolean dirty;

    public ContractService(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        this.file = new File(plugin.getDataFolder(), "contracts.yml");
        this.enabled = cfg.getBoolean("enabled", true);
        this.perDay = Math.max(1, Math.min(7, cfg.getInt("per-day", 3)));
        this.resetHour = Math.max(0, Math.min(23, cfg.getInt("reset-hour", 4)));
        this.recycleBonus = Math.max(1, cfg.getDouble("recycle-bonus", 1.5));
        this.corruptedKey = new NamespacedKey(plugin, "corrupted_stage");
        ConfigurationSection p = cfg.getConfigurationSection("pool");
        if (p != null) {
            for (String id : p.getKeys(false)) {
                ConfigurationSection c = p.getConfigurationSection(id);
                ContractType.Template t = c == null ? null : ContractType.Template.load(id, c);
                if (t == null) plugin.getLogger().warning("contracts.pool." + id + " is invalid; skipped.");
                else if (t.weight() > 0) pool.add(t);
            }
        }
        if (pool.isEmpty()) pool.addAll(defaults());
    }

    public boolean enabled() { return enabled; }

    public void start() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 1200L, 1200L);
    }

    // ---- days --------------------------------------------------------------------------

    /** Today's contract day; a new day starts at reset-hour local time. */
    String today() { return LocalDateTime.now().minusHours(resetHour).toLocalDate().toString(); }

    /** Seconds until the next reset. */
    public long secondsUntilReset() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Duration.between(now, next).getSeconds();
    }

    /** Multiplier applied to rewards when the overworld recycle is less than a day away. */
    public double bonus() {
        var r = plugin.recycler();
        if (r != null && r.enabled() && r.secondsUntilNext() <= 24 * 3600) return recycleBonus;
        return 1.0;
    }

    /** The player's contracts for today, rolling new ones if the day changed. */
    public List<Contract> contracts(Player p) { return daily(p.getUniqueId()).list; }

    private Daily daily(UUID id) {
        String day = today();
        Daily d = players.get(id);
        if (d == null || !day.equals(d.day)) {
            d = new Daily();
            d.day = day;
            roll(d);
            players.put(id, d);
            dirty = true;
        }
        return d;
    }

    private void roll(Daily d) {
        d.list.clear();
        List<ContractType.Template> left = new ArrayList<>(pool);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int n = 0; n < perDay && !left.isEmpty(); n++) {
            int total = left.stream().mapToInt(ContractType.Template::weight).sum();
            int roll = r.nextInt(total);
            ContractType.Template pick = left.get(left.size() - 1);
            for (ContractType.Template t : left) { roll -= t.weight(); if (roll < 0) { pick = t; break; } }
            left.remove(pick);
            int amount = r.nextInt(pick.minAmount(), pick.maxAmount() + 1);
            double reward = Math.round(pick.minReward() + r.nextDouble() * (pick.maxReward() - pick.minReward()));
            int shards = r.nextInt(pick.minShards(), pick.maxShards() + 1);
            d.list.add(new Contract(pick.type(), pick.item(), amount, reward, shards));
        }
    }

    public void reset(UUID id) {
        players.remove(id);
        dirty = true;
    }

    // ---- actions (from menus and forms) -------------------------------------------------

    /** Hand items in, or claim a finished contract. Returns a short message for the player. */
    public String use(Player p, int index) {
        List<Contract> list = contracts(p);
        if (index < 0 || index >= list.size()) return null;
        Contract c = list.get(index);
        if (c.claimed) return "<gray>Already claimed. New contracts in " + duration(secondsUntilReset()) + ".";
        if (!c.complete() && c.type.handIn()) {
            int taken = take(p, c, c.amount - c.progress);
            if (taken == 0) return c.type == ContractType.SHARDS ? "<red>You have no Void Shards." : "<red>You have no " + Contract.pretty(c.item) + ".";
            c.progress += taken;
            dirty = true;
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.0f);
            if (!c.complete()) return "<gray>Handed in <white>" + taken + "</white>. " + (c.amount - c.progress) + " to go.";
        }
        if (!c.complete()) return "<gray>Not done yet: <white>" + c.bar();
        double pay = c.reward * bonus();
        c.claimed = true;
        dirty = true;
        plugin.money().refund(p, pay);
        if (c.shards > 0) VoidCommands.give(p, VoidItems.Kind.SHARD, c.shards);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        return "<green>Contract complete! <gold>+" + plugin.service().money(pay) + "</gold>"
                + (c.shards > 0 ? " <dark_purple>+" + c.shards + " Void Shards</dark_purple>" : "");
    }

    private int take(Player p, Contract c, int want) {
        ItemStack[] inv = p.getInventory().getStorageContents();
        int taken = 0;
        for (int i = 0; i < inv.length && taken < want; i++) {
            ItemStack it = inv[i];
            if (it == null) continue;
            boolean match = c.type == ContractType.SHARDS
                    ? VoidItems.kindOf(it) == VoidItems.Kind.SHARD
                    : it.getType() == c.item && VoidItems.kindOf(it) == null && !it.hasItemMeta();
            if (!match) continue;
            int n = Math.min(it.getAmount(), want - taken);
            taken += n;
            if (n == it.getAmount()) inv[i] = null; else it.setAmount(it.getAmount() - n);
        }
        if (taken > 0) p.getInventory().setStorageContents(inv);
        return taken;
    }

    // ---- tracking ----------------------------------------------------------------------

    private boolean inDecayWorld(World w) {
        var d = plugin.decay();
        if (d == null || w == null) return false;
        return w.getName().equals(d.worldName());
    }

    private void progress(Player p, ContractType type, int n) {
        Daily d = daily(p.getUniqueId());
        for (Contract c : d.list) {
            if (c.type != type || c.claimed || c.complete()) continue;
            c.progress = Math.min(c.amount, c.progress + n);
            dirty = true;
            if (c.complete()) {
                Msg.send(p, "<green>Contract done:</green> <white>" + c.title() + "</white>. Claim it in <light_purple>The Corruption</light_purple> menu.");
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.4f);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || !(dead instanceof Enemy) || !inDecayWorld(dead.getWorld())) return;
        progress(killer, ContractType.KILL, 1);
        if (dead.getPersistentDataContainer().has(corruptedKey, PersistentDataType.INTEGER)) {
            progress(killer, ContractType.KILL_CORRUPTED, 1);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == Material.AIR || !inDecayWorld(e.getBlock().getWorld())) return;
        progress(e.getPlayer(), ContractType.MINE, 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        var from = e.getFrom();
        var to = e.getTo();
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) return;
        if (!inDecayWorld(to.getWorld())) return;
        Player p = e.getPlayer();
        long key = ((long) (to.getBlockX() >> 4) << 32) ^ ((to.getBlockZ() >> 4) & 0xffffffffL);
        for (Contract c : daily(p.getUniqueId()).list) {
            if (c.type != ContractType.EXPLORE || c.claimed || c.complete()) continue;
            if (!c.visited.add(key)) return;
        }
        progress(p, ContractType.EXPLORE, 1);
    }

    // ---- persistence -------------------------------------------------------------------

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            Daily d = new Daily();
            d.day = s.getString("day", "");
            for (Map<?, ?> m : s.getMapList("contracts")) {
                ContractType t = ContractType.parse(String.valueOf(m.get("type")));
                if (t == null) continue;
                Material item = m.get("item") == null ? null : Material.matchMaterial(String.valueOf(m.get("item")));
                if (t == ContractType.DELIVER && item == null) continue;
                Contract c = new Contract(t, item, num(m.get("amount"), 1), dbl(m.get("reward")), num(m.get("shards"), 0));
                c.progress = num(m.get("progress"), 0);
                c.claimed = Boolean.TRUE.equals(m.get("claimed"));
                if (m.get("visited") instanceof List<?> l) for (Object o : l) { try { c.visited.add(Long.parseLong(String.valueOf(o))); } catch (NumberFormatException ignored) {} }
                d.list.add(c);
            }
            players.put(id, d);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        String day = today();
        for (Map.Entry<UUID, Daily> e : players.entrySet()) {
            Daily d = e.getValue();
            if (!day.equals(d.day)) continue; // stale days aren't worth keeping
            ConfigurationSection s = y.createSection(e.getKey().toString());
            s.set("day", d.day);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Contract c : d.list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", c.type.name());
                if (c.item != null) m.put("item", c.item.name());
                m.put("amount", c.amount);
                m.put("reward", c.reward);
                m.put("shards", c.shards);
                m.put("progress", c.progress);
                m.put("claimed", c.claimed);
                if (!c.visited.isEmpty()) m.put("visited", c.visited.stream().map(String::valueOf).toList());
                list.add(m);
            }
            s.set("contracts", list);
        }
        try { y.save(file); dirty = false; } catch (IOException ex) { plugin.getLogger().warning("Couldn't save contracts.yml: " + ex.getMessage()); }
    }

    private static int num(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    public static String duration(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private static List<ContractType.Template> defaults() {
        return List.of(
                new ContractType.Template("copper", ContractType.DELIVER, Material.COPPER_INGOT, 32, 96, 150, 400, 0, 0, 10),
                new ContractType.Template("iron", ContractType.DELIVER, Material.IRON_INGOT, 16, 48, 200, 500, 0, 2, 10),
                new ContractType.Template("logs", ContractType.DELIVER, Material.OAK_LOG, 32, 64, 100, 250, 0, 0, 8),
                new ContractType.Template("coal", ContractType.DELIVER, Material.COAL, 32, 64, 100, 250, 0, 0, 8),
                new ContractType.Template("hunt", ContractType.KILL, null, 15, 30, 200, 450, 0, 2, 10),
                new ContractType.Template("corrupted", ContractType.KILL_CORRUPTED, null, 5, 12, 400, 800, 2, 5, 6),
                new ContractType.Template("mine", ContractType.MINE, null, 200, 500, 150, 400, 0, 1, 8),
                new ContractType.Template("explore", ContractType.EXPLORE, null, 8, 20, 150, 350, 0, 1, 8),
                new ContractType.Template("shards", ContractType.SHARDS, null, 10, 30, 300, 700, 0, 0, 6));
    }
}
