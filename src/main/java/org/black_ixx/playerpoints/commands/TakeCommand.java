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

import java.util.OptionalInt;
import java.util.UUID;
import java.util.logging.Level;

public class TakeCommand extends BasePointsCommand {

    public TakeCommand(PlayerPoints playerPoints) {
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
                int availablePoints;
                OptionalInt takenAmount;
                try {
                    availablePoints = this.getAvailablePoints(targetId);
                    takenAmount = this.takePoints(targetId, senderId, amount);
                } catch (RuntimeException failure) {
                    this.rosePlugin.getLogger().log(Level.WARNING,
                            "Failed to take PlayerPoints", failure);
                    if (silentFlag == null) {
                        this.rosePlugin.getScheduler().runTask(() ->
                                this.localeManager.sendCommandMessage(
                                        sender, "command-points-update-failure"));
                    }
                    return;
                }

                if (silentFlag != null)
                    return;

                boolean taken = takenAmount.isPresent();
                int appliedAmount = taken ? takenAmount.getAsInt() : amount;
                String balance = taken ? this.lookFormattedOrUnknown(targetId) : "?";
                this.rosePlugin.getScheduler().runTask(() -> {
                    if (!taken) {
                        if (availablePoints < amount) {
                            this.localeManager.sendCommandMessage(sender,
                                    this.getNotEnoughMessageKey(),
                                    StringPlaceholders.builder("player", targetName)
                                            .add("currency", this.localeManager.getCurrencyName(amount))
                                            .build());
                        } else {
                            this.localeManager.sendCommandMessage(
                                    sender, "command-points-update-failure");
                        }
                        return;
                    }

                    StringPlaceholders placeholders = StringPlaceholders.builder(
                                    "player", targetName)
                            .add("balance", balance)
                            .add("currency", this.localeManager.getCurrencyName(appliedAmount))
                            .add("amount", PointsUtils.formatPoints(appliedAmount))
                            .build();
                    this.localeManager.sendCommandMessage(
                            sender, this.getSuccessMessageKey(), placeholders);

                    Player onlinePlayer = Bukkit.getPlayer(targetId);
                    if (onlinePlayer != null) {
                        this.localeManager.sendCommandMessage(
                                onlinePlayer, "command-take-taken", placeholders);
                    }
                });
            });
        });
    }

    protected int getAvailablePoints(UUID playerId) {
        return this.api.look(playerId);
    }

    protected OptionalInt takePoints(UUID playerId, UUID sourceId, int amount) {
        return this.api.takeAndGetAmount(playerId, sourceId, amount);
    }

    protected String getSuccessMessageKey() {
        return "command-take-success";
    }

    protected String getNotEnoughMessageKey() {
        return "command-take-not-enough";
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("take")
                .descriptionKey("command-take-description")
                .permission("playerpoints.take")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(PointsUtils::getPlayerTabComplete))
                        .required("amount", ArgumentHandlers.INTEGER)
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
