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
import dev.skyrealms.island.Island;
import dev.skyrealms.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Gives islands XP, tracks the daily caps, and celebrates level-ups. */
public final class XpService {
    private final SkyRealmsPlugin plugin;
    private final Levels levels;

    /** Daily counters per island (in memory; they reset each day and on restart). */
    private static final class Day {
        LocalDate date = LocalDate.now();
        int placed, harvested, visitorXp;
        final Set<UUID> visitors = new HashSet<>();
    }
    private final Map<Integer, Day> days = new HashMap<>();

    public XpService(SkyRealmsPlugin plugin, Levels levels) {
        this.plugin = plugin;
        this.levels = levels;
    }

    public Levels levels() { return levels; }

    public int level(Island i) { return levels.levelOf(i.xp); }

    public String title(Island i) { return levels.title(level(i)); }

    /** "Lv 12 · Settler" */
    public String badge(Island i) { return "Lv " + level(i) + " · " + title(i); }

    private Day day(Island i) {
        Day d = days.computeIfAbsent(i.index, k -> new Day());
        if (!d.date.equals(LocalDate.now())) { d = new Day(); days.put(i.index, d); }
        return d;
    }

    // ---- sources ------------------------------------------------------------------------

    public void blockPlaced(Island i) {
        Day d = day(i);
        if (d.placed >= levels.placeCap) return;
        d.placed += levels.placeBlock;
        add(i, levels.placeBlock);
    }

    /** Breaking a block takes back XP from blocks placed today, so place-and-break earns nothing. */
    public void blockBroken(Island i) {
        Day d = day(i);
        if (d.placed <= 0) return;
        d.placed -= levels.placeBlock;
        i.xp = Math.max(0, i.xp - levels.placeBlock);
    }

    public void cropHarvested(Island i) {
        Day d = day(i);
        if (d.harvested >= levels.harvestCap) return;
        d.harvested += levels.harvestCrop;
        add(i, levels.harvestCrop);
    }

    /** Returns true if this counted as a new unique visit today. */
    public boolean visited(Island i, UUID visitor) {
        Day d = day(i);
        if (!d.visitors.add(visitor)) return false;
        i.visitorsTotal++;
        if (d.visitorXp < levels.visitorCap) {
            d.visitorXp += levels.visitor;
            add(i, levels.visitor);
        }
        return true;
    }

    // ---- core ---------------------------------------------------------------------------

    /** Add XP and celebrate any level-ups. */
    public void add(Island i, long amount) {
        if (amount <= 0) return;
        int before = level(i);
        i.xp += amount;
        int after = level(i);
        for (int lv = before + 1; lv <= after; lv++) levelUp(i, lv);
    }

    /** Admin: set XP outright (no celebrations for skipped levels). */
    public void set(Island i, long xp) { i.xp = Math.max(0, xp); }

    /** Back-fill XP for islands created before levels existed. */
    public void backfill(Island i) {
        if (i.xpBackfilled) return;
        i.xpBackfilled = true;
        if (i.xp > 0) return;
        i.xp = (long) i.pieces.size() * levels.growPiece + (long) Math.max(0, i.biomes.size() - 1) * levels.unlockBiome;
    }

    private void levelUp(Island i, int lv) {
        String title = levels.title(lv);
        boolean newTitle = levels.titles().containsKey(lv);
        List<String> unlocked = new ArrayList<>();
        for (String b : plugin.biomes().all()) if (levels.biomeLevel(b) == lv) unlocked.add(plugin.biomes().pretty(b));
        List<String> styles = new ArrayList<>();
        for (var st : dev.skyrealms.terrain.UndersideStyle.values()) if (levels.undersideLevel(st.id) == lv && lv > 1) styles.add(st.displayName);

        for (UUID id : i.managers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.showTitle(Title.title(Msg.c("<gold>Island Level " + lv + "!"),
                    Msg.c(newTitle ? "<yellow>New title: " + title : "<gray>" + title),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            Msg.send(p, "Your island reached <gold>level " + lv + "</gold>"
                    + (newTitle ? " and earned the title <yellow>" + title + "</yellow>" : "") + "!");
            if (!unlocked.isEmpty()) p.sendMessage(Msg.c(" <gray>New biomes to unlock: <green>" + String.join(", ", unlocked)));
            List<String> cos = plugin.cosmetics().unlocksAt(lv);
            if (!cos.isEmpty()) p.sendMessage(Msg.c(" <gray>New cosmetics: <light_purple>" + String.join(", ", cos)
                    + " <dark_gray>(Manage Island → Cosmetics)"));
            if (!styles.isEmpty()) p.sendMessage(Msg.c(" <gray>New underside style: <aqua>" + String.join(", ", styles)
                    + " <dark_gray>(Manage Island → Underside Style)"));
            if (p.getWorld().equals(plugin.skyWorld()) && i.containsLand(p.getX(), p.getZ())) firework(p.getLocation());
        }
        if (levels.announceEvery > 0 && lv % levels.announceEvery == 0) {
            Bukkit.broadcast(Msg.c("<dark_aqua>[<aqua>SkyLands</aqua>]</dark_aqua> <white>"
                    + plugin.cosmetics().displayName(i) + "</white> <gray>reached <gold>level " + lv + "</gold>: <yellow>" + title + "</yellow>!"));
        }
        for (String cmd : levels.rewards(lv)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{owner}", name(i.owner))
                    .replace("{level}", String.valueOf(lv)).replace("{title}", title));
        }
    }

    private void firework(Location at) {
        Firework fw = at.getWorld().spawn(at.clone().add(0, 1, 0), Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.AQUA, Color.LIME).withFade(Color.WHITE).trail(true).build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }

    private static String name(UUID id) { return Objects.toString(Bukkit.getOfflinePlayer(id).getName(), "Someone"); }

    // ---- progress display ------------------------------------------------------------------

    /** "▰▰▰▰▱▱▱▱▱▱  3,120 / 4,400 XP" */
    public String progressBar(Island i) {
        int lv = level(i);
        if (lv >= levels.maxLevel) return "<gold>MAX LEVEL";
        long from = levels.xpFor(lv), to = levels.xpFor(lv + 1);
        double f = (i.xp - from) / (double) (to - from);
        int filled = (int) Math.round(f * 10);
        return "<green>" + "▰".repeat(filled) + "<dark_gray>" + "▱".repeat(10 - filled)
                + " <gray>" + String.format("%,d / %,d XP", i.xp - from, to - from);
    }
}
