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
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Level;

public class GiveTemporaryCommand extends BasePointsCommand {

    public GiveTemporaryCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    static OptionalLong resolveExpiration(String duration, long currentTimeMillis) {
        OptionalLong durationMillis = DurationParser.parseMillis(duration);
        if (!durationMillis.isPresent()) {
            return OptionalLong.empty();
        }

        try {
            return OptionalLong.of(Math.addExact(currentTimeMillis, durationMillis.getAsLong()));
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    @RoseExecutable
    public void execute(CommandContext context, String target, Integer amount, String duration, String silentFlag) {
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

            long currentTime = System.currentTimeMillis();
            OptionalLong expiresAt = resolveExpiration(duration, currentTime);
            if (!expiresAt.isPresent()) {
                this.localeManager.sendCommandMessage(sender, "invalid-duration");
                return;
            }

            long durationMillis = expiresAt.getAsLong() - currentTime;
            UUID targetId = player.getFirst();
            UUID senderId = PointsUtils.getSenderUUID(sender);
            String targetName = player.getSecond();
            this.rosePlugin.getScheduler().runTaskAsync(() -> {
                OptionalInt grantedAmount;
                try {
                    grantedAmount = this.api.giveTemporaryAndGetAmount(
                            targetId, senderId, amount, expiresAt.getAsLong());
                } catch (RuntimeException failure) {
                    this.rosePlugin.getLogger().log(Level.WARNING,
                            "Failed to give temporary PlayerPoints to " + targetId, failure);
                    if (silentFlag == null) {
                        this.rosePlugin.getScheduler().runTask(() ->
                                this.localeManager.sendCommandMessage(
                                        sender, "command-points-update-failure"));
                    }
                    return;
                }
                if (!grantedAmount.isPresent()) {
                    if (silentFlag == null) {
                        this.rosePlugin.getScheduler().runTask(() ->
                                this.localeManager.sendCommandMessage(
                                        sender, "command-points-update-failure"));
                    }
                    return;
                }
                if (silentFlag != null)
                    return;

                String balance = this.lookFormattedOrUnknown(targetId);
                int appliedAmount = grantedAmount.getAsInt();
                this.rosePlugin.getScheduler().runTask(() -> {
                    String formattedAmount = PointsUtils.formatPoints(appliedAmount);
                    String currency = this.localeManager.getCurrencyName(appliedAmount);
                    String formattedDuration = DurationParser.formatMillis(durationMillis);

                    Player onlinePlayer = Bukkit.getPlayer(targetId);
                    if (onlinePlayer != null) {
                        this.localeManager.sendCommandMessage(onlinePlayer,
                                "command-give-temp-received",
                                StringPlaceholders.builder("amount", formattedAmount)
                                        .add("balance", balance)
                                        .add("currency", currency)
                                        .add("duration", formattedDuration)
                                        .build());
                    }

                    this.localeManager.sendCommandMessage(sender, "command-give-temp-success",
                            StringPlaceholders.builder("amount", formattedAmount)
                                    .add("balance", balance)
                                    .add("currency", currency)
                                    .add("duration", formattedDuration)
                                    .add("player", targetName)
                                    .build());
                });
            });
        });
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("give-temp")
                .aliases("givetemp", "tempgive")
                .descriptionKey("command-give-temp-description")
                .permission("playerpoints.give-temp")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(PointsUtils::getPlayerTabComplete))
                        .required("amount", ArgumentHandlers.INTEGER)
                        .required("duration", new StringSuggestingArgumentHandler(
                                "30m", "1h", "1d", "7d"))
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
