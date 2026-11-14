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
package dev.skyrealms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.decay.CleansingTonic;
import dev.skyrealms.island.Island;
import dev.skyrealms.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /skyland tonic buy <1-3> and /skylandadmin tonic give <player> <1-3> [amount]. */
public final class TonicCommands {
    private TonicCommands() {}

    public static void attachPlayer(LiteralArgumentBuilder<CommandSourceStack> skyland, SkyRealmsPlugin plugin) {
        skyland.then(Commands.literal("tonic").then(Commands.literal("buy")
                .then(Commands.argument("level", IntegerArgumentType.integer(1, CleansingTonic.MAX_LEVEL))
                        .executes(ctx -> buy(ctx.getSource(), plugin, IntegerArgumentType.getInteger(ctx, "level"))))));
    }

    public static void attachAdmin(LiteralArgumentBuilder<CommandSourceStack> admin, SkyRealmsPlugin plugin) {
        admin.then(Commands.literal("tonic").then(Commands.literal("give")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> { Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName())); return b.buildFuture(); })
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, CleansingTonic.MAX_LEVEL))
                                .executes(ctx -> give(ctx.getSource().getSender(), plugin, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "level"), 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> give(ctx.getSource().getSender(), plugin, StringArgumentType.getString(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "level"), IntegerArgumentType.getInteger(ctx, "amount"))))))));
    }

    private static CleansingTonic tonic(CommandSender to, SkyRealmsPlugin plugin) {
        CleansingTonic t = plugin.tonic();
        if (t == null) Msg.error(to, "Cleansing Tonic is unavailable while decay is off.");
        return t;
    }

    private static int buy(CommandSourceStack src, SkyRealmsPlugin plugin, int level) {
        if (!(src.getExecutor() instanceof Player p)) { Msg.error(src.getSender(), "Players only."); return 0; }
        CleansingTonic t = tonic(p, plugin);
        if (t == null) return 0;
        CleansingTonic.Tier tier = t.tier(level);
        if (tier.requiredLevel() > 1) {
            Island i = plugin.islands().of(p.getUniqueId());
            int have = i == null ? 0 : plugin.xp().level(i);
            if (have < tier.requiredLevel()) {
                Msg.error(p, "Cleansing Tonic " + CleansingTonic.numeral(level) + " needs an island at level <white>"
                        + tier.requiredLevel() + "</white>" + (i == null ? " (you don't have an island)." : " (yours is " + have + ")."));
                return 0;
            }
        }
        if (!plugin.money().usable()) { Msg.error(p, "Purchases are unavailable: no economy is installed."); return 0; }
        if (!plugin.money().charge(p, tier.price())) {
            Msg.error(p, "You can't afford that. It costs <white>" + plugin.money().format(tier.price()) + "</white>.");
            return 0;
        }
        hand(p, t.create(level, 1));
        Msg.send(p, "Bought a <aqua>Cleansing Tonic " + CleansingTonic.numeral(level) + "</aqua> for <white>"
                + plugin.money().format(tier.price()) + "</white>.");
        return Command.SINGLE_SUCCESS;
    }

    private static int give(CommandSender to, SkyRealmsPlugin plugin, String name, int level, int amount) {
        CleansingTonic t = tonic(to, plugin);
        if (t == null) return 0;
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) { Msg.error(to, name + " isn't online."); return 0; }
        hand(target, t.create(level, amount));
        Msg.send(to, "Gave " + amount + "x Cleansing Tonic " + CleansingTonic.numeral(level) + " to " + target.getName() + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static void hand(Player p, ItemStack item) {
        p.getInventory().addItem(item).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
    }
}
