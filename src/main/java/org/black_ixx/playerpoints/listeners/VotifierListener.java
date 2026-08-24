package org.black_ixx.playerpoints.listeners;

import com.vexsoftware.votifier.model.VotifierEvent;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.config.SettingKey;
import org.black_ixx.playerpoints.manager.LocaleManager;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;
import java.util.logging.Level;

public class VotifierListener implements Listener {

    private final PlayerPoints plugin;

    public VotifierListener(PlayerPoints plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void vote(VotifierEvent event) {
        if (event.getVote().getUsername() == null)
            return;

        String name = event.getVote().getUsername();
        PointsUtils.getPlayerByName(name, playerInfo -> {
            if (playerInfo == null)
                return;

            int amount = SettingKey.VOTE_AMOUNT.get();
            Player player = Bukkit.getPlayer(playerInfo.getFirst());

            if (!SettingKey.VOTE_ONLINE.get() || player != null) {
                UUID playerId = playerInfo.getFirst();
                String serviceName = event.getVote().getServiceName();
                this.plugin.getScheduler().runTaskAsync(() -> {
                    boolean granted;
                    try {
                        granted = this.plugin.getAPI().give(playerId, amount);
                    } catch (RuntimeException failure) {
                        this.plugin.getLogger().log(Level.WARNING,
                                "Unable to grant vote points to " + playerId, failure);
                        return;
                    }
                    if (!granted) {
                        this.plugin.getLogger().warning(
                                "Unable to grant vote points to " + playerId);
                        return;
                    }

                    this.plugin.getScheduler().runTask(() -> {
                        Player onlinePlayer = Bukkit.getPlayer(playerId);
                        if (onlinePlayer == null)
                            return;
                        this.plugin.getManager(LocaleManager.class).sendMessage(
                                onlinePlayer, "votifier-voted",
                                StringPlaceholders.builder("service", serviceName)
                                        .add("amount", amount)
                                        .build());
                    });
                });
            }
        });
    }
}
