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
package dev.skyrealms.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_aqua>[<aqua>SkyRealms</aqua>]</dark_aqua> ";

    private Msg() {}

    public static Component c(String mini) { return MM.deserialize(mini); }

    public static void send(CommandSender to, String mini) { to.sendMessage(MM.deserialize(PREFIX + "<gray>" + mini)); }

    public static void error(CommandSender to, String mini) { to.sendMessage(MM.deserialize(PREFIX + "<red>" + mini)); }
}
