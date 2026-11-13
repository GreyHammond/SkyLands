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
package dev.skyrealms.cosmetic;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.util.Msg;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renaming an island: the player types the new name in chat. Chat works the same for Java and
 * Bedrock (Geyser) players, unlike typing into a GUI.
 */
public final class NamePrompt implements Listener {
    private static final long TIMEOUT_MS = 60_000;
    private final SkyRealmsPlugin plugin;
    private final Map<UUID, Long> waiting = new ConcurrentHashMap<>();

    public NamePrompt(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    public void ask(Player p) {
        waiting.put(p.getUniqueId(), System.currentTimeMillis());
        p.closeInventory();
        Msg.send(p, "Type your island's new name in chat (max " + maxLength() + " characters).");
        p.sendMessage(Msg.c(" <dark_gray>Type <white>cancel</white> to keep the current name, or <white>reset</white> to go back to the default."));
    }

    private int maxLength() { return Math.max(4, plugin.getConfig().getInt("cosmetics.name-max-length", 24)); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Long since = waiting.get(id);
        if (since == null) return;
        waiting.remove(id);
        if (System.currentTimeMillis() - since > TIMEOUT_MS) return; // too late: treat as normal chat
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> rename(e.getPlayer(), text));
    }

    /** Rename the player's island. "cancel" keeps it, "reset" restores the default. Used by chat and by the Bedrock text box. */
    public void rename(Player p, String text) {
        Island i = plugin.service().owned(p);
        if (i == null) return;
        if (text.equalsIgnoreCase("cancel")) { Msg.send(p, "Name unchanged."); return; }
        if (text.equalsIgnoreCase("reset")) {
            i.name = null;
            plugin.islands().save();
            Msg.send(p, "Your island is called <white>" + plugin.cosmetics().displayName(i) + "</white> again.");
            return;
        }
        String clean = text.replaceAll("[<>&§\\\\]", "").replaceAll("\\s+", " ").trim();
        if (clean.length() < 2) { Msg.error(p, "That name is too short."); return; }
        if (clean.length() > maxLength()) { Msg.error(p, "That name is too long (max " + maxLength() + " characters)."); return; }
        String lower = clean.toLowerCase(Locale.ROOT);
        for (String bad : plugin.getConfig().getStringList("cosmetics.banned-words")) {
            if (!bad.isBlank() && lower.contains(bad.toLowerCase(Locale.ROOT))) { Msg.error(p, "That name isn't allowed."); return; }
        }
        i.name = clean;
        plugin.islands().save();
        Msg.send(p, "Your island is now called <white>" + clean + "</white>.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { waiting.remove(e.getPlayer().getUniqueId()); }
}
