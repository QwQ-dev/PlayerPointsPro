package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.util.DurationParser;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.command.CommandSender;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Level;

public class SetTemporaryCommand extends BasePointsCommand {

    public SetTemporaryCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @RoseExecutable
    public void execute(CommandContext context, String target, Integer amount,
                        String duration, String silentFlag) {
        PointsUtils.getPlayerByName(target, player -> {
            CommandSender sender = context.getSender();
            if (player == null) {
                this.localeManager.sendCommandMessage(sender,
                        target.startsWith("*") ? "unknown-account" : "unknown-player",
                        StringPlaceholders.of(
                                target.startsWith("*") ? "account" : "player", target));
                return;
            }
            if (amount <= 0) {
                this.localeManager.sendCommandMessage(sender, "invalid-amount");
                return;
            }

            long now = System.currentTimeMillis();
            OptionalLong expiration = GiveTemporaryCommand.resolveExpiration(duration, now);
            if (!expiration.isPresent()) {
                this.localeManager.sendCommandMessage(sender, "invalid-duration");
                return;
            }

            UUID targetId = player.getFirst();
            UUID senderId = PointsUtils.getSenderUUID(sender);
            String targetName = player.getSecond();
            this.rosePlugin.getScheduler().runTaskAsync(() -> {
                boolean updated;
                try {
                    updated = this.api.setTemporary(
                            targetId, senderId, amount, expiration.getAsLong());
                } catch (RuntimeException failure) {
                    this.rosePlugin.getLogger().log(Level.WARNING,
                            "Failed to set temporary PlayerPoints for " + targetId, failure);
                    if (silentFlag == null) {
                        this.rosePlugin.getScheduler().runTask(() ->
                                this.localeManager.sendCommandMessage(
                                        sender, "command-points-update-failure"));
                    }
                    return;
                }
                if (silentFlag != null)
                    return;

                String balance = updated ? this.lookFormattedOrUnknown(targetId) : "?";
                int temporary = updated ? this.api.lookTemporary(targetId) : amount;
                this.rosePlugin.getScheduler().runTask(() -> {
                    if (!updated) {
                        this.localeManager.sendCommandMessage(
                                sender, "command-points-update-failure");
                        return;
                    }
                    this.localeManager.sendCommandMessage(sender,
                            "command-set-temp-success",
                            StringPlaceholders.builder("player", targetName)
                                    .add("amount", PointsUtils.formatPoints(temporary))
                                    .add("balance", balance)
                                    .add("currency", this.localeManager.getCurrencyName(temporary))
                                    .add("duration", DurationParser.formatMillis(
                                            expiration.getAsLong() - now))
                                    .build());
                });
            });
        });
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("set-temp")
                .aliases("settemp")
                .descriptionKey("command-set-temp-description")
                .permission("playerpoints.set-temp")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(
                                PointsUtils::getPlayerTabComplete))
                        .required("amount", ArgumentHandlers.INTEGER)
                        .required("duration", new StringSuggestingArgumentHandler(
                                "30m", "1h", "1d", "7d"))
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
