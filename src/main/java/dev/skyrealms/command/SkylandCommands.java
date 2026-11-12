package dev.skyrealms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.island.Tier;
import dev.skyrealms.piece.Piece;
import dev.skyrealms.piece.PieceCapture;
import dev.skyrealms.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.*;

/**
 * /skyland (player menu) and /skylandadmin, registered with Paper's Brigadier API so the
 * client gets a full command tree: every argument offers Tab suggestions you can cycle through.
 */
public final class SkylandCommands {
    private static final int MIN_RADIUS = 4, MAX_RADIUS = 48;
    private final SkyRealmsPlugin plugin;

    public SkylandCommands(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    public void register(Commands commands) {
        commands.register(skyland(), "Open the Skyland menu", List.of("sl", "realm"));
        commands.register(admin(), "Skyland admin tools", List.of("sla", "realmadmin"));
    }

    // ---- /skyland ------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> skyland() {
        return Commands.literal("skyland")
                .requires(src -> src.getSender().hasPermission("skyrealms.use"))
                .executes(ctx -> {
                    if (ctx.getSource().getExecutor() instanceof Player p) plugin.menus().main(p);
                    else Msg.error(ctx.getSource().getSender(), "Players only.");
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // ---- /skylandadmin -------------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> admin() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("skylandadmin")
                .requires(src -> src.getSender().hasPermission("skyrealms.admin"))
                .executes(ctx -> help(ctx.getSource().getSender()));

        root.then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())));

