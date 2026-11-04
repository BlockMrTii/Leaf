package org.dreeam.leaf.command.subcommands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.dreeam.leaf.command.LeafCommand;
import org.dreeam.leaf.command.PermissionedLeafSubcommand;
import org.dreeam.leaf.world.redstone.RedstoneBarTask;

import java.util.Collections;
import java.util.List;

public final class RedstoneBarCommand extends PermissionedLeafSubcommand {

    public static final String LITERAL_ARGUMENT = "redstonebar";
    public static final String PERM = LeafCommand.BASE_PERM + "." + LITERAL_ARGUMENT;

    public RedstoneBarCommand() {
        super(PERM, PermissionDefault.OP);
    }

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        boolean enabled = RedstoneBarTask.toggle(player);
        player.sendMessage(Component.text("Redstone bar: ")
            .append(Component.text(enabled ? "on" : "off", enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String subCommand, final String[] args) {
        return Collections.emptyList();
    }
}
