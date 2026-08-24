package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.command.CommandSender;

import java.util.UUID;
import java.util.logging.Level;

public class ResetCommand extends BasePointsCommand {

    public ResetCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @RoseExecutable
    public void execute(CommandContext context, String target) {
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

            UUID targetId = player.getFirst();
            UUID senderId = PointsUtils.getSenderUUID(sender);
            String targetName = player.getSecond();
            this.rosePlugin.getScheduler().runTaskAsync(() -> {
                boolean reset;
                try {
                    reset = this.resetPoints(targetId, senderId);
                } catch (RuntimeException failure) {
                    this.rosePlugin.getLogger().log(Level.WARNING,
                            "Failed to reset PlayerPoints for " + targetId, failure);
                    this.rosePlugin.getScheduler().runTask(() ->
                            this.localeManager.sendCommandMessage(
                                    sender, "command-points-update-failure"));
                    return;
                }
                String balance = reset ? this.lookFormattedOrUnknown(targetId) : "?";
                this.rosePlugin.getScheduler().runTask(() -> {
                    if (!reset) {
                        this.localeManager.sendCommandMessage(
                                sender, "command-points-update-failure");
                        return;
                    }

                    this.localeManager.sendCommandMessage(sender, this.getSuccessMessageKey(),
                            StringPlaceholders.builder("player", targetName)
                                    .add("balance", balance)
                                    .add("currency", this.localeManager.getCurrencyName(0))
                                    .build());
                });
            });
        });
    }

    protected boolean resetPoints(UUID playerId, UUID sourceId) {
        return this.api.reset(playerId, sourceId);
    }

    protected String getSuccessMessageKey() {
        return "command-reset-success";
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("reset")
                .descriptionKey("command-reset-description")
                .permission("playerpoints.reset")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(PointsUtils::getPlayerTabComplete))
                        .build())
                .build();
    }

}
