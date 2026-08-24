package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.util.PointsUtils;

import java.util.OptionalInt;
import java.util.UUID;

public class TakePermanentCommand extends TakeCommand {

    public TakePermanentCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @Override
    protected int getAvailablePoints(UUID playerId) {
        return this.api.lookPermanent(playerId);
    }

    @Override
    protected OptionalInt takePoints(UUID playerId, UUID sourceId, int amount) {
        return this.api.takePermanentAndGetAmount(playerId, sourceId, amount);
    }

    @Override
    protected String getSuccessMessageKey() {
        return "command-take-permanent-success";
    }

    @Override
    protected String getNotEnoughMessageKey() {
        return "command-take-permanent-not-enough";
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("take-permanent")
                .aliases("takeperm", "takepermanent", "permtake")
                .descriptionKey("command-take-permanent-description")
                .permission("playerpoints.take-permanent")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(
                                PointsUtils::getPlayerTabComplete))
                        .required("amount", ArgumentHandlers.INTEGER)
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
