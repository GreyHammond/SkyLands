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
package dev.skyrealms.contracts;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** /skyland contracts and /skylandadmin contracts reset. */
public final class ContractCommands {
    private ContractCommands() {}

    public static void attachPlayer(LiteralArgumentBuilder<CommandSourceStack> skyland, SkyRealmsPlugin plugin) {
        skyland.then(Commands.literal("contracts").executes(ctx -> {
            var menu = plugin.contractMenu();
            if (menu == null) { Msg.error(ctx.getSource().getSender(), "Contracts are turned off."); return 0; }
            if (ctx.getSource().getExecutor() instanceof Player p) menu.open(p);
            else Msg.error(ctx.getSource().getSender(), "Players only.");
            return Command.SINGLE_SUCCESS;
        }));
    }

    public static void attachAdmin(LiteralArgumentBuilder<CommandSourceStack> admin, SkyRealmsPlugin plugin) {
        admin.then(Commands.literal("contracts").then(Commands.literal("reset").then(Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, b) -> { Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName())); return b.buildFuture(); })
                .executes(ctx -> {
                    var svc = plugin.contracts();
                    var to = ctx.getSource().getSender();
                    if (svc == null) { Msg.error(to, "Contracts are turned off."); return 0; }
                    String name = StringArgumentType.getString(ctx, "player");
                    OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
                    if (op == null) { Msg.error(to, "Unknown player " + name + "."); return 0; }
                    svc.reset(op.getUniqueId());
                    Msg.send(to, name + " gets a fresh set of contracts.");
                    return Command.SINGLE_SUCCESS;
                }))));
    }

    public static String adminHelp() { return "contracts reset <player> <dark_gray>- give a player new daily contracts"; }
}
