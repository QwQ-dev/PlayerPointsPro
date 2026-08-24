package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.framework.BaseRoseCommand;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.black_ixx.playerpoints.manager.LocaleManager;
import org.black_ixx.playerpoints.models.DetailedPointsBalance;
import org.black_ixx.playerpoints.models.TemporaryPointGrant;
import org.black_ixx.playerpoints.util.ExpirationFormatter;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.command.CommandSender;

import java.util.UUID;
import java.util.logging.Level;

public abstract class BasePointsCommand extends BaseRoseCommand {

    protected final PlayerPoints playerPoints;
    protected final PlayerPointsAPI api;
    protected final LocaleManager localeManager;

    public BasePointsCommand(PlayerPoints playerPoints) {
        super(playerPoints);
        this.playerPoints = playerPoints;
        this.api = playerPoints.getAPI();
        this.localeManager = playerPoints.getManager(LocaleManager.class);
    }

    protected String lookFormattedOrUnknown(UUID playerId) {
        try {
            return PointsUtils.formatPoints(this.api.look(playerId));
        } catch (RuntimeException failure) {
            this.rosePlugin.getLogger().log(Level.WARNING,
                    "Unable to read the updated PlayerPoints balance for " + playerId, failure);
            return "?";
        }
    }

    protected void sendBalanceDetails(
            CommandSender sender, DetailedPointsBalance balance) {
        this.localeManager.sendCommandMessage(sender,
                "command-balance-breakdown",
                StringPlaceholders.builder(
                                "permanent", PointsUtils.formatPoints(balance.getPermanent()))
                        .add("temporary", PointsUtils.formatPoints(balance.getTemporary()))
                        .build());
        for (TemporaryPointGrant grant : balance.getTemporaryGrants()) {
            int amount = grant.getAmount();
            this.localeManager.sendCommandMessage(sender,
                    "command-temporary-grant-entry",
                    StringPlaceholders.builder(
                                    "amount", PointsUtils.formatPoints(amount))
                            .add("currency", this.localeManager.getCurrencyName(amount))
                            .add("expiration", ExpirationFormatter.format(grant.getExpiresAt()))
                            .build());
        }
    }

}
