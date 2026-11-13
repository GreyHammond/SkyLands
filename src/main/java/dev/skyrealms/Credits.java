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
package dev.skyrealms;

/**
 * Author attribution. Under the additional terms in the NOTICE file (GPL-3.0 section 7(b)),
 * this attribution must be kept wherever it is shown: the in-game menus, the startup message
 * and plugin.yml.
 */
public final class Credits {
    private Credits() {}

    public static final String ATTRIBUTION = "SkyLands by Hammond Digital Studios";
    public static final String LICENSE = "GPL-3.0 with attribution terms (see NOTICE)";
    public static final String WEBSITE = "https://greyhammond.github.io/SkyLands/";

    private static final String[] LOGO = {
            "  ____  _          _                    _     ",
            " / ___|| | ___   _| |    __ _ _ __   __| |___ ",
            " \\___ \\| |/ / | | | |   / _` | '_ \\ / _` / __|",
            "  ___) |   <| |_| | |__| (_| | | | | (_| \\__ \\",
            " |____/|_|\\_\\\\__, |_____\\__,_|_| |_|\\__,_|___/",
            "             |___/                            ",
    };

    /** The startup banner shown in the server console. */
    public static void banner(org.bukkit.command.CommandSender console, String version) {
        net.kyori.adventure.text.format.NamedTextColor aqua = net.kyori.adventure.text.format.NamedTextColor.AQUA;
        String rule = "==================================================";
        console.sendMessage(net.kyori.adventure.text.Component.text(rule, aqua));
        for (String line : LOGO) console.sendMessage(net.kyori.adventure.text.Component.text(line, aqua));
        console.sendMessage(net.kyori.adventure.text.Component.text("  SkyLands ", net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA)
                .append(net.kyori.adventure.text.Component.text("v" + version, net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
        console.sendMessage(net.kyori.adventure.text.Component.text("  Released by ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(net.kyori.adventure.text.Component.text("Hammond Digital Studios", net.kyori.adventure.text.format.NamedTextColor.WHITE)));
        console.sendMessage(net.kyori.adventure.text.Component.text("  " + LICENSE, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        console.sendMessage(net.kyori.adventure.text.Component.text("  " + WEBSITE, net.kyori.adventure.text.format.NamedTextColor.BLUE));
        console.sendMessage(net.kyori.adventure.text.Component.text(rule, aqua));
    }
}
