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
package dev.skyrealms.economy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /skyland exchange and /skylandadmin void give. */
public final class VoidCommands {
    private VoidCommands() {}

    public static void attachPlayer(LiteralArgumentBuilder<CommandSourceStack> skyland, SkyRealmsPlugin plugin) {
        skyland.then(Commands.literal("exchange").executes(ctx -> {
            if (ctx.getSource().getExecutor() instanceof Player p) plugin.menus().exchange(p);
            else Msg.error(ctx.getSource().getSender(), "Players only.");
            return Command.SINGLE_SUCCESS;
        }));
    }

    public static void attachAdmin(LiteralArgumentBuilder<CommandSourceStack> admin, SkyRealmsPlugin plugin) {
        var kind = Commands.argument("kind", StringArgumentType.word())
                .suggests((ctx, b) -> { for (var k : VoidItems.Kind.values()) b.suggest(k.id()); return b.buildFuture(); })
                .executes(ctx -> give(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), StringArgumentType.getString(ctx, "kind"), 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64 * 36))
                        .executes(ctx -> give(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"),
                                StringArgumentType.getString(ctx, "kind"), IntegerArgumentType.getInteger(ctx, "amount"))));
        admin.then(Commands.literal("void").then(Commands.literal("give").then(Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, b) -> { Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName())); return b.buildFuture(); })
                .then(kind))));
    }

    private static int give(CommandSender to, String playerName, String kindId, int amount) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) { Msg.error(to, playerName + " isn't online."); return 0; }
        VoidItems.Kind kind = VoidItems.Kind.byId(kindId);
        if (kind == null) { Msg.error(to, "Kind must be shard or remnant."); return 0; }
        give(target, kind, amount);
        Msg.send(to, "Gave " + target.getName() + " <white>" + amount + "</white> " + kind.id() + ".");
        return Command.SINGLE_SUCCESS;
    }

    /** Gives void items, dropping whatever doesn't fit at the player's feet. */
    public static void give(Player p, VoidItems.Kind kind, int amount) {
        int stack = kind.material.getMaxStackSize();
        while (amount > 0) {
            int n = Math.min(stack, amount);
            amount -= n;
            for (var left : p.getInventory().addItem(VoidItems.create(kind, n)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }
}
