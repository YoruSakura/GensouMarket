package net.scarletphantasy.gensouMarket.velocity.service;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.scarletphantasy.gensouMarket.velocity.protocol.OutgoingPacket;
import net.scarletphantasy.gensouMarket.velocity.protocol.PacketCodec;
import org.slf4j.Logger;

/**
 * 广播服务：向所有已注册后端服发送消息，可选排除源服。
 */
public class BroadcastService {

    private final Logger logger;
    private final BackendRegistry backendRegistry;
    private final ChannelIdentifier channelId;

    public BroadcastService(Logger logger, BackendRegistry backendRegistry, ChannelIdentifier channelId) {
        this.logger = logger;
        this.backendRegistry = backendRegistry;
        this.channelId = channelId;
    }

    /**
     * 广播给所有已注册后端，排除源服。
     */
    public void broadcast(OutgoingPacket packet) {
        byte[] data = PacketCodec.encode(packet);
        String sourceId = packet.getSourceServerId();

        for (RegisteredServer server : backendRegistry.getAllServers()) {
            String serverId = backendRegistry.getServerId(server.getServerInfo().getName());
            if (sourceId.equals(serverId)) continue;

            if (!server.getPlayersConnected().isEmpty()) {
                server.sendPluginMessage(channelId, data);
            } else {
                logger.debug("跳过无玩家后端: {}", serverId);
            }
        }
    }

    /**
     * 广播给所有已注册后端（含源服）。
     */
    public void broadcastAll(OutgoingPacket packet) {
        byte[] data = PacketCodec.encode(packet);

        for (RegisteredServer server : backendRegistry.getAllServers()) {
            if (!server.getPlayersConnected().isEmpty()) {
                server.sendPluginMessage(channelId, data);
            }
        }
    }

    /**
     * 定向发送到指定后端服。
     */
    public boolean sendTo(String serverId, OutgoingPacket packet) {
        RegisteredServer server = backendRegistry.getServer(serverId);
        if (server == null) {
            logger.warn("目标后端未注册: {}", serverId);
            return false;
        }
        if (server.getPlayersConnected().isEmpty()) {
            logger.warn("目标后端无玩家在线: {}", serverId);
            return false;
        }
        byte[] data = PacketCodec.encode(packet);
        server.sendPluginMessage(channelId, data);
        return true;
    }

    /**
     * 定向发送到玩家所在的后端服。
     */
    public boolean sendToPlayer(PlayerRouteService routeService, java.util.UUID playerUuid, OutgoingPacket packet) {
        return routeService.locatePlayer(playerUuid)
                .map(server -> {
                    byte[] data = PacketCodec.encode(packet);
                    server.sendPluginMessage(channelId, data);
                    return true;
                })
                .orElse(false);
    }
}
