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
package dev.skyrealms.island;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.util.Msg;
import org.bukkit.*;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Auto-prune: islands whose owner and co-owners have all been away for {@code inactive-days}
 * are archived to plugins/SkyRealms/archives/&lt;owner-uuid&gt;/ and removed from the world.
 *
 * The archive keeps every block, chest/sign contents and entities (one structure file per chunk),
 * plus the island's settings. It can be restored to the same spot for {@code restore-days},
 * by the owner from /skyland or by an admin. After that it's deleted.
 */
public final class IslandArchiver implements Listener {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private final SkyRealmsPlugin plugin;
    private final File root;
    private final boolean enabled;
    private final int inactiveDays, restoreDays;
    private final Deque<Runnable> work = new ArrayDeque<>();
    private boolean busy;

    public IslandArchiver(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "archives");
        this.enabled = cfg == null || cfg.getBoolean("enabled", true);
        this.inactiveDays = cfg == null ? 30 : Math.max(1, cfg.getInt("inactive-days", 30));
        this.restoreDays = cfg == null ? 14 : Math.max(1, cfg.getInt("restore-days", 14));
        long period = Math.max(1, cfg == null ? 6 : cfg.getInt("check-every-hours", 6)) * 72000L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L * 300, period);
    }

    public int inactiveDays() { return inactiveDays; }

    public int restoreDays() { return restoreDays; }

    // =========================================================================================
    //  Who's inactive
    // =========================================================================================

    /** Last time any manager of the island was seen, or 0 if unknown. */
    public long lastSeen(Island i) {
        long last = 0;
        for (UUID id : i.managers()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            if (op.isOnline()) return System.currentTimeMillis();
            last = Math.max(last, op.getLastSeen());
        }
        return last;
    }

    public List<Island> candidates() {
        long cutoff = System.currentTimeMillis() - inactiveDays * DAY;
        List<Island> out = new ArrayList<>();
        for (Island i : plugin.islands().all()) {
            if (i.pruneExempt) continue;
            long seen = lastSeen(i);
            if (seen > 0 && seen < cutoff) out.add(i); // 0 = never joined (e.g. admin-given): leave alone
        }
        return out;
    }

    /** Scheduled check: archive inactive islands and delete expired archives. */
    public void check() {
        deleteExpired();
        if (!enabled) return;
        for (Island i : candidates()) {
            plugin.getLogger().info("Auto-prune: archiving the island of " + name(i.owner)
                    + " (inactive " + inactiveDays + "+ days).");
            archive(i, null);
        }
    }

    // =========================================================================================
    //  Archive
    // =========================================================================================

    /** Save the island to disk, then remove it from the world. done gets true on success. */
    public void archive(Island island, Consumer<Boolean> done) {
        queue(() -> {
            World w = plugin.skyWorld();
            File dir = dir(island.owner);
            if (dir.exists()) deleteDir(dir);
            dir.mkdirs();
            List<int[]> chunks = new ArrayList<>();
            int half = island.half() + 48;
            for (int cx = (island.centerX - half) >> 4; cx <= (island.centerX + half) >> 4; cx++)
                for (int cz = (island.centerZ - half) >> 4; cz <= (island.centerZ + half) >> 4; cz++)
                    chunks.add(new int[]{cx, cz});

            // Everyone standing on it goes to the overworld spawn first.
            Location safe = safeSpot();
            for (Player p : w.getPlayers()) if (island.containsLand(p.getX(), p.getZ())) p.teleportAsync(safe);

            List<String> saved = new ArrayList<>();
            Iterator<int[]> it = chunks.iterator();
            StructureManager sm = Bukkit.getStructureManager();
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                try {
                    for (int n = 0; n < 2 && it.hasNext(); n++) {
                        int[] c = it.next();
                        Chunk chunk = w.getChunkAt(c[0], c[1]);
                        int[] range = verticalRange(chunk);
                        if (range == null) continue;
                        Structure s = sm.createStructure();
                        s.fill(new Location(w, c[0] << 4, range[0], c[1] << 4),
                                new BlockVector(16, range[1] - range[0] + 1, 16), true);
                        String file = "c_" + c[0] + "_" + c[1] + "_" + range[0] + ".nbt";
                        sm.saveStructure(new File(dir, file), s);
                        saved.add(file);
                    }
                    if (it.hasNext()) return;
                    task.cancel();
                    YamlConfiguration meta = new YamlConfiguration();
                    meta.set("owner", island.owner.toString());
                    meta.set("archived-at", System.currentTimeMillis());
                    meta.set("chunks", saved);
                    IslandManager.write(meta.createSection("island"), island);
                    meta.save(new File(dir, "meta.yml"));
                } catch (IOException | RuntimeException e) {
                    task.cancel();
                    plugin.getLogger().severe("Archiving " + name(island.owner) + "'s island failed, leaving it in place: " + e);
                    deleteDir(dir);
                    finish(done, false);
                    return;
                }
                // Saved safely: now remove it from the world.
                plugin.islands().remove(island);
                plugin.islands().save();
                plugin.clearer().clear(w, island.centerX, island.centerZ, island.half() + 48);
                for (UUID id : island.managers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) Msg.send(p, "Your island has been archived. You can restore it from /skyland for "
                            + restoreDays + " days.");
                }
                finish(done, true);
            }, 1L, 1L);
        });
    }

    /** Lowest and highest non-air Y in a chunk, or null if empty. */
    private int[] verticalRange(Chunk chunk) {
        ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
        World w = chunk.getWorld();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = Math.min(w.getMaxHeight() - 1, snap.getHighestBlockYAt(x, z) + 8);
            for (int y = top; y >= w.getMinHeight(); y--) {
                if (snap.getBlockType(x, y, z).isAir()) continue;
                lo = Math.min(lo, y); hi = Math.max(hi, y);
            }
        }
        return lo == Integer.MAX_VALUE ? null : new int[]{lo, hi};
    }

    // =========================================================================================
    //  Restore
    // =========================================================================================

    public record ArchiveInfo(UUID owner, long archivedAt, long expiresAt) {
        public long daysLeft() { return Math.max(0, (expiresAt - System.currentTimeMillis() + DAY - 1) / DAY); }
    }

    public ArchiveInfo info(UUID owner) {
        File meta = new File(dir(owner), "meta.yml");
        if (!meta.exists()) return null;
        long at = YamlConfiguration.loadConfiguration(meta).getLong("archived-at", 0);
        long exp = at + restoreDays * DAY;
        return System.currentTimeMillis() > exp ? null : new ArchiveInfo(owner, at, exp);
    }

    public List<ArchiveInfo> list() {
        List<ArchiveInfo> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            try {
                ArchiveInfo a = info(UUID.fromString(d.getName()));
                if (a != null) out.add(a);
            } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    /** Put an archived island back exactly where it was. done gets a message key: ok / none / has-island / failed. */
    public void restore(UUID owner, Consumer<String> done) {
        queue(() -> {
            ArchiveInfo info = info(owner);
            if (info == null) { finishMsg(done, "none"); return; }
            if (plugin.islands().of(owner) != null) { finishMsg(done, "has-island"); return; }
            File dir = dir(owner);
            YamlConfiguration meta = YamlConfiguration.loadConfiguration(new File(dir, "meta.yml"));
            Island island = IslandManager.read(owner, meta.getConfigurationSection("island"));
            // Drop co-owners who meanwhile own or co-own another island.
            island.coOwners.removeIf(c -> plugin.islands().of(c) != null);
            World w = plugin.skyWorld();
            StructureManager sm = Bukkit.getStructureManager();
            Iterator<String> it = meta.getStringList("chunks").iterator();
            Random rng = new Random();
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                try {
                    for (int n = 0; n < 2 && it.hasNext(); n++) {
                        String f = it.next();
                        String[] parts = f.replace(".nbt", "").split("_");
                        int cx = Integer.parseInt(parts[1]), cz = Integer.parseInt(parts[2]), y = Integer.parseInt(parts[3]);
                        Structure s = sm.loadStructure(new File(dir, f));
                        s.place(new Location(w, cx << 4, y, cz << 4), true, StructureRotation.NONE, Mirror.NONE, 0, 1f, rng);
                    }
                    if (it.hasNext()) return;
                } catch (IOException | RuntimeException e) {
                    task.cancel();
                    plugin.getLogger().severe("Restoring " + name(owner) + "'s island failed: " + e);
                    finishMsg(done, "failed");
                    return;
                }
                task.cancel();
                plugin.islands().restore(island);
                plugin.islands().save();
                deleteDir(dir);
                finishMsg(done, "ok");
            }, 1L, 1L);
        });
    }

    // =========================================================================================
    //  Housekeeping
    // =========================================================================================

    public void deleteExpired() {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            File meta = new File(d, "meta.yml");
            long at = meta.exists() ? YamlConfiguration.loadConfiguration(meta).getLong("archived-at", 0) : 0;
            if (at == 0 || System.currentTimeMillis() > at + restoreDays * DAY) {
                plugin.getLogger().info("Deleting expired island archive " + d.getName());
                // The island is gone, but the player's level, unlocks and styles carry over to their next island.
                try {
                    UUID owner = UUID.fromString(d.getName());
                    var sec = meta.exists() ? YamlConfiguration.loadConfiguration(meta).getConfigurationSection("island") : null;
                    if (sec != null && plugin.islands().of(owner) == null) plugin.progress().save(owner, IslandManager.read(owner, sec));
                } catch (IllegalArgumentException ignored) {}
                deleteDir(d);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ArchiveInfo a = info(e.getPlayer().getUniqueId());
        if (a == null || plugin.islands().of(e.getPlayer().getUniqueId()) != null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> Msg.send(e.getPlayer(),
                "Your island was archived while you were away. Open <white>/skyland</white> to restore it ("
                        + a.daysLeft() + " days left)."), 60L);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void queue(Runnable r) {
        work.add(r);
        if (!busy) next();
    }

    private void next() {
        Runnable r = work.poll();
        busy = r != null;
        if (r != null) r.run();
    }

    private void finish(Consumer<Boolean> done, boolean ok) {
        if (done != null) done.accept(ok);
        next();
    }

    private void finishMsg(Consumer<String> done, String msg) {
        if (done != null) done.accept(msg);
        next();
    }

    private File dir(UUID owner) { return new File(root, owner.toString()); }

    private Location safeSpot() {
        World ground = Bukkit.getWorld(plugin.getConfig().getString("worlds.overworld", "world"));
        return (ground != null ? ground : Bukkit.getWorlds().get(0)).getSpawnLocation();
    }

    private static String name(UUID id) { return Objects.toString(Bukkit.getOfflinePlayer(id).getName(), id.toString()); }

    private static void deleteDir(File d) {
        File[] files = d.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        d.delete();
    }
}
