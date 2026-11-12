package dev.skyrealms.command;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Tier;
import dev.skyrealms.piece.Piece;
import dev.skyrealms.piece.PieceCapture;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class AdminCommand implements TabExecutor {
    private static final int MAX_RADIUS = 48;
    private final SkyRealmsPlugin plugin;

    public AdminCommand(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "capture" -> capture(sender, args);
            case "preview" -> preview(sender, args);
            case "pieces" -> pieces(sender);
            case "give" -> give(sender, args);
            case "tp" -> tp(sender, args);
            case "rebuild" -> rebuild(sender, args);
            case "reload" -> {
                plugin.library().reload();
                Msg.send(sender, "Pieces reloaded. (Restart the server to apply config.yml changes.)");
            }
            default -> {
                Msg.send(sender, "<white>Admin commands:");
                for (String l : List.of(
                        "capture <biome> <radius> [name] <dark_gray>- save a sphere of terrain around you as a piece",
                        "preview <radius> <dark_gray>- show the capture sphere outline before saving",
                        "pieces <dark_gray>- list biomes and their variants",
                        "give <player> <tier> <biome> <dark_gray>- create an island for free",
                        "tp <player> <dark_gray>- go to someone's island",
                        "rebuild <player> <dark_gray>- re-paste every piece of an island",
                        "reload <dark_gray>- reload piece files"))
                    sender.sendMessage(Msg.c(" <aqua>/" + label + " <gray>" + l));
            }
        }
        return true;
    }

    private void capture(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { Msg.error(sender, "Players only."); return; }
        if (args.length < 3) { Msg.error(p, "Usage: /realmadmin capture <biome> <radius> [name]"); return; }
        String biome = args[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        int radius;
        try { radius = Integer.parseInt(args[2]); } catch (NumberFormatException e) { Msg.error(p, "Radius must be a number."); return; }
        if (radius < 4 || radius > MAX_RADIUS) { Msg.error(p, "Radius must be 4-" + MAX_RADIUS + "."); return; }
        String name = args.length > 3 ? args[3].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "") : plugin.library().nextName(biome);

        Piece piece = PieceCapture.capture(p.getLocation(), radius, biome, name);
        if (piece.size() == 0) { Msg.error(p, "Nothing but air there."); return; }
        try {
            piece.write(plugin.library().fileFor(biome, name));
        } catch (IOException e) {
            Msg.error(p, "Couldn't save: " + e.getMessage());
            return;
        }
        plugin.library().add(piece);
        Msg.send(p, "Saved <white>" + name + "</white> to biome <green>" + biome + "</green> ("
                + String.format("%,d", piece.size()) + " blocks, radius " + radius + ").");
        if (radius * 2 > Tier.SMALL.footprint) {
            p.sendMessage(Msg.c(" <yellow>Note: this piece is wider than a Small island (" + Tier.SMALL.footprint
                    + "), so Small islands can't use it as their first piece."));
        }
    }

    private void preview(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) return;
        int r = args.length > 1 ? Math.min(MAX_RADIUS, Math.max(4, parse(args[1], 16))) : 16;
        var c = p.getLocation();
        for (int i = 0; i < 600; i++) {
            double u = Math.random() * 2 - 1, t = Math.random() * Math.PI * 2, s = Math.sqrt(1 - u * u);
            p.spawnParticle(Particle.END_ROD, c.clone().add(s * Math.cos(t) * r, u * r, s * Math.sin(t) * r), 1, 0, 0, 0, 0);
        }
        Msg.send(p, "That's a radius-" + r + " sphere around your feet. Everything inside (except air) gets captured.");
    }

    private void pieces(CommandSender sender) {
        if (plugin.library().biomes().isEmpty()) {
            Msg.send(sender, "No pieces yet. Go to a biome and run <white>/realmadmin capture <biome> <radius>");
            return;
        }
        for (String b : plugin.library().biomes()) {
            sender.sendMessage(Msg.c(" <green>" + b + "<gray>: " + String.join(", ",
                    plugin.library().variants(b).stream().map(v -> v.name + " <dark_gray>(r" + v.radius + ")<gray>").toList())));
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 4) { Msg.error(sender, "Usage: /realmadmin give <player> <tier> <biome>"); return; }
        @SuppressWarnings("deprecation") OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (plugin.islands().of(target.getUniqueId()) != null) { Msg.error(sender, "They already have an island."); return; }
        Tier tier = Tier.parse(args[2]);
        String biome = args[3].toLowerCase(Locale.ROOT);
        if (tier == null || plugin.library().variants(biome).isEmpty()) { Msg.error(sender, "Bad tier or biome."); return; }
        Island i = plugin.islands().create(target.getUniqueId(), tier);
        for (int n = 0; n < tier.startPieces; n++) plugin.grower().grow(plugin.skyWorld(), i, biome, null);
        plugin.islands().save();
        Msg.send(sender, "Created a " + tier.pretty() + " " + biome + " island for " + args[1] + " at " + i.centerX + ", " + i.centerZ + ".");
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || args.length < 2) return;
        @SuppressWarnings("deprecation") Island i = plugin.islands().of(Bukkit.getOfflinePlayer(args[1]).getUniqueId());
        if (i == null) { Msg.error(p, "No island."); return; }
        p.teleportAsync(i.home(plugin.skyWorld(), plugin.baseY()));
    }

    private void rebuild(CommandSender sender, String[] args) {
        if (args.length < 2) return;
        @SuppressWarnings("deprecation") Island i = plugin.islands().of(Bukkit.getOfflinePlayer(args[1]).getUniqueId());
        if (i == null) { Msg.error(sender, "No island."); return; }
        Msg.send(sender, "Re-pasting " + plugin.grower().rebuild(plugin.skyWorld(), i) + " pieces.");
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out;
        if (args.length == 1) out = List.of("capture", "preview", "pieces", "give", "tp", "rebuild", "reload");
        else if (args[0].equalsIgnoreCase("capture") && args.length == 2) out = new ArrayList<>(plugin.library().biomes());
        else if (args[0].equalsIgnoreCase("give") && args.length == 3) out = Stream.of(Tier.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
        else if (args[0].equalsIgnoreCase("give") && args.length == 4) out = new ArrayList<>(plugin.library().biomes());
        else if (args.length == 2 && List.of("give", "tp", "rebuild").contains(args[0].toLowerCase(Locale.ROOT))) return null;
        else return List.of();
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.startsWith(last)).toList();
    }
}
