package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.manager.DataManager;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GiveAllCommand extends BasePointsCommand {

    public GiveAllCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @RoseExecutable
    public void execute(CommandContext context, Integer amount, String includeOffline, String silentFlag) {
        DataManager dataManager = this.rosePlugin.getManager(DataManager.class);
        CommandSender sender = context.getSender();
        UUID senderId = PointsUtils.getSenderUUID(sender);
        List<UUID> onlinePlayerIds = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toList());
        this.rosePlugin.getScheduler().runTaskAsync(() -> {
            List<UUID> updatedPlayerIds = new ArrayList<>();
            boolean updatedAll;
            if (includeOffline != null) {
                try {
                    updatedAll = dataManager.offsetAllPointsWithResult(amount);
                } catch (RuntimeException failure) {
                    this.rosePlugin.getLogger().log(Level.WARNING,
                            "Failed to update all PlayerPoints accounts", failure);
                    updatedAll = false;
                }
                if (updatedAll)
                    updatedPlayerIds.addAll(onlinePlayerIds);
            } else {
                for (UUID playerId : onlinePlayerIds) {
                    try {
                        if (this.api.give(playerId, senderId, amount))
                            updatedPlayerIds.add(playerId);
                    } catch (RuntimeException failure) {
                        this.rosePlugin.getLogger().log(Level.WARNING,
                                "Failed to update PlayerPoints for " + playerId, failure);
                    }
                }
                updatedAll = updatedPlayerIds.size() == onlinePlayerIds.size();
            }
            if (!updatedAll) {
                this.rosePlugin.getLogger().warning(
                        "Updated " + updatedPlayerIds.size() + " of " + onlinePlayerIds.size()
                                + " online accounts for /points giveall");
            }

            if (silentFlag != null)
                return;

            Map<UUID, String> balances = new HashMap<>();
            for (UUID playerId : updatedPlayerIds)
                balances.put(playerId, this.lookFormattedOrUnknown(playerId));

            boolean allUpdated = updatedAll;
            this.rosePlugin.getScheduler().runTask(() -> {
                for (Map.Entry<UUID, String> entry : balances.entrySet()) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player == null)
                        continue;
                    this.localeManager.sendCommandMessage(player, "command-give-received",
                            StringPlaceholders.builder("amount", PointsUtils.formatPoints(amount))
                                    .add("balance", entry.getValue())
                                    .add("currency", this.localeManager.getCurrencyName(amount))
                                    .build());
                }

                if (allUpdated) {
                    this.localeManager.sendCommandMessage(sender, "command-giveall-success",
                            StringPlaceholders.builder("amount", PointsUtils.formatPoints(amount))
                                    .add("currency", this.localeManager.getCurrencyName(amount))
                                    .build());
                } else if (updatedPlayerIds.isEmpty()) {
                    this.localeManager.sendCommandMessage(
                            sender, "command-points-update-failure");
                } else {
                    this.localeManager.sendCommandMessage(sender, "command-giveall-partial",
                            StringPlaceholders.builder("amount", PointsUtils.formatPoints(amount))
                                    .add("currency", this.localeManager.getCurrencyName(amount))
                                    .add("success", updatedPlayerIds.size())
                                    .add("total", onlinePlayerIds.size())
                                    .build());
                }
            });
        });
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("giveall")
                .descriptionKey("command-giveall-description")
                .permission("playerpoints.giveall")
                .arguments(ArgumentsDefinition.builder()
                        .required("amount", ArgumentHandlers.INTEGER)
                        .optional("*", ArgumentHandlers.forValues(String.class, "*"))
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
