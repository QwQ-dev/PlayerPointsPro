package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.models.DetailedPointsBalance;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.command.CommandSender;

public class LookCommand extends BasePointsCommand {

    public LookCommand(PlayerPoints playerPoints) {
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

            DetailedPointsBalance balance = this.api.lookDetailed(player.getFirst());
            int amount = balance.getTotal();
            this.localeManager.sendCommandMessage(sender, "command-look-success",
                    StringPlaceholders.builder("player", player.getSecond())
                            .add("amount", PointsUtils.formatPoints(amount))
                            .add("permanent", PointsUtils.formatPoints(balance.getPermanent()))
                            .add("temporary", PointsUtils.formatPoints(balance.getTemporary()))
                            .add("currency", this.localeManager.getCurrencyName(amount))
                            .build());
            this.sendBalanceDetails(sender, balance);
        });
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("look")
                .descriptionKey("command-look-description")
                .permission("playerpoints.look")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(PointsUtils::getPlayerTabComplete))
                        .build())
                .build();
    }

}
