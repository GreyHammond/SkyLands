package dev.skyrealms.command;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Tier;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Stream;

public final class RealmCommand implements TabExecutor {
    private final SkyRealmsPlugin plugin;
    private final Map<UUID, Long> homeCooldowns = new HashMap<>();

    public RealmCommand(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Msg.error(sender, "Players only."); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "buy" -> buy(p, args);
            case "home", "go" -> home(p);
            case "sethome" -> setHome(p);
            case "info" -> info(p);
            case "upgrade" -> upgrade(p);
            case "grow", "expand" -> grow(p, args);
            case "biome", "biomes" -> biome(p, args);
            case "trust" -> trust(p, args, true);
            case "untrust" -> trust(p, args, false);
            case "visit" -> visit(p, args);
            case "prices", "shop" -> prices(p);
            default -> help(p, label);
        }
        return true;
    }

    private void help(Player p, String label) {
        Msg.send(p, "<white>Commands:");
        for (String line : List.of(
                "buy <tier> <biome> <dark_gray>- buy your floating island",
                "home <dark_gray>- fly back up to your island",
                "info <dark_gray>- size, biomes and pieces",
                "upgrade <dark_gray>- move up to the next size tier",
                "grow [biome] <dark_gray>- attach more land of a biome you own",
                "biome list | buy <biome> <dark_gray>- unlock a new biome region",
                "trust | untrust <player> <dark_gray>- let friends build",
                "visit <player> <dark_gray>- visit someone's island",
                "sethome <dark_gray>- set where /" + label + " home puts you",
                "prices <dark_gray>- see what everything costs")) {
            p.sendMessage(Msg.c(" <aqua>/" + label + " <gray>" + line));
        }
        p.sendMessage(Msg.c(" <dark_gray><i>Jump off the edge of your island to fall down to the overworld."));
    }

    private void buy(Player p, String[] args) {
        if (plugin.islands().of(p.getUniqueId()) != null) { Msg.error(p, "You already have an island. Try <white>/realm upgrade</white>."); return; }
        if (args.length < 3) { Msg.error(p, "Usage: /realm buy <tier> <biome>  (biomes: " + biomeList() + ")"); return; }
        Tier tier = Tier.parse(args[1]);
        if (tier == null) { Msg.error(p, "Unknown tier. Pick: " + tierList()); return; }
        String biome = args[2].toLowerCase(Locale.ROOT);
        if (plugin.library().variants(biome).isEmpty()) { Msg.error(p, "Unknown biome. Pick: " + biomeList()); return; }
        if (!plugin.money().usable()) { Msg.error(p, "The economy isn't set up yet."); return; }
        if (!plugin.money().charge(p, tier.price)) { Msg.error(p, "You need <white>" + plugin.money().format(tier.price) + "</white> for a " + tier.pretty() + " island."); return; }

        Island island = plugin.islands().create(p.getUniqueId(), tier);
        Msg.send(p, "Raising your <white>" + tier.pretty() + "</white> <green>" + biome + "</green> island from the clouds...");
        // Teleport them as soon as the first piece lands; the rest keeps growing around them.
        plugin.grower().grow(plugin.skyWorld(), island, biome, () -> {
            if (p.isOnline()) {
                p.teleportAsync(island.home(plugin.skyWorld(), plugin.baseY()));
                Msg.send(p, "Welcome home. Jump off the edge any time to drop into the overworld.");
            }
        });
        for (int i = 1; i < tier.startPieces; i++) plugin.grower().grow(plugin.skyWorld(), island, biome, null);
        plugin.islands().save();
    }

    private void home(Player p) {
        Island island = plugin.islands().of(p.getUniqueId());
        if (island == null) { Msg.error(p, "You don't have an island yet. <white>/realm buy <tier> <biome>"); return; }
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("transit.home-cooldown", 10) * 1000L;
        Long last = homeCooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd && !p.hasPermission("skyrealms.admin")) {
            Msg.error(p, "Wait " + ((cd - (now - last)) / 1000 + 1) + "s before going home again.");
            return;
        }
        homeCooldowns.put(p.getUniqueId(), now);
        p.teleportAsync(island.home(plugin.skyWorld(), plugin.baseY()));
    }

    private void setHome(Player p) {
        Island island = plugin.islands().of(p.getUniqueId());
        if (island == null || !island.owner.equals(p.getUniqueId())) { Msg.error(p, "Only island owners can do that."); return; }
        var loc = p.getLocation();
        if (!loc.getWorld().equals(plugin.skyWorld()) || !island.containsLand(loc.getX(), loc.getZ())) {
            Msg.error(p, "Stand on your island first."); return;
        }
        island.home = new double[]{loc.getX() - island.centerX, loc.getY(), loc.getZ() - island.centerZ, loc.getYaw(), loc.getPitch()};
        plugin.islands().save();
        Msg.send(p, "Home set.");
    }

    private void info(Player p) {
        Island i = plugin.islands().of(p.getUniqueId());
        if (i == null) { Msg.error(p, "You don't have an island yet."); return; }
        Msg.send(p, "<white>" + Bukkit.getOfflinePlayer(i.owner).getName() + "'s island");
        p.sendMessage(Msg.c(" <gray>Tier: <white>" + i.tier.pretty() + " <dark_gray>(" + i.tier.footprint + "x" + i.tier.footprint + ")"));
        p.sendMessage(Msg.c(" <gray>Land pieces: <white>" + i.pieces.size() + "<gray>/" + i.tier.maxPieces));
        p.sendMessage(Msg.c(" <gray>Biomes: <green>" + String.join(", ", i.biomes)));
        p.sendMessage(Msg.c(" <gray>Centre: <white>" + i.centerX + ", " + i.centerZ + " <dark_gray>(you'll land near here)"));
        if (!i.members.isEmpty()) {
            p.sendMessage(Msg.c(" <gray>Trusted: <white>" + String.join(", ",
                    i.members.stream().map(u -> Objects.toString(Bukkit.getOfflinePlayer(u).getName(), "?")).toList())));
        }
    }

    private void upgrade(Player p) {
        Island i = ownIsland(p);
        if (i == null) return;
        Tier next = i.tier.next();
        if (next == null) { Msg.error(p, "You're already Continental. There's nowhere bigger."); return; }
        double cost = Math.max(0, next.price - i.tier.price);
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Upgrading to " + next.pretty() + " costs <white>" + plugin.money().format(cost)); return; }
        int bonus = Math.max(0, next.startPieces - i.tier.startPieces);
        i.tier = next;
        List<String> owned = new ArrayList<>(i.biomes);
        Random rng = new Random();
        int grown = 0;
        for (int n = 0; n < bonus; n++) {
            if (plugin.grower().grow(plugin.skyWorld(), i, owned.get(rng.nextInt(owned.size())), null) != null) grown++;
        }
        plugin.islands().save();
        Msg.send(p, "Your island is now <white>" + next.pretty() + "</white> (" + next.footprint + "x" + next.footprint
                + "). " + (grown > 0 ? grown + " new pieces of land are rising." : ""));
    }

    private void grow(Player p, String[] args) {
        Island i = ownIsland(p);
        if (i == null) return;
        if (i.pieces.size() >= i.tier.maxPieces) { Msg.error(p, "Your " + i.tier.pretty() + " island is full. <white>/realm upgrade</white> for more room."); return; }
        String biome = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : new ArrayList<>(i.biomes).get(new Random().nextInt(i.biomes.size()));
        if (!i.biomes.contains(biome)) { Msg.error(p, "You don't own <white>" + biome + "</white>. Unlock it with <white>/realm biome buy " + biome); return; }
        double cost = plugin.getConfig().getDouble("prices.grow-piece", 1000);
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Growing costs <white>" + plugin.money().format(cost)); return; }
        if (plugin.grower().grow(plugin.skyWorld(), i, biome, () -> Msg.send(p, "New <green>" + biome + "</green> land has attached to your island.")) == null) {
            plugin.money().refund(p, cost);
            Msg.error(p, "There's no room left inside your border. <white>/realm upgrade</white> to grow further.");
            return;
        }
        plugin.islands().save();
    }

    private void biome(Player p, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            Island i = plugin.islands().of(p.getUniqueId());
            Msg.send(p, "Biomes:");
            for (String b : plugin.library().biomes()) {
                boolean owned = i != null && i.biomes.contains(b);
                p.sendMessage(Msg.c(" " + (owned ? "<green>✔ " : "<gray>• ") + b + " <dark_gray>("
                        + plugin.library().variants(b).size() + " variants)" + (owned ? "" : " <gold>" + plugin.money().format(unlockPrice(b)))));
            }
            return;
        }
        if (!args[1].equalsIgnoreCase("buy") || args.length < 3) { Msg.error(p, "Usage: /realm biome buy <biome>"); return; }
        Island i = ownIsland(p);
        if (i == null) return;
        String biome = args[2].toLowerCase(Locale.ROOT);
        if (plugin.library().variants(biome).isEmpty()) { Msg.error(p, "Unknown biome. Pick: " + biomeList()); return; }
        if (i.biomes.contains(biome)) { Msg.error(p, "You already own that biome. Use <white>/realm grow " + biome); return; }
        int slots = i.tier.maxPieces - i.pieces.size();
        if (slots <= 0) { Msg.error(p, "Your island is full. <white>/realm upgrade</white> first."); return; }
        double cost = unlockPrice(biome);
        if (!plugin.money().charge(p, cost)) { Msg.error(p, "Unlocking <white>" + biome + "</white> costs <white>" + plugin.money().format(cost)); return; }
        int want = Math.min(slots, Math.max(1, plugin.getConfig().getInt("prices.biome-region-pieces", 2)));
        if (plugin.grower().grow(plugin.skyWorld(), i, biome, () -> Msg.send(p, "A <green>" + biome + "</green> region has attached to the edge of your island!")) == null) {
            plugin.money().refund(p, cost);
            Msg.error(p, "There's no room on your island's edge. <white>/realm upgrade</white> first.");
            return;
        }
        for (int n = 1; n < want; n++) plugin.grower().grow(plugin.skyWorld(), i, biome, null);
        plugin.islands().save();
    }

    private void trust(Player p, String[] args, boolean add) {
        Island i = ownIsland(p);
        if (i == null) return;
        if (args.length < 2) { Msg.error(p, "Usage: /realm " + (add ? "trust" : "untrust") + " <player>"); return; }
        @SuppressWarnings("deprecation") OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target.getUniqueId().equals(p.getUniqueId())) { Msg.error(p, "That's you."); return; }
        if (add) {
            if (plugin.islands().of(target.getUniqueId()) != null) { Msg.error(p, args[1] + " already has or belongs to an island."); return; }
            plugin.islands().addMember(i, target.getUniqueId());
            Msg.send(p, args[1] + " can now build on your island.");
        } else {
            plugin.islands().removeMember(i, target.getUniqueId());
            Msg.send(p, args[1] + " can no longer build on your island.");
        }
        plugin.islands().save();
    }

    private void visit(Player p, String[] args) {
        if (args.length < 2) { Msg.error(p, "Usage: /realm visit <player>"); return; }
        @SuppressWarnings("deprecation") OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        Island i = plugin.islands().of(target.getUniqueId());
        if (i == null) { Msg.error(p, args[1] + " doesn't have an island."); return; }
        p.teleportAsync(i.home(plugin.skyWorld(), plugin.baseY()));
        Msg.send(p, "Visiting " + args[1] + "'s island.");
    }

    private void prices(Player p) {
        Msg.send(p, "<white>Island tiers:");
        for (Tier t : Tier.values()) {
            p.sendMessage(Msg.c(" <aqua>" + t.pretty() + " <dark_gray>" + t.footprint + "x" + t.footprint
                    + " <gray>- <gold>" + plugin.money().format(t.price) + " <dark_gray>(" + t.startPieces + " pieces, room for " + t.maxPieces + ")"));
        }
        p.sendMessage(Msg.c(" <gray>Extra land piece: <gold>" + plugin.money().format(plugin.getConfig().getDouble("prices.grow-piece"))));
        p.sendMessage(Msg.c(" <gray>New biome region: <gold>" + plugin.money().format(plugin.getConfig().getDouble("prices.biome-unlock-default")) + " <dark_gray>(some biomes cost more)"));
    }

    // ---- helpers -------------------------------------------------------------

    private Island ownIsland(Player p) {
        Island i = plugin.islands().ofOwner(p.getUniqueId());
        if (i == null) Msg.error(p, plugin.islands().of(p.getUniqueId()) != null ? "Only the island owner can do that." : "You don't have an island yet.");
        return i;
    }

    private double unlockPrice(String biome) {
        return plugin.getConfig().getDouble("prices.biome-unlock." + biome, plugin.getConfig().getDouble("prices.biome-unlock-default", 10000));
    }

    private String biomeList() {
        return plugin.library().biomes().isEmpty() ? "<white>none yet - an admin needs to capture some" : "<white>" + String.join(", ", plugin.library().biomes());
    }

    private static String tierList() {
        return "<white>" + String.join(", ", Stream.of(Tier.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out;
        if (args.length == 1) {
            out = List.of("buy", "home", "info", "upgrade", "grow", "biome", "trust", "untrust", "visit", "sethome", "prices");
        } else if (args[0].equalsIgnoreCase("buy") && args.length == 2) {
            out = Stream.of(Tier.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
        } else if ((args[0].equalsIgnoreCase("buy") && args.length == 3) || (args[0].equalsIgnoreCase("grow") && args.length == 2)
                || (args[0].equalsIgnoreCase("biome") && args.length == 3)) {
            out = new ArrayList<>(plugin.library().biomes());
        } else if (args[0].equalsIgnoreCase("biome") && args.length == 2) {
            out = List.of("list", "buy");
        } else if (args.length == 2 && List.of("trust", "untrust", "visit").contains(args[0].toLowerCase(Locale.ROOT))) {
            return null; // online player names
        } else {
            return List.of();
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.startsWith(last)).toList();
    }
}
