package dev.skyrealms.island;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/** In the sky world, only an island's owner and trusted members can change it. */
public final class ProtectionListener implements Listener {
    private final IslandManager islands;
    private final String skyWorld;

    public ProtectionListener(IslandManager islands, String skyWorld) {
        this.islands = islands;
        this.skyWorld = skyWorld;
    }

    private boolean denied(Player p, Location loc) {
        if (loc == null || !loc.getWorld().getName().equals(skyWorld)) return false;
        if (p.hasPermission("skyrealms.admin.bypass")) return false;
        Island i = islands.at(loc.getX(), loc.getZ());
        return i == null || !i.canBuild(p.getUniqueId());
    }

    private void check(Player p, Location loc, Cancellable e) {
        if (denied(p, loc)) {
            e.setCancelled(true);
            p.sendActionBar(dev.skyrealms.util.Msg.c("<red>This isn't your island."));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) { check(e.getPlayer(), e.getBlock().getLocation(), e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) { check(e.getPlayer(), e.getBlock().getLocation(), e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) { check(e.getPlayer(), e.getBlock().getLocation(), e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketFill(PlayerBucketFillEvent e) { check(e.getPlayer(), e.getBlock().getLocation(), e); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (!e.getClickedBlock().getType().isInteractable()) return;
        if (denied(e.getPlayer(), e.getClickedBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHitEntity(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && !(e.getEntity() instanceof Player)) {
            check(p, e.getEntity().getLocation(), e);
        }
    }
}
