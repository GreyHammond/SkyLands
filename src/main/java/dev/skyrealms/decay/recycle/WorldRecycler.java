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
package dev.skyrealms.decay.recycle;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.util.Msg;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The weekly overworld recycle. The overworld players fall into is a managed world named
 * {@code <base>_<cycle>}. On each recycle everyone in it is sent home, a fresh world with a new
 * seed is created, every target is switched to it, and the old world is unloaded and deleted.
 */
public final class WorldRecycler implements Listener {
    private static final int DELETE_TRIES = 10;
    private static final long DELETE_RETRY_TICKS = 40L;

    private final SkyRealmsPlugin plugin;
    private final boolean enabled;
    private final String base;
    private final Pattern managedName;
    private final long intervalMillis;
    private final TreeSet<Integer> warnings = new TreeSet<>(Comparator.reverseOrder());
    private final Set<String> blockedCommands = new HashSet<>();
    private final String msgWarning, msgStart, msgDone, msgEvacuated, msgBlocked;
    private final RecycleState state;

    private BukkitTask countdown;
    private int secondsLeft = -1;
    private boolean resetting;

    public WorldRecycler(SkyRealmsPlugin plugin, ConfigurationSection section) {
        this.plugin = plugin;
        ConfigurationSection c = section != null ? section : new MemoryConfiguration();
        enabled = c.getBoolean("enabled", false);
        base = c.getString("base-name", "skyrealms_overworld").replaceAll("[^A-Za-z0-9_\\-]", "");
        managedName = Pattern.compile(Pattern.quote(base) + "_\\d+");
        intervalMillis = (long) (Math.max(0.01, c.getDouble("interval-days", 7)) * 24 * 60 * 60 * 1000L);
        List<Integer> w = c.getIntegerList("warnings");
        warnings.addAll(w.isEmpty() ? List.of(600, 300, 60, 30, 10) : w);
        warnings.removeIf(s -> s <= 0);
        if (warnings.isEmpty()) warnings.add(10);
        for (String cmd : c.getStringList("blocked-commands").isEmpty() && !c.isList("blocked-commands")
                ? List.of("sethome", "esethome", "createhome", "setwarp") : c.getStringList("blocked-commands")) {
            blockedCommands.add(cmd.toLowerCase(Locale.ROOT).replace("/", ""));
        }
        ConfigurationSection m = c.getConfigurationSection("messages");
        if (m == null) m = new MemoryConfiguration();
        msgWarning = m.getString("warning", "<red>The overworld will be recycled in <white>{time}</white> to contain the corruption. Get to your island!");
        msgStart = m.getString("start", "<red>Recycling the overworld to contain the corruption...");
        msgDone = m.getString("done", "<green>The corruption is contained. A fresh overworld waits below the islands.");
        msgEvacuated = m.getString("evacuated", "<gray>The winds carried you home before the overworld was recycled.");
        msgBlocked = m.getString("blocked", "<red>You can't do that down here. The overworld is recycled every week to contain the corruption.");
        state = new RecycleState(new File(plugin.getDataFolder(), "recycle.yml"), plugin.getLogger());
    }

    public boolean enabled() { return enabled; }

