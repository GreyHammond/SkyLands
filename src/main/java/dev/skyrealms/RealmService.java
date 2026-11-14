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
package dev.skyrealms;

import dev.skyrealms.island.Flag;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Role;
import dev.skyrealms.island.Tier;
import dev.skyrealms.util.Msg;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.util.*;

/** Every player action lives here so chest menus and commands behave identically. */
public final class RealmService {
    private final SkyRealmsPlugin plugin;
    private final Map<UUID, Long> homeCooldowns = new HashMap<>();
    private final Random rng = new Random();

    public RealmService(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    // ---- prices ---------------------------------------------------------------

    public double growPrice() { return plugin.getConfig().getDouble("prices.grow-piece", 1000); }

    public double unlockPrice(String biome) {
        return plugin.getConfig().getDouble("prices.biome-unlock." + biome, plugin.getConfig().getDouble("prices.biome-unlock-default", 10000));
    }

    public double upgradeCost(Island i) {
        Tier next = i.tier.next();
        return next == null ? 0 : Math.max(0, next.price - i.tier.price);
    }

    public String money(double amount) { return plugin.money().format(amount); }

    // ---- buying & growing -------------------------------------------------------

    public boolean buy(Player p, Tier tier, String biome) { return buy(p, tier, biome, null); }

    /** Underside styles a player may pick for a new island (unlocked at their starting level). */
    public java.util.List<dev.skyrealms.terrain.UndersideStyle> startUndersides(java.util.UUID player) {
        int lv = startLevel(player);
        return java.util.Arrays.stream(dev.skyrealms.terrain.UndersideStyle.values())
                .filter(st -> plugin.xp().levels().undersideLevel(st.id) <= lv).toList();
    }

    /** Themes a player may pick for a new island. */
    public java.util.List<dev.skyrealms.cosmetic.Cosmetics.Theme> startThemes(java.util.UUID player) {
        int lv = startLevel(player);
        return java.util.Arrays.stream(dev.skyrealms.cosmetic.Cosmetics.Theme.values())
                .filter(t -> plugin.cosmetics().need(t) <= lv).toList();
    }

    /**
     * Buy an island with a chosen look: {@code style} is "theme:&lt;id&gt;", "underside:&lt;id&gt;", or null to keep
     * the saved (or default) style.
     */
    public boolean buy(Player p, Tier tier, String biome, String style) {
        Island existing = plugin.islands().of(p.getUniqueId());
        if (existing != null) {
            Msg.error(p, existing.owner.equals(p.getUniqueId()) ? "You already have an island. Upgrade it instead."
                    : "You're a co-owner of " + name(existing.owner) + "'s island. Leave it first to buy your own.");
            return false;
        }
        if (!plugin.biomes().startable(biome)) { Msg.error(p, "You can't start an island with that biome."); return false; }
        int startLevel = startLevel(p.getUniqueId());
        if (plugin.xp().levels().biomeLevel(biome) > startLevel) {
            Msg.error(p, "That biome unlocks at island level " + plugin.xp().levels().biomeLevel(biome) + ". Your new island starts at level "
                    + startLevel + ".");
            return false;
        }
        if (!plugin.money().usable()) { Msg.error(p, "The economy isn't set up yet."); return false; }
        if (!plugin.money().charge(p, tier.price)) { Msg.error(p, "You need <white>" + money(tier.price) + "</white> for a " + tier.pretty() + " island."); return false; }

        Island island = plugin.islands().create(p.getUniqueId(), tier);
        Msg.send(p, "Raising your <white>" + tier.pretty() + "</white> <green>" + plugin.biomes().pretty(biome)
                + "</green> island from the clouds...");
        island.xpBackfilled = true;
        // Bring back the level, unlocked biomes, underside style and cosmetics from a deleted island.
        boolean restored = plugin.progress().apply(p.getUniqueId(), island);
        if (style != null) {
            int lv = Math.max(startLevel, plugin.xp().level(island));
            if (style.startsWith("theme:")) {
                var t = dev.skyrealms.cosmetic.Cosmetics.Theme.byId(style.substring(6));
                if (t != null && plugin.cosmetics().need(t) <= lv) {
                    island.theme = t.id; island.underside = t.underside; island.particles = t.particles.id; island.border = t.border.id;
                }
            } else if (style.startsWith("underside:")) {
                var st = dev.skyrealms.terrain.UndersideStyle.byId(style.substring(10));
                if (plugin.xp().levels().undersideLevel(st.id) <= lv) { island.underside = st.id; island.theme = null; }
            }
        }
        if (restored) Msg.send(p, "Your progress came with you: <gold>" + plugin.xp().badge(island) + "</gold>, "
                + island.biomes.size() + " biomes unlocked, " + dev.skyrealms.terrain.UndersideStyle.byId(island.underside).displayName
                + " underside.");
        int placed = plugin.grower().growMany(plugin.skyWorld(), island, biome, tier.startPieces, () -> {
            if (!p.isOnline()) return;
            p.teleportAsync(island.home(plugin.skyWorld()));
            Msg.send(p, "Welcome home! Jump off the edge to drop into the overworld.");
        }).size();
        plugin.xp().add(island, (long) placed * plugin.xp().levels().buyPerPiece);
        plugin.islands().save();
        return true;
    }

    /** Level a player's next island starts at: the level of the island they deleted, or 1. */
    public int startLevel(UUID player) {
        return plugin.xp().levels().levelOf(plugin.progress().xp(player));
    }

    public boolean upgrade(Player p) {
        Island i = owned(p);
        if (i == null) return false;
        Tier next = i.tier.next();
        if (next == null) { Msg.error(p, "You're already Continental. There's nowhere bigger."); return false; }
        double cost = upgradeCost(i);
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Upgrading to " + next.pretty() + " costs <white>" + money(cost)); return false; }
        // Room only: the border and piece cap grow, and players add land with Grow Land.
        i.tier = next;
        plugin.xp().add(i, plugin.xp().levels().upgradeXp(next));
        // Floating islets sat just outside the old border; move them out past the new one.
        if (!i.islets.isEmpty()) dev.skyrealms.cosmetic.Islets.apply(plugin.skyWorld(), i,
                dev.skyrealms.cosmetic.Cosmetics.DecorationSet.byId(i.decorations), i.seaY);
        plugin.islands().save();
        Msg.send(p, "Your island is now <white>" + next.pretty() + "</white> (" + next.footprint + "x" + next.footprint
                + "), with room for <white>" + next.maxPieces + "</white> pieces of land. Use <white>Grow Land</white> to fill it.");
        return true;
    }

    public boolean grow(Player p, String biome) { return grow(p, biome, null); }

    /** Where a player can ask new land to go. */
    public enum Placement {
        AUTO("Automatic", 0, 0), HERE("Toward where I'm standing", 0, 0),
        N("North", 0, -1), NE("North-East", 1, -1), E("East", 1, 0), SE("South-East", 1, 1),
        S("South", 0, 1), SW("South-West", -1, 1), W("West", -1, 0), NW("North-West", -1, -1);
        public final String label; public final int dx, dz;
        Placement(String label, int dx, int dz) { this.label = label; this.dx = dx; this.dz = dz; }
    }

    /** True if the player is standing on or just beside their island, so "toward me" makes sense. */
    public boolean canPlaceHere(Player p, Island i) {
        return p.getWorld().equals(plugin.skyWorld()) && Math.abs(p.getX() - i.centerX) <= i.half() + 24
                && Math.abs(p.getZ() - i.centerZ) <= i.half() + 24;
    }

    /** Target offset from the island centre for a placement choice (null = automatic). */
    public double[] targetFor(Player p, Island i, Placement where) {
        return switch (where) {
            case AUTO -> null;
            case HERE -> canPlaceHere(p, i) ? new double[]{p.getX() - i.centerX, p.getZ() - i.centerZ} : null;
            default -> new double[]{where.dx * i.half(), where.dz * i.half()};
        };
    }

    public boolean grow(Player p, String biome, Placement where) {
        Island i = owned(p);
        if (i == null) return false;
        if (i.pieces.size() >= i.tier.maxPieces) { Msg.error(p, "Your " + i.tier.pretty() + " island is full. Upgrade for more room."); return false; }
        if (biome == null) biome = new ArrayList<>(i.biomes).get(rng.nextInt(i.biomes.size()));
        if (!i.biomes.contains(biome)) { Msg.error(p, "You don't own <white>" + biome + "</white> yet. Unlock it first."); return false; }
        double cost = growPrice();
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Growing costs <white>" + money(cost)); return false; }
        String b = biome;
        double[] target = where == null ? null : targetFor(p, i, where);
        String whereText = where == null || where == Placement.AUTO ? "" : where == Placement.HERE ? " near where you're standing" : " on the " + where.label.toLowerCase() + " side";
        if (plugin.grower().growMany(plugin.skyWorld(), i, biome, 1, target,
                () -> Msg.send(p, "New <green>" + plugin.biomes().pretty(b) + "</green> land has attached" + whereText + ".")).isEmpty()) {
            plugin.money().refund(p, cost);
            if (target != null) {
                String side = where == Placement.HERE ? "near where you're standing" : "on the " + where.label.toLowerCase() + " side";
                Msg.error(p, "There's no room " + side + " inside your border. Try another side, choose Automatic, or upgrade."
                        + (cost > 0 ? " You weren't charged." : ""));
            } else {
                Msg.error(p, "There's no room left inside your border. Upgrade to grow further.");
            }
            return false;
        }
        plugin.xp().add(i, plugin.xp().levels().growPiece);
        plugin.islands().save();
        return true;
    }

    /** Unlocking only adds the biome to the island's list. Land is added with Grow Land. */
    public boolean unlockBiome(Player p, String biome) {
        Island i = owned(p);
        if (i == null) return false;
        if (!plugin.biomes().exists(biome)) { Msg.error(p, "That biome isn't available."); return false; }
        if (i.biomes.contains(biome)) { Msg.error(p, "You already have that biome. Use Grow Land to add more of it."); return false; }
        int need = plugin.xp().levels().biomeLevel(biome);
        if (plugin.xp().level(i) < need) {
            Msg.error(p, plugin.biomes().pretty(biome) + " unlocks at island level <white>" + need + "</white>. Your island is level "
                    + plugin.xp().level(i) + ".");
            return false;
        }
        double cost = unlockPrice(biome);
        String name = plugin.biomes().pretty(biome);
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Unlocking <white>" + name + "</white> costs <white>" + money(cost)); return false; }
        i.biomes.add(biome);
        plugin.xp().add(i, plugin.xp().levels().unlockBiome);
        plugin.islands().save();
        Msg.send(p, "<green>" + name + "</green> unlocked! Use <white>Grow Land</white> to add it to your island."
                + (i.pieces.size() >= i.tier.maxPieces ? " <yellow>(Your island is full, so upgrade first.)" : ""));
        return true;
    }

    /** Pick the underside style used for land grown from now on. */
    public boolean setUnderside(Player p, dev.skyrealms.terrain.UndersideStyle style) {
        Island i = owned(p);
        if (i == null) return false;
        int need = plugin.xp().levels().undersideLevel(style.id);
        if (plugin.xp().level(i) < need) { Msg.error(p, style.displayName + " unlocks at island level " + need + "."); return false; }
        i.underside = style.id;
        plugin.islands().save();
        Msg.send(p, "New land will now hang <aqua>" + style.displayName + "</aqua> underneath. Existing land stays as it is.");
        return true;
    }

    public double restylePrice() { return plugin.getConfig().getDouble("prices.restyle-underside", 2500); }

    /** Convert the rock under the island's existing land to {@code style}, and use it for new land too. */
    public boolean restyleUnderside(Player p, dev.skyrealms.terrain.UndersideStyle style) {
        Island i = owned(p);
        if (i == null) return false;
        int need = plugin.xp().levels().undersideLevel(style.id);
        var theme = dev.skyrealms.cosmetic.Cosmetics.Theme.byId(i.theme);
        boolean fromTheme = theme != null && theme.underside.equals(style.id);
        if (plugin.xp().level(i) < need && !fromTheme) { Msg.error(p, style.displayName + " unlocks at island level " + need + "."); return false; }
        if (plugin.restyler().busy(i)) { Msg.error(p, "Your underside is already being restyled. Give it a moment."); return false; }
        double cost = restylePrice();
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Restyling costs <white>" + money(cost)); return false; }
        i.underside = style.id;
        i.pieces.replaceAll(pc -> dev.skyrealms.island.IslandGrower.GENERATED.equals(pc.variant()) ? pc.withStyle(style.id) : pc);
        plugin.islands().save();
        Msg.send(p, "Reshaping the rock under your island into <aqua>" + style.displayName + "</aqua>...");
        plugin.restyler().restyle(plugin.skyWorld(), i, style,
                () -> { if (p.isOnline()) Msg.send(p, "Your island's underside is now <aqua>" + style.displayName + "</aqua>."); });
        return true;
    }

    // ---- travel ----------------------------------------------------------------

    /** Seconds left on the return-home cooldown, or 0. */
    public long cooldownLeft(Player p) {
        if (p.hasPermission("skyrealms.bypass.cooldown")) return 0;
        long cd = plugin.getConfig().getLong("transit.home-cooldown", 10) * 1000L;
        Long last = homeCooldowns.get(p.getUniqueId());
        long left = last == null ? 0 : cd - (System.currentTimeMillis() - last);
        return left <= 0 ? 0 : left / 1000 + 1;
    }

    public boolean home(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) { Msg.error(p, "You don't have an island yet."); return false; }
        long left = cooldownLeft(p);
        if (left > 0) { Msg.error(p, "Wait " + left + "s before going home again."); return false; }
        homeCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        p.leaveVehicle();
        p.teleportAsync(i.home(plugin.skyWorld())).thenRun(() -> p.setFallDistance(0));
        return true;
    }

