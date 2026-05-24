package net.scarletphantasy.gensouMarket.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import net.scarletphantasy.gensouMarket.velocity.protocol.IncomingPacket;
import net.scarletphantasy.gensouMarket.velocity.protocol.PacketCodec;
import net.scarletphantasy.gensouMarket.velocity.protocol.PacketType;
import net.scarletphantasy.gensouMarket.velocity.service.BackendRegistry;
import net.scarletphantasy.gensouMarket.velocity.service.BroadcastService;
import net.scarletphantasy.gensouMarket.velocity.service.PlayerRouteService;
import org.slf4j.Logger;

import java.io.UncheckedIOException;

/**
 * 处理来自后端服的 Plugin Messaging Channel 消息。
 */
public class PluginMessageListener {

    private final Logger logger;
    private final ChannelIdentifier channelId;
    private final BackendRegistry backendRegistry;
    private final BroadcastService broadcastService;
    private final PlayerRouteService playerRouteService;

    public PluginMessageListener(Logger logger, ChannelIdentifier channelId,
                                 BackendRegistry backendRegistry,
                                 BroadcastService broadcastService,
                                 PlayerRouteService playerRouteService) {
        this.logger = logger;
        this.channelId = channelId;
        this.backendRegistry = backendRegistry;
        this.broadcastService = broadcastService;
        this.playerRouteService = playerRouteService;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channelId.equals(event.getIdentifier())) return;

        // 只处理来自后端服的消息
        if (!(event.getSource() instanceof ServerConnection connection)) return;

        // 标记消息已处理，不再转发给客户端
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        IncomingPacket packet;
        try {
            packet = PacketCodec.decode(event.getData());
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause();
            String detail = cause != null ? cause.getMessage() : e.getMessage();
            logger.warn("无法解码来自 {} 的消息: {} ({} bytes)",
                    connection.getServerInfo().getName(), detail, event.getData().length);
            return;
        }

        logger.debug("收到消息: type={}, source={}, requestId={}",
                packet.type(), packet.sourceServerId(), packet.requestId());

        switch (packet.type()) {
            case SERVER_HELLO -> handleServerHello(packet, connection);
            case SERVER_GOODBYE -> handleServerGoodbye(packet);

            // 广播类事件：转发给所有其他后端
            case MARKET_LISTING_CHANGED,
                 MARKET_LISTING_SOLD,
                 AUCTION_CREATED,
                 AUCTION_BID_UPDATED,
                 AUCTION_ENDED,
                 AUCTION_CANCELLED,
                 MAIL_CREATED,
                 REFRESH_MARKET_VIEW,
                 REFRESH_AUCTION_VIEW,
                 PRESSURE_SYNC,
                 RECYCLE_STOCK_SYNC -> handleBroadcast(packet);

            // 定向投递类：根据目标玩家路由到所在后端
            case PLAYER_NOTIFY,
                 REMOTE_DEPOSIT,
                 REMOTE_GIVE_ITEM -> handleTargeted(packet);

            // 应答类：回发给源服
            case REQUEST_ACK,
                 REQUEST_FAIL -> handleResponse(packet);
        }
    }

    private void handleServerHello(IncomingPacket packet, ServerConnection connection) {
        String serverId = packet.sourceServerId();
        backendRegistry.register(serverId, connection.getServer());
        logger.info("后端服 HELLO: {} ({})", serverId, connection.getServerInfo().getName());
    }

    private void handleServerGoodbye(IncomingPacket packet) {
        String serverId = packet.sourceServerId();
        backendRegistry.unregister(serverId);
        logger.info("后端服 GOODBYE: {}", serverId);
    }

    private void handleBroadcast(IncomingPacket packet) {
        // 重新封装为 OutgoingPacket 并广播
        var outgoing = new net.scarletphantasy.gensouMarket.velocity.protocol.OutgoingPacket(
                packet.type(), packet.requestId(), packet.sourceServerId());
        packet.payload().forEach(outgoing::put);
        broadcastService.broadcast(outgoing);
    }

    private void handleTargeted(IncomingPacket packet) {
        String targetUuid = packet.payload().get("targetPlayerUuid");
        if (targetUuid == null) {
            logger.warn("定向消息缺少 targetPlayerUuid: type={}, requestId={}", packet.type(), packet.requestId());
            sendFail(packet, "missing targetPlayerUuid");
            return;
        }

        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(targetUuid);
        } catch (IllegalArgumentException e) {
            logger.warn("无效的 UUID: {}", targetUuid);
            sendFail(packet, "invalid targetPlayerUuid");
            return;
        }

        var outgoing = new net.scarletphantasy.gensouMarket.velocity.protocol.OutgoingPacket(
                packet.type(), packet.requestId(), packet.sourceServerId());
        packet.payload().forEach(outgoing::put);

        boolean sent = broadcastService.sendToPlayer(playerRouteService, uuid, outgoing);
        if (!sent) {
            logger.debug("目标玩家不在任何已注册后端: {}", targetUuid);
            sendFail(packet, "player not found on any registered backend");
        }
    }

    private void handleResponse(IncomingPacket packet) {
        // 应答消息：转发回请求方
        String targetServerId = packet.payload().get("targetServerId");
        if (targetServerId == null) {
            logger.warn("应答消息缺少 targetServerId: requestId={}", packet.requestId());
            return;
        }

        var outgoing = new net.scarletphantasy.gensouMarket.velocity.protocol.OutgoingPacket(
                packet.type(), packet.requestId(), packet.sourceServerId());
        packet.payload().forEach(outgoing::put);
        broadcastService.sendTo(targetServerId, outgoing);
    }

    private void sendFail(IncomingPacket originalPacket, String reason) {
        var fail = new net.scarletphantasy.gensouMarket.velocity.protocol.OutgoingPacket(
                PacketType.REQUEST_FAIL, originalPacket.requestId(), "velocity");
        fail.put("reason", reason);
        fail.put("targetServerId", originalPacket.sourceServerId());
        broadcastService.sendTo(originalPacket.sourceServerId(), fail);
    }
}