    /**
     * Loads or creates the current managed overworld. Call in onEnable before anything reads
     * the overworld name. Returns the world's name, or null if recycling is off or failed.
     */
    public String setup() {
        if (!enabled) return null;
        if (!state.load()) {
            state.cycle = 1;
            state.current = base + "_1";
            state.managed.clear();
            state.managed.add(state.current);
            state.nextReset = System.currentTimeMillis() + intervalMillis;
        }
        if (!state.managed.contains(state.current)) state.managed.add(state.current);
        World w = Bukkit.getWorld(state.current);
        if (w == null) w = create(state.current);
        if (w == null) {
            plugin.getLogger().severe("Could not load or create the recycled overworld '" + state.current + "'. Recycling is off.");
            return null;
        }
        state.save();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkSchedule, 200L, 200L);
        // Leftovers from a recycle that couldn't finish deleting (server stopped, file locks).
        Bukkit.getScheduler().runTaskLater(plugin, this::cleanupLeftovers, 100L);
        plugin.getLogger().info("Overworld recycling on: current world '" + state.current + "', next recycle in "
                + duration((state.nextReset - System.currentTimeMillis()) / 1000) + ".");
        return state.current;
    }

    private World create(String name) {
        return new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .seed(ThreadLocalRandom.current().nextLong())
                .createWorld();
    }

    // ---- schedule & countdown ----------------------------------------------------------

    private void checkSchedule() {
        if (countdown != null || resetting) return;
        long left = (state.nextReset - System.currentTimeMillis()) / 1000;
        int first = warnings.first();
        if (left <= first) startCountdown((int) Math.max(Math.min(60, first), left));
    }

    /** Starts (or restarts) the countdown. Returns false if a recycle is already running. */
    public boolean startCountdown(int seconds) {
        if (!enabled || resetting) return false;
        cancelCountdown(false);
        secondsLeft = Math.max(1, seconds);
        announce(secondsLeft);
        countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            secondsLeft--;
            if (secondsLeft <= 0) {
                cancelCountdown(false);
                recycle();
            } else if (warnings.contains(secondsLeft)) {
                announce(secondsLeft);
            }
        }, 20L, 20L);
        return true;
    }

    /** Stops a pending countdown. If it was the scheduled weekly one, the next one is pushed back a full interval. */
    public boolean cancelCountdown(boolean byAdmin) {
        if (countdown == null) return false;
        countdown.cancel();
        countdown = null;
        secondsLeft = -1;
        if (byAdmin && state.nextReset <= System.currentTimeMillis() + warnings.first() * 1000L) {
            state.nextReset = System.currentTimeMillis() + intervalMillis;
            state.save();
        }
        return true;
    }

    private void announce(int seconds) {
        Bukkit.getServer().sendMessage(Msg.c(msgWarning.replace("{time}", duration(seconds))));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getName().equals(state.current)) p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 0.8f, 0.6f);
        }
    }

    // ---- the recycle itself ---------------------------------------------------------------

    private void recycle() {
        if (resetting) return;
        resetting = true;
        Bukkit.getServer().sendMessage(Msg.c(msgStart));
        String oldName = state.current;
        World old = Bukkit.getWorld(oldName);
        if (old != null) evacuate(old);

        int nextCycle = state.cycle + 1;
        String newName = base + "_" + nextCycle;
        World fresh = Bukkit.getWorld(newName);
        if (fresh == null) fresh = create(newName);
        if (fresh == null) {
            plugin.getLogger().severe("Recycle failed: could not create '" + newName + "'. The old overworld stays.");
            Bukkit.getServer().sendMessage(Msg.c("<red>The overworld recycle failed. An admin has been told."));
            resetting = false;
            state.nextReset = System.currentTimeMillis() + 60 * 60 * 1000L; // try again in an hour
            state.save();
            return;
        }
        state.cycle = nextCycle;
        state.current = newName;
        if (!state.managed.contains(newName)) state.managed.add(newName);
        state.nextReset = System.currentTimeMillis() + intervalMillis;
        state.save();
        plugin.setOverworldName(newName);

        if (old != null) unloadThenDelete(oldName, 0);
        else deleteLater(oldName, 0);
        Bukkit.getServer().sendMessage(Msg.c(msgDone));
        resetting = false;
    }

    private void evacuate(World old) {
        World main = Bukkit.getWorlds().get(0);
        for (Player p : new ArrayList<>(old.getPlayers())) {
            Island i = plugin.islands().of(p.getUniqueId());
            Location dest = i != null ? i.home(plugin.skyWorld()) : main.getSpawnLocation();
            p.leaveVehicle();
            p.teleport(dest);
            p.setFallDistance(0);
            Msg.send(p, msgEvacuated);
        }
    }

    private void unloadThenDelete(String name, int attempt) {
        World w = Bukkit.getWorld(name);
        if (w != null) {
            evacuate(w); // anyone who slipped in since
            if (!Bukkit.unloadWorld(w, false)) {
                if (attempt < DELETE_TRIES) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> unloadThenDelete(name, attempt + 1), DELETE_RETRY_TICKS);
                } else {
                    plugin.getLogger().warning("Could not unload old overworld '" + name + "'. It will be deleted at next startup.");
                }
                return;
            }
        }
        deleteLater(name, 0);
    }

    /** Deletes a managed world folder off the main thread, retrying while Windows holds file locks. */
    private void deleteLater(String name, int attempt) {
        if (!safeToDelete(name)) return;
        File folder = new File(Bukkit.getWorldContainer(), name);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            boolean ok = deleteTree(folder.toPath());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) {
                    state.managed.remove(name);
                    state.save();
                    plugin.getLogger().info("Deleted old overworld '" + name + "'.");
                } else if (attempt + 1 < DELETE_TRIES) {
                    deleteLater(name, attempt + 1);
                } else {
                    plugin.getLogger().warning("Could not delete '" + name + "' yet (files in use?). Will retry at next startup.");
                }
            });
        }, attempt == 0 ? 20L : DELETE_RETRY_TICKS);
    }

    private void cleanupLeftovers() {
        for (String name : new ArrayList<>(state.managed)) {
            if (name.equals(state.current)) continue;
            if (Bukkit.getWorld(name) != null) unloadThenDelete(name, 0);
            else if (new File(Bukkit.getWorldContainer(), name).exists()) deleteLater(name, 0);
            else { state.managed.remove(name); state.save(); }
        }
    }

    /** Every guard must pass before a folder is deleted. */
    private boolean safeToDelete(String name) {
        if (name == null || !managedName.matcher(name).matches()) return false;
        if (!state.managed.contains(name) || name.equals(state.current)) return false;
        if (name.equals(Bukkit.getWorlds().get(0).getName())) return false;
        if (name.equals(plugin.skyWorld().getName())) return false;
        if (Bukkit.getWorld(name) != null) return false;
        try {
            Path container = Bukkit.getWorldContainer().getCanonicalFile().toPath();
            Path folder = new File(Bukkit.getWorldContainer(), name).getCanonicalFile().toPath();
            if (!container.equals(folder.getParent())) return false;
            // A real world folder, not something that happens to share the name.
            return Files.isDirectory(folder) && Files.exists(folder.resolve("level.dat"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean deleteTree(Path root) {
        if (!Files.exists(root)) return true;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
            // Keep level.dat until last, so a half-finished delete still passes safeToDelete on retry.
            Path marker = root.resolve("level.dat");
            if (paths.remove(marker)) paths.add(paths.size() - 1, marker); // just before the root folder itself
            boolean ok = true;
            for (Path p : paths) {
                try { Files.deleteIfExists(p); } catch (IOException e) { ok = false; }
            }
            return ok && !Files.exists(root);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- blocked commands ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!p.getWorld().getName().equals(state.current) || p.hasPermission("skyrealms.admin.bypass")) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (blockedCommands.contains(label)) {
            e.setCancelled(true);
            Msg.error(p, msgBlocked);
        }
    }

    // ---- status ------------------------------------------------------------------------------

    public String currentWorld() { return state.current; }
    public int cycle() { return state.cycle; }
    public long secondsUntilNext() { return Math.max(0, (state.nextReset - System.currentTimeMillis()) / 1000); }
    public int countdownSeconds() { return secondsLeft; }
    public int longestWarning() { return warnings.first(); }

    public static String duration(long seconds) {
        if (seconds < 60) return seconds + "s";
        long d = seconds / 86400, h = seconds % 86400 / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append("d ");
        if (h > 0) b.append(h).append("h ");
        if (m > 0) b.append(m).append("m ");
        if (s > 0 && d == 0) b.append(s).append("s");
        return b.toString().trim();
    }
}
