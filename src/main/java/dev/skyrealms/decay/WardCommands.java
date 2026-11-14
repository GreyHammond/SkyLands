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
package dev.skyrealms.decay;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.util.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/** Brigadier branches for Warded gear: {@code /skylandadmin ward ...} and {@code /skyland ward upgrade}. */
public final class WardCommands {
    private static final Map<String, Material> PIECES = Map.of(
            "helmet", Material.IRON_HELMET, "chestplate", Material.IRON_CHESTPLATE,
            "leggings", Material.IRON_LEGGINGS, "boots", Material.IRON_BOOTS);

    private WardCommands() {}

    /** ward apply <1-3> | ward give <player> <piece> <1-3> */
    public static void attachAdmin(LiteralArgumentBuilder<CommandSourceStack> root, SkyRealmsPlugin plugin) {
        var level = IntegerArgumentType.integer(1, WardedGear.MAX_LEVEL);
        var give = Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, b) -> { Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName())); return b.buildFuture(); });
        for (String piece : PIECES.keySet()) {
            give.then(Commands.literal(piece).then(Commands.argument("level", level)
                    .executes(ctx -> give(plugin, ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"),
                            piece, IntegerArgumentType.getInteger(ctx, "level")))));
        }
        root.then(Commands.literal("ward")
                .then(Commands.literal("apply").then(Commands.argument("level", level)
                        .executes(ctx -> apply(plugin, ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("give").then(give)));
    }

    /** ward upgrade */
    public static void attachPlayer(LiteralArgumentBuilder<CommandSourceStack> root, SkyRealmsPlugin plugin) {
        root.then(Commands.literal("ward").then(Commands.literal("upgrade")
                .executes(ctx -> upgrade(plugin, ctx.getSource()))));
    }

    public static List<String> adminHelp() {
        return List.of("ward apply <1-3> <dark_gray>- ward the armor in your hand",
                "ward give <player> <helmet|chestplate|leggings|boots> <1-3> <dark_gray>- give warded iron armor");
    }

    private static WardedGear gear(SkyRealmsPlugin plugin, CommandSender to) {
        WardedGear g = plugin.ward();
        if (g == null) Msg.error(to, "Warded gear isn't available.");
        return g;
    }

    private static int apply(SkyRealmsPlugin plugin, CommandSourceStack src, int level) {
        CommandSender to = src.getSender();
        WardedGear g = gear(plugin, to);
        if (g == null) return 0;
        if (!(src.getExecutor() instanceof Player p)) { Msg.error(to, "Players only."); return 0; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (WardedGear.armorSlot(hand.getType()) == null) { Msg.error(to, "Hold a helmet, chestplate, leggings or boots."); return 0; }
        g.setLevel(hand, level);
        Msg.send(to, "That piece is now <white>Warded " + WardedGear.roman(level) + "</white>.");
        return Command.SINGLE_SUCCESS;
    }

    private static int give(SkyRealmsPlugin plugin, CommandSender to, String name, String piece, int level) {
        WardedGear g = gear(plugin, to);
        if (g == null) return 0;
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) { Msg.error(to, name + " isn't online."); return 0; }
        ItemStack item = new ItemStack(PIECES.get(piece));
        g.setLevel(item, level);
        target.getInventory().addItem(item).values().forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
        Msg.send(to, "Gave " + target.getName() + " Warded " + WardedGear.roman(level) + " iron " + piece + ".");
        if (!target.equals(to)) Msg.send(target, "You received Warded " + WardedGear.roman(level) + " iron " + piece + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int upgrade(SkyRealmsPlugin plugin, CommandSourceStack src) {
        CommandSender to = src.getSender();
        WardedGear g = gear(plugin, to);
        if (g == null) return 0;
        if (!(src.getExecutor() instanceof Player p)) { Msg.error(to, "Players only."); return 0; }
        if (!g.shop) {
            Msg.send(p, "Warded armor is smithed now. Craft a <light_purple>Ward Template</light_purple> (8 Void Shards around Crying Obsidian), "
                    + "then smith it onto armor with a Void Shard for Ward I. Ward II and III need a <dark_purple>Corrupted Template</dark_purple> "
                    + "(a Ward Template surrounded by Corrupted Remnants) plus a Remnant, then an Echo Shard. Check your recipe book.");
            return 0;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (WardedGear.armorSlot(hand.getType()) == null) { Msg.error(p, "Hold the helmet, chestplate, leggings or boots to upgrade."); return 0; }
        if (hand.getAmount() != 1) { Msg.error(p, "Hold a single piece of armor."); return 0; }
        int current = g.level(hand);
        if (current >= WardedGear.MAX_LEVEL) { Msg.error(p, "That piece is already Warded " + WardedGear.roman(WardedGear.MAX_LEVEL) + "."); return 0; }
        int next = current + 1;

        int needed = g.requiredIslandLevel(next);
        if (needed > 0) {
            Island island = plugin.islands().of(p.getUniqueId());
            int have = island == null ? 0 : plugin.xp().level(island);
            if (have < needed) {
                Msg.error(p, "Warded " + WardedGear.roman(next) + " needs island level <white>" + needed + "</white> (yours: " + have + ").");
                return 0;
            }
        }
        double price = g.price(next);
        if (!plugin.money().usable()) { Msg.error(p, "Purchases are unavailable: no economy is installed."); return 0; }
        if (!plugin.money().charge(p, price)) {
            Msg.error(p, "Warded " + WardedGear.roman(next) + " costs <white>" + plugin.money().format(price) + "</white>.");
            return 0;
        }
        ItemStack again = p.getInventory().getItemInMainHand();
        if (!again.isSimilar(hand) || again.getAmount() != 1) {
            plugin.money().refund(p, price);   // item-currency payment could have shifted the inventory
            Msg.error(p, "Keep the armor in your hand while upgrading. You were refunded.");
            return 0;
        }
        g.setLevel(again, next);
        Msg.send(p, "Upgraded to <white>Warded " + WardedGear.roman(next) + "</white> for " + plugin.money().format(price) + ".");
        return Command.SINGLE_SUCCESS;
    }
}
