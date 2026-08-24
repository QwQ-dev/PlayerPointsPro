package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.util.PointsUtils;

import java.util.UUID;

public class SetPermanentCommand extends SetCommand {

    public SetPermanentCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @Override
    protected boolean setPoints(UUID playerId, UUID sourceId, int amount) {
        return this.api.setPermanent(playerId, sourceId, amount);
    }

    @Override
    protected int getSetAmount(UUID playerId) {
        return this.api.lookPermanent(playerId);
    }

    @Override
    protected String getSuccessMessageKey() {
        return "command-set-permanent-success";
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("set-permanent")
                .aliases("setperm")
                .descriptionKey("command-set-permanent-description")
                .permission("playerpoints.set-permanent")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(
                                PointsUtils::getPlayerTabComplete))
                        .required("amount", ArgumentHandlers.INTEGER)
                        .optional("-s", ArgumentHandlers.forValues(String.class, "-s"))
                        .build())
                .build();
    }

}
