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
package dev.skyrealms.decay;

import dev.skyrealms.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cleansing Tonic I/II/III: while it lasts, the drinker causes no decay. The expiry is kept on the
 * player (persistent data) so it survives relogs, and a boss bar shows the time left.
 */
public final class CleansingTonic implements Listener, DecayResist {
    public static final int MAX_LEVEL = 3;
    public static final int CAP_SECONDS = 180;
    private static final String[] NUMERALS = {"", "I", "II", "III"};

    /** Per-level settings read from the {@code tonic:} section. */
    public record Tier(int seconds, double price, int requiredLevel) {}

    private final Plugin plugin;
    private final NamespacedKey itemKey, splashKey, untilKey;
    private final boolean shop;
    private final double splashRadius;
    private final Tier[] tiers = new Tier[MAX_LEVEL + 1];
    private final Map<UUID, Long> until = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> barTotal = new HashMap<>();

    public CleansingTonic(Plugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "cleansing_tonic");
        this.splashKey = new NamespacedKey(plugin, "cleansing_tonic_splash");
        this.untilKey = new NamespacedKey(plugin, "tonic_until");
        ConfigurationSection c = cfg != null ? cfg : new MemoryConfiguration();
        this.shop = c.getBoolean("shop", false);
        this.splashRadius = Math.max(1, Math.min(16, c.getDouble("splash-radius", 4)));
        int[] defSeconds = {0, 60, 120, 180};
        double[] defPrice = {0, 500, 1500, 4000};
        int[] defLevel = {0, 1, 10, 25};
        for (int lv = 1; lv <= MAX_LEVEL; lv++) {
            String p = "levels." + lv + ".";
            int secs = Math.max(1, Math.min(CAP_SECONDS, c.getInt(p + "seconds", defSeconds[lv])));
            tiers[lv] = new Tier(secs, Math.max(0, c.getDouble(p + "price", defPrice[lv])),
                    Math.max(1, c.getInt(p + "required-level", defLevel[lv])));
        }
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) loadFrom(p);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickBars, 20L, 20L);
    }

    /** Whether /skyland tonic buy is allowed. Off: tonics are brewed. */
    public boolean shopEnabled() { return shop; }

    public Tier tier(int level) { return tiers[Math.max(1, Math.min(MAX_LEVEL, level))]; }

    public static String numeral(int level) { return NUMERALS[Math.max(1, Math.min(MAX_LEVEL, level))]; }

    // ---- the item --------------------------------------------------------------------

    public ItemStack create(int level, int amount) {
        int lv = Math.max(1, Math.min(MAX_LEVEL, level));
        ItemStack item = new ItemStack(Material.POTION, Math.max(1, Math.min(64, amount)));
        item.editMeta(PotionMeta.class, m -> {
            m.setColor(Color.fromRGB(0x7FE3D0));
            m.displayName(Msg.c("<!italic><aqua>Cleansing Tonic " + numeral(lv)));
            m.lore(List.of(
                    Msg.c("<!italic><gray>Your presence causes no corruption"),
                    Msg.c("<!italic><gray>for <white>" + tiers[lv].seconds() + " seconds</white>.")));
            m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            m.getPersistentDataContainer().set(itemKey, PersistentDataType.INTEGER, lv);
        });
        return item;
    }

    /** A splash tonic: when it lands, every player within the splash radius is cleansed. */
    public ItemStack createSplash(int level, int amount) {
        int lv = Math.max(1, Math.min(MAX_LEVEL, level));
        ItemStack item = new ItemStack(Material.SPLASH_POTION, Math.max(1, Math.min(64, amount)));
        item.editMeta(PotionMeta.class, m -> {
            m.setColor(Color.fromRGB(0x7FE3D0));
            m.displayName(Msg.c("<!italic><aqua>Splash Cleansing Tonic " + numeral(lv)));
            m.lore(List.of(
                    Msg.c("<!italic><gray>Everyone it splashes causes no"),
                    Msg.c("<!italic><gray>corruption for <white>" + tiers[lv].seconds() + " seconds</white>.")));
            m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            m.getPersistentDataContainer().set(splashKey, PersistentDataType.INTEGER, lv);
        });
        return item;
    }

    /** The splash tonic level of an item, or 0 if it isn't a splash tonic. */
    public int splashLevelOf(ItemStack item) {
        if (item == null || item.getType() != Material.SPLASH_POTION || !item.hasItemMeta()) return 0;
        Integer lv = item.getItemMeta().getPersistentDataContainer().get(splashKey, PersistentDataType.INTEGER);
        return lv == null ? 0 : Math.max(1, Math.min(MAX_LEVEL, lv));
    }

    /** The tonic level of an item, or 0 if it isn't a tonic. */
    public int levelOf(ItemStack item) {
        if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) return 0;
        Integer lv = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.INTEGER);
        return lv == null ? 0 : Math.max(1, Math.min(MAX_LEVEL, lv));
    }

    // ---- effect ----------------------------------------------------------------------

    @Override
    public double factor(Player player) {
        Long t = until.get(player.getUniqueId());
        return t != null && t > System.currentTimeMillis() ? 0.0 : 1.0;
    }

    public long remainingMillis(Player p) {
        Long t = until.get(p.getUniqueId());
        return t == null ? 0 : Math.max(0, t - System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        int lv = levelOf(e.getItem());
        if (lv == 0) return;
        apply(e.getPlayer(), lv);
    }

    /** A splash tonic cleanses every player within the splash radius instead of vanilla's splash area. */
    @EventHandler(ignoreCancelled = true)
    public void onSplash(org.bukkit.event.entity.PotionSplashEvent e) {
        int lv = splashLevelOf(e.getPotion().getItem());
        if (lv == 0) return;
        var at = e.getPotion().getLocation();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= splashRadius * splashRadius) apply(p, lv);
        }
    }

    /** Cleanses a player as if they'd drunk a tonic of this level. */
    public void apply(Player p, int level) {
        int lv = Math.max(1, Math.min(MAX_LEVEL, level));
        long now = System.currentTimeMillis();
        long fresh = now + tiers[lv].seconds() * 1000L;
        long current = until.getOrDefault(p.getUniqueId(), 0L);
        long end = Math.min(now + CAP_SECONDS * 1000L, Math.max(current, fresh));
        until.put(p.getUniqueId(), end);
        p.getPersistentDataContainer().set(untilKey, PersistentDataType.LONG, end);
        barTotal.put(p.getUniqueId(), end - now);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
        Msg.send(p, "The corruption can't take hold of you for <white>" + ((end - now) / 1000) + "s</white>.");
        updateBar(p, end - now);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { loadFrom(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        until.remove(id);
        barTotal.remove(id);
        BossBar bar = bars.remove(id);
        if (bar != null) e.getPlayer().hideBossBar(bar);
    }

    private void loadFrom(Player p) {
        Long end = p.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        if (end == null) return;
        long left = end - System.currentTimeMillis();
        if (left <= 0) { p.getPersistentDataContainer().remove(untilKey); return; }
        until.put(p.getUniqueId(), end);
        barTotal.put(p.getUniqueId(), Math.min(left, CAP_SECONDS * 1000L));
    }

    private void tickBars() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long end = until.get(p.getUniqueId());
            if (end == null) continue;
            long left = end - now;
            if (left <= 0) {
                until.remove(p.getUniqueId());
                barTotal.remove(p.getUniqueId());
                p.getPersistentDataContainer().remove(untilKey);
                BossBar bar = bars.remove(p.getUniqueId());
                if (bar != null) p.hideBossBar(bar);
                Msg.send(p, "Your Cleansing Tonic has worn off.");
                continue;
            }
            updateBar(p, left);
        }
    }

    private void updateBar(Player p, long left) {
        long total = Math.max(1, barTotal.getOrDefault(p.getUniqueId(), left));
        float progress = (float) Math.max(0, Math.min(1, (double) left / total));
        Component title = Msg.c("<aqua>Cleansed <gray>- <white>" + (left + 999) / 1000 + "s");
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }
}