    /** "Return to Island": a gust throws you upward, then the wind carries you home. */
    public void launchHome(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) { Msg.error(p, "You don't have an island to fly to yet."); return; }
        long left = cooldownLeft(p);
        if (left > 0) { p.sendActionBar(Msg.c("<red>The wind needs " + left + "s to gather again.")); return; }
        homeCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        p.leaveVehicle();
        int ticks = Math.max(5, plugin.getConfig().getInt("return.launch-ticks", 20));
        double power = Math.max(0.5, plugin.getConfig().getDouble("return.launch-power", 2.2));
        p.setVelocity(p.getVelocity().setY(power));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f);
        p.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, p.getLocation(), 1);
        int[] age = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || p.isDead()) { task.cancel(); return; }
            if (++age[0] < ticks) {
                // The gust keeps pushing, so you're still rising (never falling) when the wind takes you home.
                org.bukkit.util.Vector v = p.getVelocity();
                if (v.getY() < power) p.setVelocity(v.setY(power));
                p.setFallDistance(0);
                if (age[0] % 2 == 0) p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 6, 0.3, 0.1, 0.3, 0.02);
                return;
            }
            task.cancel();
            p.setVelocity(new org.bukkit.util.Vector());
            p.teleportAsync(i.home(plugin.skyWorld())).thenRun(() -> {
                p.setFallDistance(0);
                p.setVelocity(new org.bukkit.util.Vector());
            });
        }, 1L, 1L);
    }

    public boolean visit(Player p, OfflinePlayer owner) {
        Island i = plugin.islands().of(owner.getUniqueId());
        if (i == null) { Msg.error(p, owner.getName() + " doesn't have an island."); return false; }
        if (!i.setting(Flag.VISITS) && i.roleOf(p.getUniqueId()) == Role.VISITOR && !p.hasPermission("skyrealms.bypass.closed")) {
            Msg.error(p, owner.getName() + "'s island is closed to visitors.");
            return false;
        }
        p.leaveVehicle();
        p.teleportAsync(i.home(plugin.skyWorld())).thenAccept(ok -> {
            if (ok && plugin.tips() != null) plugin.tips().startVisit(p, i);
        });
        Msg.send(p, "Visiting " + owner.getName() + "'s island.");
        return true;
    }

    public boolean setHome(Player p) {
        Island i = owned(p);
        if (i == null) return false;
        Location loc = p.getLocation();
        if (!loc.getWorld().equals(plugin.skyWorld()) || !i.containsLand(loc.getX(), loc.getZ())) {
            Msg.error(p, "Stand on your island first."); return false;
        }
        i.home = new double[]{loc.getX() - i.centerX, loc.getY(), loc.getZ() - i.centerZ, loc.getYaw(), loc.getPitch()};
        plugin.islands().save();
        Msg.send(p, "Home set. <gray>/ihome</gray> brings you back here.");
        return true;
    }

    /** Forget the set home, so /ihome finds safe ground near the island's centre again. */
    public boolean resetHome(Player p) {
        Island i = owned(p);
        if (i == null) return false;
        i.home = null;
        plugin.islands().save();
        Msg.send(p, "Home cleared. <gray>/ihome</gray> now takes you to safe ground near your island's centre.");
        return true;
    }

    // ---- friends & flags -------------------------------------------------------------

    public boolean addFriend(Player p, OfflinePlayer target) {
        Island i = owned(p);
        if (i == null) return false;
        if (target.getUniqueId().equals(p.getUniqueId())) { Msg.error(p, "That's you."); return false; }
        if (i.roleOf(target.getUniqueId()).manages()) { Msg.error(p, target.getName() + " already manages this island."); return false; }
        if (!i.friends.add(target.getUniqueId())) { Msg.error(p, target.getName() + " is already a friend."); return false; }
        plugin.islands().save();
        Msg.send(p, "<white>" + target.getName() + "</white> is now a friend on your island.");
        if (target.getPlayer() != null) Msg.send(target.getPlayer(), "<white>" + p.getName() + "</white> added you as a friend on their island.");
        return true;
    }

    public boolean removeFriend(Player p, OfflinePlayer target) {
        Island i = owned(p);
        if (i == null) return false;
        if (!i.friends.remove(target.getUniqueId())) { Msg.error(p, target.getName() + " isn't a friend."); return false; }
        plugin.islands().save();
        Msg.send(p, "<white>" + target.getName() + "</white> is no longer a friend.");
        return true;
    }

    public void toggle(Island i, Role role, Flag flag) {
        i.set(role, flag, !i.get(role, flag));
        plugin.islands().save();
    }

    // ---- deleting ------------------------------------------------------------------

    public double deleteRefund(Island i) {
        double spent = i.tier.price;
        return spent * Math.max(0, Math.min(100, plugin.getConfig().getDouble("prices.delete-refund-percent", 0))) / 100.0;
    }

    /** Removes the island, sends anyone standing on it to the overworld, and wipes its blocks in the background. */
    public boolean deleteIsland(Player p) {
        Island i = ownerOnly(p);
        if (i == null) return false;
        World ground = Bukkit.getWorld(plugin.overworldName());
        Location safe = ground != null ? ground.getSpawnLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
        for (Player other : plugin.skyWorld().getPlayers()) {
            if (i.containsLand(other.getX(), other.getZ())) {
                other.teleportAsync(safe);
                if (!other.equals(p)) Msg.send(other, "The island you were on has been removed by its owner.");
            }
        }
        if (p.getWorld().equals(plugin.skyWorld()) && i.containsLand(p.getX(), p.getZ())) p.teleportAsync(safe);
        plugin.progress().save(i.owner, i);   // level, unlocks and styles carry over to the next island
        for (UUID c : i.coOwners) {
            Player cp = Bukkit.getPlayer(c);
            if (cp != null && !cp.equals(p)) Msg.send(cp, name(i.owner) + " deleted the island you co-owned.");
        }
        plugin.islands().remove(i);
        plugin.islands().save();
        plugin.clearer().clear(plugin.skyWorld(), i.centerX, i.centerZ, i.half() + 48);
        double refund = deleteRefund(i);
        plugin.money().refund(p, refund);
        Msg.send(p, "Your island crumbles into the clouds." + (refund > 0 ? " You got <gold>" + money(refund) + "</gold> back." : "")
                + " Your <gold>" + plugin.xp().badge(i) + "</gold> level, unlocked biomes and styles are saved for your next island.");
        return true;
    }

    /** The island this player can manage (owner or co-owner), with an error message if none. */
    public Island owned(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) Msg.error(p, "You don't have an island yet.");
        return i;
    }

    /** The island this player owns, for owner-only actions (delete, transfer, co-owners). */
    public Island ownerOnly(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) { Msg.error(p, "You don't have an island yet."); return null; }
        if (!i.owner.equals(p.getUniqueId())) { Msg.error(p, "Only the island owner, " + name(i.owner) + ", can do that."); return null; }
        return i;
    }

    // ---- co-owners & transfer ------------------------------------------------------------

    public boolean addCoOwner(Player p, OfflinePlayer target) {
        Island i = ownerOnly(p);
        if (i == null) return false;
        UUID t = target.getUniqueId();
        if (t.equals(p.getUniqueId())) { Msg.error(p, "That's you."); return false; }
        Island theirs = plugin.islands().of(t);
        if (theirs == i) { Msg.error(p, target.getName() + " is already a co-owner."); return false; }
        if (theirs != null) { Msg.error(p, target.getName() + " already owns or co-owns another island."); return false; }
        plugin.islands().addCoOwner(i, t);
        plugin.islands().save();
        Msg.send(p, "<white>" + target.getName() + "</white> is now a co-owner of your island.");
        if (target.getPlayer() != null) Msg.send(target.getPlayer(), "<white>" + p.getName()
                + "</white> made you a co-owner of their island. Open /skyland to manage it.");
        return true;
    }

    public boolean removeCoOwner(Player p, OfflinePlayer target) {
        Island i = ownerOnly(p);
        if (i == null) return false;
        if (!i.coOwners.contains(target.getUniqueId())) { Msg.error(p, target.getName() + " isn't a co-owner."); return false; }
        plugin.islands().removeCoOwner(i, target.getUniqueId());
        plugin.islands().save();
        Msg.send(p, "<white>" + target.getName() + "</white> is no longer a co-owner.");
        if (target.getPlayer() != null) Msg.send(target.getPlayer(), "You're no longer a co-owner of " + p.getName() + "'s island.");
        return true;
    }

    /** A co-owner walks away from the island they co-own. */
    public boolean leaveIsland(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null || !i.coOwners.contains(p.getUniqueId())) { Msg.error(p, "You're not a co-owner of an island."); return false; }
        plugin.islands().removeCoOwner(i, p.getUniqueId());
        plugin.islands().save();
        Msg.send(p, "You left " + name(i.owner) + "'s island.");
        Player owner = Bukkit.getPlayer(i.owner);
        if (owner != null) Msg.send(owner, p.getName() + " stopped co-owning your island.");
        return true;
    }

    /** Give the island away. The old owner immediately becomes a visitor. */
    public boolean transfer(Player p, OfflinePlayer target) {
        Island i = ownerOnly(p);
        if (i == null) return false;
        UUID t = target.getUniqueId();
        if (t.equals(p.getUniqueId())) { Msg.error(p, "You already own it."); return false; }
        Island theirs = plugin.islands().of(t);
        if (theirs != null && theirs != i) { Msg.error(p, target.getName() + " already owns or co-owns another island."); return false; }
        plugin.islands().transfer(i, t);
        plugin.islands().save();
        Msg.send(p, "You gave your island to <white>" + target.getName() + "</white>. You're now a visitor there.");
        if (target.getPlayer() != null) Msg.send(target.getPlayer(), "<white>" + p.getName()
                + "</white> gave you their island! Open /skyland to manage it.");
        return true;
    }

    // ---- archives ------------------------------------------------------------------------

    public void restoreOwn(Player p) {
        Msg.send(p, "Restoring your island...");
        plugin.archiver().restore(p.getUniqueId(), result -> {
            switch (result) {
                case "ok" -> {
                    Island i = plugin.islands().of(p.getUniqueId());
                    if (i != null && p.isOnline()) p.teleportAsync(i.home(plugin.skyWorld()));
                    Msg.send(p, "Welcome back! Your island is exactly as you left it.");
                }
                case "has-island" -> Msg.error(p, "You already have an island, so the archived one can't be restored.");
                case "none" -> Msg.error(p, "There's no archived island to restore (archives last "
                        + plugin.archiver().restoreDays() + " days).");
                default -> Msg.error(p, "Restoring failed. Please tell an admin.");
            }
        });
    }

    private static String name(UUID id) {
        return Objects.toString(Bukkit.getOfflinePlayer(id).getName(), "someone");
    }
}
