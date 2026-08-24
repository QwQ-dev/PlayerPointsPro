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
import org.bukkit.command.CommandSender;

import java.util.UUID;
import java.util.logging.Level;

public class SetCommand extends BasePointsCommand {

    public SetCommand(PlayerPoints playerPoints) {
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

            if (amount < 0) {
                this.localeManager.sendCommandMessage(sender, "invalid-amount");
                return;
            }

            UUID targetId = player.getFirst();
            UUID senderId = PointsUtils.getSenderUUID(sender);
            String targetName = player.getSecond();
            this.rosePlugin.getScheduler().runTaskAsync(() -> {
                boolean updated;
                try {
                    updated = this.setPoints(targetId, senderId, amount);
                } catch (RuntimeException failure) {
                    this.rosePlugin.getLogger().log(Level.WARNING,
                            "Failed to set PlayerPoints for " + targetId, failure);
                    if (silentFlag == null) {
                        this.rosePlugin.getScheduler().runTask(() ->
                                this.localeManager.sendCommandMessage(
                                        sender, "command-points-update-failure"));
                    }
                    return;
                }
                if (silentFlag != null)
                    return;

                int appliedAmount = updated ? this.getSetAmount(targetId) : amount;
                String balance = updated ? this.lookFormattedOrUnknown(targetId) : "?";
                this.rosePlugin.getScheduler().runTask(() -> {
                    if (!updated) {
                        this.localeManager.sendCommandMessage(
                                sender, "command-points-update-failure");
                        return;
                    }

                    this.localeManager.sendCommandMessage(sender, this.getSuccessMessageKey(),
                            StringPlaceholders.builder("player", targetName)
                                    .add("balance", balance)
                                    .add("currency", this.localeManager.getCurrencyName(appliedAmount))
                                    .add("amount", PointsUtils.formatPoints(appliedAmount))
                                    .build());
                });
            });
        });
    }

    protected boolean setPoints(UUID playerId, UUID sourceId, int amount) {
        return this.api.set(playerId, sourceId, amount);
    }

    protected int getSetAmount(UUID playerId) {
        return this.api.look(playerId);
    }

    protected String getSuccessMessageKey() {
        return "command-set-success";
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("set")
                .descriptionKey("command-set-description")
                .permission("playerpoints.set")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(PointsUtils::getPlayerTabComplete))
                        .required("amount", ArgumentHandlers.INTEGER)
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
