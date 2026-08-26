package org.black_ixx.playerpoints.listeners;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import dev.rosewood.rosegarden.RosePlugin;
import org.black_ixx.playerpoints.manager.DataManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class PointsMessageListener implements PluginMessageListener {

    public static final String CHANNEL = "BungeeCord";
    public static final String REFRESH_SUBCHANNEL = "playerpoints:refresh";
    public static final String REFRESH_ALL_SUBCHANNEL = "playerpoints:refresh-all";
    private static final int UUID_TEXT_LENGTH = 36;
    private final DataManager dataManager;

    public PointsMessageListener(RosePlugin rosePlugin) {
        this.dataManager = rosePlugin.getManager(DataManager.class);
    }

    @Override
    public void onPluginMessageReceived(@NonNull String channel, @NonNull Player attachedPlayer, byte[] message) {
        if (!CHANNEL.equals(channel) || message == null)
            return;

        ByteArrayDataInput input = ByteStreams.newDataInput(message);
        String subchannel;
        try {
            subchannel = input.readUTF();
        } catch (RuntimeException ignored) {
            return;
        }
        if (subchannel.equals(REFRESH_SUBCHANNEL)) {
            UUID uuid = this.readRefreshUuid(input);
            if (uuid != null)
                this.dataManager.refreshPoints(uuid);
        } else if (subchannel.equals(REFRESH_ALL_SUBCHANNEL)) {
            this.dataManager.refreshAllPoints();
        }
    }

    private UUID readRefreshUuid(ByteArrayDataInput input) {
        try {
            int length = input.readUnsignedShort();
            if (length != UUID_TEXT_LENGTH)
                return null;

            byte[] data = new byte[length];
            input.readFully(data);
            return UUID.fromString(new String(data, StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

}
