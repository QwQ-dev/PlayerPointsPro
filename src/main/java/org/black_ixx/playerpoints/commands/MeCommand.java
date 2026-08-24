package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.manager.LocaleManager;
import org.black_ixx.playerpoints.models.DetailedPointsBalance;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MeCommand extends BasePointsCommand {

    public MeCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @RoseExecutable
    public void execute(CommandContext context) {
        CommandSender sender = context.getSender();
        LocaleManager localeManager = this.rosePlugin.getManager(LocaleManager.class);
        DetailedPointsBalance balance = this.api.lookDetailed(
                ((Player) sender).getUniqueId());
        int amount = balance.getTotal();
        localeManager.sendCommandMessage(sender, "command-me-success",
                StringPlaceholders.builder("amount", PointsUtils.formatPoints(amount))
                        .add("permanent", PointsUtils.formatPoints(balance.getPermanent()))
                        .add("temporary", PointsUtils.formatPoints(balance.getTemporary()))
                        .add("currency", localeManager.getCurrencyName(amount))
                        .build());
        this.sendBalanceDetails(sender, balance);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("me")
                .descriptionKey("command-me-description")
                .permission("playerpoints.me")
                .playerOnly()
                .build();
    }

}
