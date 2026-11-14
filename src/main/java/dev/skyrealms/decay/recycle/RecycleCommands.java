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
package dev.skyrealms.decay.recycle;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

/** /skylandadmin recycle status|now [seconds]|cancel */
public final class RecycleCommands {
    private RecycleCommands() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> adminRoot, SkyRealmsPlugin plugin) {
        adminRoot.then(Commands.literal("recycle")
                .executes(ctx -> status(ctx.getSource().getSender(), plugin))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource().getSender(), plugin)))
                .then(Commands.literal("now")
                        .executes(ctx -> now(ctx.getSource().getSender(), plugin, -1))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .executes(ctx -> now(ctx.getSource().getSender(), plugin, IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource().getSender(), plugin))));
    }

    private static WorldRecycler on(CommandSender to, SkyRealmsPlugin plugin) {
        WorldRecycler r = plugin.recycler();
        if (r != null && r.enabled() && r.currentWorld() != null) return r;
        Msg.error(to, "Overworld recycling is off. Enable it under <white>recycle:</white> in config.yml (and restart).");
        return null;
    }

    private static int status(CommandSender to, SkyRealmsPlugin plugin) {
        WorldRecycler r = on(to, plugin);
        if (r == null) return 0;
        Msg.send(to, "Overworld: <white>" + r.currentWorld() + "</white> (cycle " + r.cycle() + ").");
        if (r.countdownSeconds() > 0) Msg.send(to, "Recycling in <white>" + WorldRecycler.duration(r.countdownSeconds()) + "</white>.");
        else Msg.send(to, "Next recycle in <white>" + WorldRecycler.duration(r.secondsUntilNext()) + "</white>.");
        return Command.SINGLE_SUCCESS;
    }

    private static int now(CommandSender to, SkyRealmsPlugin plugin, int seconds) {
        WorldRecycler r = on(to, plugin);
        if (r == null) return 0;
        int s = seconds > 0 ? seconds : r.longestWarning();
        if (!r.startCountdown(s)) { Msg.error(to, "A recycle is already in progress."); return 0; }
        Msg.send(to, "Recycle countdown started: <white>" + WorldRecycler.duration(s) + "</white>.");
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel(CommandSender to, SkyRealmsPlugin plugin) {
        WorldRecycler r = on(to, plugin);
        if (r == null) return 0;
        if (!r.cancelCountdown(true)) { Msg.error(to, "No recycle countdown is running."); return 0; }
        Msg.send(to, "Recycle cancelled. Next one in <white>" + WorldRecycler.duration(r.secondsUntilNext()) + "</white>.");
        return Command.SINGLE_SUCCESS;
    }
}
