package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.database.MySQLConnector;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.database.PointsBackup;
import org.black_ixx.playerpoints.database.PointsBackupParser;
import org.black_ixx.playerpoints.manager.DataManager;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.logging.Level;

public class ImportCommand extends BasePointsCommand {

    public ImportCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @RoseExecutable
    public void execute(CommandContext context, String confirm) {
        CommandSender sender = context.getSender();
        File file = new File(this.rosePlugin.getDataFolder(), "storage.yml");
        if (!file.exists()) {
            this.localeManager.sendMessage(sender, "command-import-no-backup");
            return;
        }

        DataManager dataManager = this.rosePlugin.getManager(DataManager.class);
        if (confirm == null) {
            String databaseType = dataManager.getDatabaseConnector() instanceof MySQLConnector ? "MySQL" : "SQLite";
            this.localeManager.sendMessage(sender, "command-import-warning", StringPlaceholders.of("type", databaseType));
            return;
        }

        this.rosePlugin.getScheduler().runTaskAsync(() -> {
            boolean imported;
            try {
                PointsBackup backup = PointsBackupParser.load(file, System.currentTimeMillis());
                imported = dataManager.importDataWithResult(backup.getPermanentPoints(),
                        backup.getUsernames(), backup.getTemporaryGrants());
            } catch (Exception e) {
                this.rosePlugin.getLogger().log(Level.SEVERE,
                        "Unable to import storage.yml; existing data was preserved.", e);
                this.localeManager.sendCommandMessage(sender, "command-import-failure");
                return;
            }

            if (imported) {
                this.localeManager.sendCommandMessage(sender, "command-import-success");
            } else {
                this.rosePlugin.getLogger().warning(
                        "storage.yml was rejected; existing data was preserved.");
                this.localeManager.sendCommandMessage(sender, "command-import-failure");
            }
        });
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("import")
                .descriptionKey("command-import-description")
                .permission("playerpoints.import")
                .arguments(ArgumentsDefinition.builder()
                        .optional("confirm", ArgumentHandlers.forValues(String.class, "confirm"))
                        .build())
                .build();
    }

}
