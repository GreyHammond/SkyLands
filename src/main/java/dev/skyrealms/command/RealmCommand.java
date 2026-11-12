package dev.skyrealms.command;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Role;
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

/** /realm with no arguments opens the chest menu; subcommands are shortcuts for the same actions. */
public final class RealmCommand implements TabExecutor {
    private final SkyRealmsPlugin plugin;

    public RealmCommand(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { Msg.error(sender, "Players only."); return true; }
        var svc = plugin.service();
        var menus = plugin.menus();
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> menus.main(p);
            case "help" -> help(p, label);
            case "buy" -> {
                if (args.length < 3) { menus.buyTier(p); return true; }
                Tier tier = Tier.parse(args[1]);
                if (tier == null) { Msg.error(p, "Unknown size. Pick: " + tierList()); return true; }
                svc.buy(p, tier, args[2].toLowerCase(Locale.ROOT));
            }
            case "home", "go" -> svc.home(p);
            case "sethome" -> svc.setHome(p);
            case "info" -> info(p);
            case "upgrade" -> svc.upgrade(p);
            case "grow", "expand" -> {
                if (args.length < 2) menus.biomes(p, dev.skyrealms.menu.Menus.Mode.GROW, null);
                else svc.grow(p, args[1].toLowerCase(Locale.ROOT));
            }
            case "biome", "biomes" -> {
                if (args.length >= 3 && args[1].equalsIgnoreCase("buy")) svc.unlockBiome(p, args[2].toLowerCase(Locale.ROOT));
                else menus.biomes(p, dev.skyrealms.menu.Menus.Mode.UNLOCK, null);
            }
            case "friend", "friends", "trust" -> {
                if (args.length < 2) { menus.friends(p); return true; }
                if (args[1].equalsIgnoreCase("remove") && args.length > 2) svc.removeFriend(p, offline(args[2]));
                else svc.addFriend(p, offline(args[1].equalsIgnoreCase("add") && args.length > 2 ? args[2] : args[1]));
            }
            case "unfriend", "untrust" -> { if (args.length > 1) svc.removeFriend(p, offline(args[1])); }
            case "protection", "protect", "settings", "flags" -> menus.protection(p);
            case "visit" -> {
                if (args.length < 2) menus.visit(p, 0);
                else svc.visit(p, offline(args[1]));
            }
            case "feather" -> svc.giveFeather(p, true);
            case "prices", "shop" -> menus.buyTier(p);
            default -> help(p, label);
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
    }

    private void help(Player p, String label) {
        Msg.send(p, "<white>Type <aqua>/" + label + "</aqua> on its own to open the menu. Shortcuts:");
        for (String line : List.of(
                "buy <size> <biome>", "home", "info", "upgrade", "grow [biome]", "biome buy <biome>",
                "friend <player> | friend remove <player>", "protection", "visit [player]", "feather", "sethome")) {
            p.sendMessage(Msg.c(" <aqua>/" + label + " <gray>" + line));
        }
        p.sendMessage(Msg.c(" <dark_gray><i>Jump off the edge of your island to fall down to the overworld."));
    }

    private void info(Player p) {
        Island i = plugin.service().owned(p);
        if (i == null) return;
        Msg.send(p, "<white>Your island");
        p.sendMessage(Msg.c(" <gray>Size: <white>" + i.tier.pretty() + " <dark_gray>(" + i.tier.footprint + "x" + i.tier.footprint + ")"));
        p.sendMessage(Msg.c(" <gray>Land pieces: <white>" + i.pieces.size() + "<gray>/" + i.tier.maxPieces));
        p.sendMessage(Msg.c(" <gray>Biomes: <green>" + String.join(", ", i.biomes)));
        p.sendMessage(Msg.c(" <gray>Centre: <white>" + i.centerX + ", " + i.centerZ + " <dark_gray>(you'll land near here)"));
        p.sendMessage(Msg.c(" <gray>Friends: <white>" + i.friends.size() + "  <gray>Visitors may: <white>"
                + String.join(", ", i.flags(Role.VISITOR).stream().map(f -> f.title.toLowerCase()).toList())));
    }

    private static String tierList() {
        return "<white>" + String.join(", ", Stream.of(Tier.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out;
        if (args.length == 1) {
            out = List.of("buy", "home", "info", "upgrade", "grow", "biome", "friend", "unfriend", "protection", "visit", "feather", "sethome", "help");
        } else if (args[0].equalsIgnoreCase("buy") && args.length == 2) {
            out = Stream.of(Tier.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
        } else if ((args[0].equalsIgnoreCase("buy") && args.length == 3) || (args[0].equalsIgnoreCase("grow") && args.length == 2)
                || (args[0].equalsIgnoreCase("biome") && args.length == 3)) {
            out = new ArrayList<>(plugin.library().biomes());
        } else if (args[0].equalsIgnoreCase("biome") && args.length == 2) {
            out = List.of("buy");
        } else if (args.length >= 2 && List.of("friend", "unfriend", "visit", "trust", "untrust").contains(args[0].toLowerCase(Locale.ROOT))) {
            return null; // online player names
        } else {
            return List.of();
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.startsWith(last)).toList();
    }
}
