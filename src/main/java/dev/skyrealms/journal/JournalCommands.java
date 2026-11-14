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
package dev.skyrealms.journal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /skyland journal [skip [confirm]|restart] and /skylandadmin tutorial reset|skip|complete &lt;player&gt;. */
public final class JournalCommands {
    private JournalCommands() {}

    public static void attachPlayer(LiteralArgumentBuilder<CommandSourceStack> skyland, SkyRealmsPlugin plugin) {
        skyland.then(Commands.literal("journal")
                .executes(ctx -> run(ctx.getSource(), p -> plugin.journal().openFor(p)))
                .then(Commands.literal("skip")
                        .executes(ctx -> run(ctx.getSource(), p -> {
                            if (plugin.journal().finished(p)) { Msg.send(p, "The tutorial is already done."); return; }
                            Msg.send(p, "Skip the tutorial? Every journal page opens now, but step rewards aren't paid.");
                            p.sendMessage(Msg.c("  <red><click:run_command:'/skyland journal skip confirm'>[Yes, skip it]</click></red>  "
                                    + "<gray><click:run_command:'/skyland journal'>[No, keep going]</click></gray>"));
                        }))
                        .then(Commands.literal("confirm").executes(ctx -> run(ctx.getSource(), p -> {
                            plugin.journal().skip(p);
                            Msg.send(p, "Tutorial skipped. Every page of your journal is open.");
                            plugin.journal().openFor(p);
                        }))))
                .then(Commands.literal("restart").executes(ctx -> run(ctx.getSource(), p -> {
                    plugin.journal().restart(p);
                    Msg.send(p, "Tutorial restarted. Your pages stay open, and rewards are never paid twice.");
                    plugin.journal().openFor(p);
                }))));
    }

    public static void attachAdmin(LiteralArgumentBuilder<CommandSourceStack> admin, SkyRealmsPlugin plugin) {
        var tutorial = Commands.literal("tutorial");
        for (String action : List.of("reset", "skip", "complete")) {
            tutorial.then(Commands.literal(action).then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, b) -> { Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName())); return b.buildFuture(); })
                    .executes(ctx -> admin(ctx.getSource().getSender(), plugin, action, StringArgumentType.getString(ctx, "player")))));
        }
        admin.then(tutorial);
    }

    public static String adminHelp() { return "tutorial reset|skip|complete <player> <dark_gray>- change a player's tutorial and journal"; }

    private static int admin(CommandSender to, SkyRealmsPlugin plugin, String action, String name) {
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) { Msg.error(to, "Unknown player " + name + "."); return 0; }
        switch (action) {
            case "reset" -> plugin.journal().reset(target.getUniqueId());
            case "skip" -> plugin.journal().skip(target.getUniqueId());
            default -> plugin.journal().complete(target.getUniqueId());
        }
        Msg.send(to, "Tutorial " + action + " done for <white>" + target.getName() + "</white>.");
        return Command.SINGLE_SUCCESS;
    }

    private static int run(CommandSourceStack src, java.util.function.Consumer<Player> action) {
        if (src.getExecutor() instanceof Player p) { action.accept(p); return Command.SINGLE_SUCCESS; }
        Msg.error(src.getSender(), "Players only.");
        return 0;
    }
}
