package org.black_ixx.playerpoints.commands;

import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.database.PointsBackup;
import org.black_ixx.playerpoints.database.PointsBackupParser;
import org.black_ixx.playerpoints.manager.DataManager;
import org.black_ixx.playerpoints.models.TemporaryPointGrant;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class ExportCommand extends BasePointsCommand {

    public ExportCommand(PlayerPoints playerPoints) {
        super(playerPoints);
    }

    @RoseExecutable
    public void execute(CommandContext context, String confirm) {
        this.rosePlugin.getScheduler().runTaskAsync(() -> {
            CommandSender sender = context.getSender();
            File file = new File(this.rosePlugin.getDataFolder(), "storage.yml");
            if (file.exists() && confirm == null) {
                this.localeManager.sendCommandMessage(sender, "command-export-warning");
                return;
            }

            DataManager dataManager = this.rosePlugin.getManager(DataManager.class);
            PointsBackup snapshot;
            try {
                snapshot = dataManager.getBackupSnapshot();
            } catch (RuntimeException e) {
                this.rosePlugin.getLogger().log(Level.SEVERE,
                        "Unable to load a consistent points export snapshot.", e);
                this.localeManager.sendCommandMessage(sender, "command-export-failure");
                return;
            }
            Map<UUID, Integer> permanentPoints = snapshot.getPermanentPoints();
            FileConfiguration configuration = new YamlConfiguration();
            configuration.set("Backup-Version", PointsBackupParser.CURRENT_VERSION);
            ConfigurationSection pointsSection = configuration.createSection("Points");
            ConfigurationSection uuidSection = configuration.createSection("UUIDs");
            ConfigurationSection temporaryPointsSection = configuration.createSection("TemporaryPoints");

            for (Map.Entry<UUID, Integer> entry : permanentPoints.entrySet())
                pointsSection.set(entry.getKey().toString(), entry.getValue());

            for (Map.Entry<UUID, String> entry : snapshot.getUsernames().entrySet())
                uuidSection.set(entry.getKey().toString(), entry.getValue());

            for (TemporaryPointGrant grant : snapshot.getTemporaryGrants()) {
                ConfigurationSection grantSection = temporaryPointsSection.createSection(grant.getGrantId().toString());
                grantSection.set("uuid", grant.getPlayerId().toString());
                grantSection.set("amount", grant.getAmount());
                grantSection.set("expires-at", grant.getExpiresAt());
            }

            try {
                AtomicFileWriter.replace(file, temporary -> {
                    configuration.save(temporary);
                    PointsBackupParser.load(temporary, System.currentTimeMillis());
                });
            } catch (IOException e) {
                this.rosePlugin.getLogger().log(Level.SEVERE,
                        "Unable to write storage.yml; the previous backup was preserved.", e);
                this.localeManager.sendCommandMessage(sender, "command-export-failure");
                return;
            }

            this.localeManager.sendCommandMessage(sender, "command-export-success");
        });
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("export")
                .descriptionKey("command-export-description")
                .permission("playerpoints.export")
                .arguments(ArgumentsDefinition.builder()
                        .optional("confirm", ArgumentHandlers.forValues(String.class, "confirm"))
                        .build())
                .build();
    }

}
