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
