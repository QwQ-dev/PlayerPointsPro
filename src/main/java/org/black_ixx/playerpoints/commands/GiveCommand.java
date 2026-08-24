package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Level;

public class GiveCommand extends BasePointsCommand {

    public GiveCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @RoseExecutable
    public void execute(CommandContext context, String target, Integer amount, String silentFlag) {
        PointsUtils.getPlayerByName(target, player -> {
            CommandSender sender = context.getSender();
            if (player == null) {
                if (target.startsWith("*")) {
                    this.localeManager.sendCommandMessage(sender, "unknown-account", StringPlaceholders.of("account", target));
                } else {
                    this.localeManager.sendCommandMessage(sender, "unknown-player", StringPlaceholders.of("player", target));
                }
                return;
            }

            if (amount <= 0) {
                this.localeManager.sendCommandMessage(sender, "invalid-amount");
                return;
            }

            UUID targetId = player.getFirst();
            UUID senderId = PointsUtils.getSenderUUID(sender);
            String targetName = player.getSecond();
            this.rosePlugin.getScheduler().runTaskAsync(() -> {
                boolean given;
                try {
                    given = this.api.give(targetId, senderId, amount);
                } catch (RuntimeException failure) {
                    this.rosePlugin.getLogger().log(Level.WARNING,
                            "Failed to give PlayerPoints to " + targetId, failure);
                    if (silentFlag == null) {
                        this.rosePlugin.getScheduler().runTask(() ->
                                this.localeManager.sendCommandMessage(
                                        sender, "command-points-update-failure"));
                    }
                    return;
                }
                if (silentFlag != null)
                    return;

                String balance = given ? this.lookFormattedOrUnknown(targetId) : "?";
                this.rosePlugin.getScheduler().runTask(() -> {
                    if (!given) {
                        this.localeManager.sendCommandMessage(
                                sender, "command-points-update-failure");
                        return;
                    }

                    Player onlinePlayer = Bukkit.getPlayer(targetId);
                    if (onlinePlayer != null) {
                        this.localeManager.sendCommandMessage(onlinePlayer, "command-give-received",
                                StringPlaceholders.builder("amount", PointsUtils.formatPoints(amount))
                                        .add("balance", balance)
                                        .add("currency", this.localeManager.getCurrencyName(amount))
                                        .build());
                    }

                    this.localeManager.sendCommandMessage(sender, "command-give-success",
                            StringPlaceholders.builder("amount", PointsUtils.formatPoints(amount))
                                    .add("balance", balance)
                                    .add("currency", this.localeManager.getCurrencyName(amount))
                                    .add("player", targetName)
                                    .build());
                });
            });
        });
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("give")
                .aliases("give-permanent", "giveperm")
                .descriptionKey("command-give-description")
                .permission("playerpoints.give")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(PointsUtils::getPlayerTabComplete))
                        .required("amount", ArgumentHandlers.INTEGER)
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