        // capture <biome> [small|medium|large|huge|<radius>] [name]
        var biomeArg = Commands.argument("biome", StringArgumentType.word()).suggests(captureBiomes())
                .executes(ctx -> capture(ctx, sizeRadius("medium"), null));
        for (String size : sizes()) {
            biomeArg.then(Commands.literal(size)
                    .executes(ctx -> capture(ctx, sizeRadius(size), null))
                    .then(Commands.argument("name", StringArgumentType.word())
                            .executes(ctx -> capture(ctx, sizeRadius(size), StringArgumentType.getString(ctx, "name")))));
        }
        biomeArg.then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                .executes(ctx -> capture(ctx, IntegerArgumentType.getInteger(ctx, "radius"), null))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> capture(ctx, IntegerArgumentType.getInteger(ctx, "radius"), StringArgumentType.getString(ctx, "name")))));
        root.then(Commands.literal("capture").then(biomeArg));

        // preview [small|medium|large|huge|<radius>]
        var preview = Commands.literal("preview").executes(ctx -> preview(ctx, sizeRadius("medium")));
        for (String size : sizes()) preview.then(Commands.literal(size).executes(ctx -> preview(ctx, sizeRadius(size))));
        preview.then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                .executes(ctx -> preview(ctx, IntegerArgumentType.getInteger(ctx, "radius"))));
        root.then(preview);

        root.then(Commands.literal("pieces").executes(ctx -> pieces(ctx.getSource().getSender())));

        // give <player> <tier> <biome>
        var givePlayer = Commands.argument("player", StringArgumentType.word()).suggests(players(false));
        for (Tier t : Tier.values()) {
            String tier = t.name().toLowerCase(Locale.ROOT);
            givePlayer.then(Commands.literal(tier).then(Commands.argument("biome", StringArgumentType.word()).suggests(libraryBiomes())
                    .executes(ctx -> give(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), t,
                            StringArgumentType.getString(ctx, "biome")))));
        }
        root.then(Commands.literal("give").then(givePlayer));

        root.then(Commands.literal("tp").then(Commands.argument("owner", StringArgumentType.word()).suggests(players(true))
                .executes(ctx -> tp(ctx.getSource(), StringArgumentType.getString(ctx, "owner")))));
        root.then(Commands.literal("rebuild").then(Commands.argument("owner", StringArgumentType.word()).suggests(players(true))
                .executes(ctx -> rebuild(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "owner")))));
        root.then(Commands.literal("reload").executes(ctx -> {
            plugin.library().reload();
            Msg.send(ctx.getSource().getSender(), "Pieces reloaded. (Restart the server to apply config.yml changes.)");
            return Command.SINGLE_SUCCESS;
        }));
        return root.build();
    }

    // ---- suggestions -------------------------------------------------------------------

    /** Existing piece folders, the biome you're standing in, then every vanilla biome. */
    private SuggestionProvider<CommandSourceStack> captureBiomes() {
        return (ctx, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            LinkedHashSet<String> out = new LinkedHashSet<>(plugin.library().biomes());
            if (ctx.getSource().getExecutor() instanceof Player p) {
                out.add(p.getWorld().getBiome(p.getLocation()).getKey().getKey());
            }
            for (Biome b : RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)) out.add(b.getKey().getKey());
            out.stream().filter(s -> s.startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> libraryBiomes() {
        return (ctx, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            plugin.library().biomes().stream().filter(s -> s.startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    /** Online players, or island owners (online first, then offline) when ownersOnly. */
    private SuggestionProvider<CommandSourceStack> players(boolean ownersOnly) {
        return (ctx, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if (ownersOnly) {
                plugin.islands().all().stream()
                        .sorted(Comparator.comparing((Island i) -> Bukkit.getPlayer(i.owner) == null))
                        .map(i -> Bukkit.getOfflinePlayer(i.owner).getName()).filter(Objects::nonNull).forEach(names::add);
            } else {
                Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            }
            names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    // ---- sizes -----------------------------------------------------------------------------

    private List<String> sizes() {
        // Older configs won't have this section: fall back to the built-in sizes.
        var sec = plugin.getConfig().getConfigurationSection("capture-sizes");
        List<String> keys = sec == null ? List.of() : List.copyOf(sec.getKeys(false));
        return keys.isEmpty() ? List.of("small", "medium", "large", "huge") : keys;
    }

    private int sizeRadius(String size) {
        int def = switch (size) { case "small" -> 10; case "large" -> 20; case "huge" -> 28; default -> 14; };
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, plugin.getConfig().getInt("capture-sizes." + size, def)));
    }

    private String sizeName(int radius) {
        for (String s : sizes()) if (sizeRadius(s) == radius) return s + " (" + (radius * 2 + 1) + " wide)";
        return (radius * 2 + 1) + " wide";
    }

    // ---- actions -------------------------------------------------------------------------

    private int help(CommandSender to) {
        Msg.send(to, "<white>Admin commands <dark_gray>(press Tab to see options)");
        String sizes = String.join("|", sizes());
        for (String l : List.of(
                "capture <biome> [" + sizes + "] [name] <dark_gray>- save the terrain around you as a piece",
                "preview [" + sizes + "] <dark_gray>- show the capture sphere before saving",
                "pieces <dark_gray>- list biomes and their variants",
                "give <player> <size> <biome> <dark_gray>- create an island for free",
                "tp <owner> <dark_gray>- go to someone's island",
                "rebuild <owner> <dark_gray>- re-paste every piece of an island",
                "reload <dark_gray>- reload piece files"))
            to.sendMessage(Msg.c(" <aqua>/skylandadmin <gray>" + l));
        to.sendMessage(Msg.c(" <dark_gray>Sizes can also be an exact radius from " + MIN_RADIUS + " to " + MAX_RADIUS + "."));
        return Command.SINGLE_SUCCESS;
    }

    private int capture(CommandContext<CommandSourceStack> ctx, int radius, String rawName) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) { Msg.error(ctx.getSource().getSender(), "Players only."); return 0; }
        String biome = StringArgumentType.getString(ctx, "biome").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (biome.isEmpty()) { Msg.error(p, "Biome names can only use letters, numbers and _."); return 0; }
        String name = rawName == null ? plugin.library().nextName(biome) : rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        Piece piece = PieceCapture.capture(p.getLocation(), radius, biome, name);
        if (piece.size() == 0) { Msg.error(p, "Nothing but air there."); return 0; }
        try {
            piece.write(plugin.library().fileFor(biome, name));
        } catch (IOException e) {
            Msg.error(p, "Couldn't save: " + e.getMessage());
            return 0;
        }
        plugin.library().add(piece);
        Msg.send(p, "Saved <white>" + name + "</white> to biome <green>" + biome + "</green>: "
                + sizeName(radius) + ", " + String.format("%,d", piece.size()) + " blocks.");
        if (radius * 2 > Tier.SMALL.footprint) {
            p.sendMessage(Msg.c(" <yellow>Note: wider than a Small island (" + Tier.SMALL.footprint
                    + "), so Small islands won't use it as their first piece."));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int preview(CommandContext<CommandSourceStack> ctx, int r) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
        var c = p.getLocation();
        for (int i = 0; i < 700; i++) {
            double u = Math.random() * 2 - 1, t = Math.random() * Math.PI * 2, s = Math.sqrt(1 - u * u);
            p.spawnParticle(Particle.END_ROD, c.clone().add(s * Math.cos(t) * r, u * r, s * Math.sin(t) * r), 1, 0, 0, 0, 0);
        }
        Msg.send(p, "That's a " + sizeName(r) + " sphere around your feet. Everything inside it (except air) gets captured.");
        return Command.SINGLE_SUCCESS;
    }

    private int pieces(CommandSender to) {
        if (plugin.library().biomes().isEmpty()) {
            Msg.send(to, "No pieces yet. Go to a biome and run <white>/skylandadmin capture <biome> medium");
            return Command.SINGLE_SUCCESS;
        }
        for (String b : plugin.library().biomes()) {
            to.sendMessage(Msg.c(" <green>" + b + "<gray>: " + String.join(", ",
                    plugin.library().variants(b).stream().map(v -> v.name + " <dark_gray>(" + sizeName(v.radius) + ")<gray>").toList())));
        }
        return Command.SINGLE_SUCCESS;
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
    }

    private int give(CommandSender sender, String playerName, Tier tier, String biome) {
        OfflinePlayer target = offline(playerName);
        biome = biome.toLowerCase(Locale.ROOT);
        if (plugin.islands().of(target.getUniqueId()) != null) { Msg.error(sender, playerName + " already has an island."); return 0; }
        if (plugin.library().variants(biome).isEmpty()) { Msg.error(sender, "No pieces for biome <white>" + biome + "</white>."); return 0; }
        Island i = plugin.islands().create(target.getUniqueId(), tier);
        for (int n = 0; n < tier.startPieces; n++) plugin.grower().grow(plugin.skyWorld(), i, biome, null);
        plugin.islands().save();
        Msg.send(sender, "Created a " + tier.pretty() + " " + biome + " island for " + playerName + " at " + i.centerX + ", " + i.centerZ + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int tp(CommandSourceStack src, String owner) {
        if (!(src.getExecutor() instanceof Player p)) { Msg.error(src.getSender(), "Players only."); return 0; }
        Island i = plugin.islands().of(offline(owner).getUniqueId());
        if (i == null) { Msg.error(p, owner + " doesn't have an island."); return 0; }
        p.teleportAsync(i.home(plugin.skyWorld(), plugin.baseY()));
        return Command.SINGLE_SUCCESS;
    }

    private int rebuild(CommandSender sender, String owner) {
        Island i = plugin.islands().of(offline(owner).getUniqueId());
        if (i == null) { Msg.error(sender, owner + " doesn't have an island."); return 0; }
        Msg.send(sender, "Re-pasting " + plugin.grower().rebuild(plugin.skyWorld(), i) + " pieces.");
        return Command.SINGLE_SUCCESS;
    }
}
