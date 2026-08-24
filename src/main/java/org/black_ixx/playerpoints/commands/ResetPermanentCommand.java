package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.util.PointsUtils;

import java.util.UUID;

public class ResetPermanentCommand extends ResetCommand {

    public ResetPermanentCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @Override
    protected boolean resetPoints(UUID playerId, UUID sourceId) {
        return this.api.resetPermanent(playerId, sourceId);
    }

    @Override
    protected String getSuccessMessageKey() {
        return "command-reset-permanent-success";
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("reset-permanent")
                .aliases("resetperm")
                .descriptionKey("command-reset-permanent-description")
                .permission("playerpoints.reset-permanent")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(
                                PointsUtils::getPlayerTabComplete))
                        .build())
                .build();
    }

}
