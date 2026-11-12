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

    public boolean buy(Player p, Tier tier, String biome) {
        if (plugin.islands().of(p.getUniqueId()) != null) { Msg.error(p, "You already have an island. Upgrade it instead."); return false; }
        if (!plugin.biomes().startable(biome)) { Msg.error(p, "You can't start an island with that biome."); return false; }
        if (!plugin.money().usable()) { Msg.error(p, "The economy isn't set up yet."); return false; }
        if (!plugin.money().charge(p, tier.price)) { Msg.error(p, "You need <white>" + money(tier.price) + "</white> for a " + tier.pretty() + " island."); return false; }

        Island island = plugin.islands().create(p.getUniqueId(), tier);
        Msg.send(p, "Raising your <white>" + tier.pretty() + "</white> <green>" + plugin.biomes().pretty(biome)
                + "</green> island from the clouds...");
        plugin.grower().growMany(plugin.skyWorld(), island, biome, tier.startPieces, () -> {
            if (!p.isOnline()) return;
            p.teleportAsync(island.home(plugin.skyWorld(), plugin.baseY()));
            Msg.send(p, "Welcome home! Jump off the edge to drop into the overworld.");
        });
        plugin.islands().save();
        return true;
    }

    public boolean upgrade(Player p) {
        Island i = owned(p);
        if (i == null) return false;
        Tier next = i.tier.next();
        if (next == null) { Msg.error(p, "You're already Continental. There's nowhere bigger."); return false; }
        double cost = upgradeCost(i);
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Upgrading to " + next.pretty() + " costs <white>" + money(cost)); return false; }
        int bonus = Math.max(0, next.startPieces - i.tier.startPieces);
        i.tier = next;
        List<String> owned = new ArrayList<>(i.biomes);
        int grown = 0;
        for (int n = 0; n < bonus; n++) {
            if (plugin.grower().grow(plugin.skyWorld(), i, owned.get(rng.nextInt(owned.size())), null) != null) grown++;
        }
        plugin.islands().save();
        Msg.send(p, "Your island is now <white>" + next.pretty() + "</white> (" + next.footprint + "x" + next.footprint + ")."
                + (grown > 0 ? " " + grown + " new pieces of land are rising." : ""));
        return true;
    }

    public boolean grow(Player p, String biome) {
        Island i = owned(p);
        if (i == null) return false;
        if (i.pieces.size() >= i.tier.maxPieces) { Msg.error(p, "Your " + i.tier.pretty() + " island is full. Upgrade for more room."); return false; }
        if (biome == null) biome = new ArrayList<>(i.biomes).get(rng.nextInt(i.biomes.size()));
        if (!i.biomes.contains(biome)) { Msg.error(p, "You don't own <white>" + biome + "</white> yet. Unlock it first."); return false; }
        double cost = growPrice();
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Growing costs <white>" + money(cost)); return false; }
        String b = biome;
        if (plugin.grower().grow(plugin.skyWorld(), i, biome, () -> Msg.send(p, "New <green>" + b + "</green> land has attached to your island.")) == null) {
            plugin.money().refund(p, cost);
            Msg.error(p, "There's no room left inside your border. Upgrade to grow further.");
            return false;
        }
        plugin.islands().save();
        return true;
    }

    public boolean unlockBiome(Player p, String biome) {
        Island i = owned(p);
        if (i == null) return false;
        if (!plugin.biomes().exists(biome)) { Msg.error(p, "That biome isn't available."); return false; }
        if (i.biomes.contains(biome)) { Msg.error(p, "You already own that biome. Grow it instead."); return false; }
        int slots = i.tier.maxPieces - i.pieces.size();
        if (slots <= 0) { Msg.error(p, "Your island is full. Upgrade first."); return false; }
        double cost = unlockPrice(biome);
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Unlocking <white>" + biome + "</white> costs <white>" + money(cost)); return false; }
        int want = Math.min(slots, Math.max(1, plugin.getConfig().getInt("prices.biome-region-pieces", 2)));
        String name = plugin.biomes().pretty(biome);
        if (plugin.grower().growMany(plugin.skyWorld(), i, biome, want,
                () -> Msg.send(p, "A <green>" + name + "</green> region has attached to the edge of your island!")).isEmpty()) {
            plugin.money().refund(p, cost);
            Msg.error(p, "There's no room on your island's edge. Upgrade first.");
            return false;
        }
        plugin.islands().save();
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
        p.teleportAsync(i.home(plugin.skyWorld(), plugin.baseY())).thenRun(() -> p.setFallDistance(0));
        return true;
    }

    /** "Return to Island": a gust throws you upward, then the wind carries you home. */
    public void launchHome(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) { Msg.error(p, "You don't have an island to fly to yet."); return; }
        if (p.getWorld().equals(plugin.skyWorld())) { home(p); return; }
        long left = cooldownLeft(p);
        if (left > 0) { p.sendActionBar(Msg.c("<red>The wind needs " + left + "s to gather again.")); return; }
        homeCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        p.leaveVehicle();
        int ticks = Math.max(5, plugin.getConfig().getInt("return.launch-ticks", 20));
        p.setVelocity(p.getVelocity().setY(plugin.getConfig().getDouble("return.launch-power", 1.8)));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f);
        p.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, p.getLocation(), 1);
        int[] age = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline()) { task.cancel(); return; }
            if (age[0]++ * 2 < ticks) {
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 6, 0.3, 0.1, 0.3, 0.02);
                return;
            }
            task.cancel();
            p.teleportAsync(i.home(plugin.skyWorld(), plugin.baseY())).thenRun(() -> {
                p.setFallDistance(0);
                p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.4f);
            });
        }, 1L, 2L);
    }

    public boolean visit(Player p, OfflinePlayer owner) {
        Island i = plugin.islands().of(owner.getUniqueId());
        if (i == null) { Msg.error(p, owner.getName() + " doesn't have an island."); return false; }
        if (!i.setting(Flag.VISITS) && i.roleOf(p.getUniqueId()) == Role.VISITOR && !p.hasPermission("skyrealms.bypass.closed")) {
            Msg.error(p, owner.getName() + "'s island is closed to visitors.");
            return false;
        }
        p.leaveVehicle();
        p.teleportAsync(i.home(plugin.skyWorld(), plugin.baseY()));
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
        Msg.send(p, "Home set.");
        return true;
    }

    // ---- friends & flags -------------------------------------------------------------

    public boolean addFriend(Player p, OfflinePlayer target) {
        Island i = owned(p);
        if (i == null) return false;
        if (target.getUniqueId().equals(p.getUniqueId())) { Msg.error(p, "That's you."); return false; }
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
        Island i = owned(p);
        if (i == null) return false;
        World ground = Bukkit.getWorld(plugin.getConfig().getString("worlds.overworld", "world"));
        Location safe = ground != null ? ground.getSpawnLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
        for (Player other : plugin.skyWorld().getPlayers()) {
            if (i.containsLand(other.getX(), other.getZ())) {
                other.teleportAsync(safe);
                if (!other.equals(p)) Msg.send(other, "The island you were on has been removed by its owner.");
            }
        }
        if (p.getWorld().equals(plugin.skyWorld()) && i.containsLand(p.getX(), p.getZ())) p.teleportAsync(safe);
        plugin.islands().remove(i);
        plugin.islands().save();
        plugin.clearer().clear(plugin.skyWorld(), i.centerX, i.centerZ, i.half() + 48);
        double refund = deleteRefund(i);
        plugin.money().refund(p, refund);
        Msg.send(p, "Your island crumbles into the clouds." + (refund > 0 ? " You got <gold>" + money(refund) + "</gold> back." : "")
                + " You can buy a new one any time.");
        return true;
    }

    public Island owned(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) Msg.error(p, "You don't have an island yet.");
        return i;
    }
}
