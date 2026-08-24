package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.commands.arguments.StringSuggestingArgumentHandler;
import org.black_ixx.playerpoints.util.PointsUtils;

import java.util.UUID;

public class ResetTemporaryCommand extends ResetCommand {

    public ResetTemporaryCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @Override
    protected boolean resetPoints(UUID playerId, UUID sourceId) {
        return this.api.resetTemporary(playerId, sourceId);
    }

    @Override
    protected String getSuccessMessageKey() {
        return "command-reset-temp-success";
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("reset-temp")
                .aliases("resettemp")
                .descriptionKey("command-reset-temp-description")
                .permission("playerpoints.reset-temp")
                .arguments(ArgumentsDefinition.builder()
                        .required("target", new StringSuggestingArgumentHandler(
                                PointsUtils::getPlayerTabComplete))
                        .build())
                .build();
    }

}
