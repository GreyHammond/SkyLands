package dev.skyrealms.command;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /skyland: everything happens in the chest menu. */
public final class RealmCommand implements TabExecutor {
    private final SkyRealmsPlugin plugin;

    public RealmCommand(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player p) plugin.menus().main(p);
        else Msg.error(sender, "Players only.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        return List.of();
    }
}
