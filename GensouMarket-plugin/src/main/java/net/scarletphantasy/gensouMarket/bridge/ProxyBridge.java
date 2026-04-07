package net.scarletphantasy.gensouMarket.bridge;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.protocol.IncomingPacket;
import net.scarletphantasy.gensouMarket.bridge.protocol.OutgoingPacket;
import net.scarletphantasy.gensouMarket.bridge.protocol.PacketCodec;
import net.scarletphantasy.gensouMarket.bridge.protocol.PacketType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit Plugin Messaging Channel 桥接层。
 * 负责与 Velocity 代理端的消息收发。
 */
public class ProxyBridge implements PluginMessageListener {

    private final GensouMarket plugin;
    private final String channel;
    private final String serverId;
    private record PendingRequestContext(
            Consumer<IncomingPacket> onAck,
            Consumer<IncomingPacket> onFail,
            BukkitTask timeoutTask
    ) {}

    private final Map<String, PendingRequestContext> pendingRequests = new ConcurrentHashMap<>();
    private RemoteActionHandler remoteActionHandler;

    public ProxyBridge(GensouMarket plugin, String channel, String serverId) {
        this.plugin = plugin;
        this.channel = channel;
        this.serverId = serverId;
    }

    public void enable() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, channel, this);
        plugin.getLogger().info("已注册跨服消息通道: " + channel + " (serverId=" + serverId + ")");
    }

    public void disable() {
        if (plugin.isEnabled()) {
            sendGoodbye();
        }
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
        for (PendingRequestContext context : pendingRequests.values()) {
            if (context.timeoutTask() != null) {
                context.timeoutTask().cancel();
            }
        }
        pendingRequests.clear();
    }

    public void setRemoteActionHandler(RemoteActionHandler handler) {
        this.remoteActionHandler = handler;
    }

    /**
     * 发送 SERVER_HELLO，应在有玩家加入后调用。
     */
    public void sendHello() {
        OutgoingPacket packet = new OutgoingPacket(PacketType.SERVER_HELLO, newRequestId(), serverId);
        send(packet);
    }

    /**
     * 发送 SERVER_GOODBYE。
     */
    public void sendGoodbye() {
        OutgoingPacket packet = new OutgoingPacket(PacketType.SERVER_GOODBYE, newRequestId(), serverId);
        send(packet);
    }

    /**
     * 发送消息到 Velocity。需要至少一个在线玩家。
     */
    public boolean send(OutgoingPacket packet) {
        if (!plugin.isEnabled()) {
            plugin.getLogger().fine("无法发送跨服消息（插件已禁用）: " + packet.getType());
            return false;
        }
        Player player = pickOnlinePlayer();
        if (player == null) {
            plugin.getLogger().fine("无法发送跨服消息（无在线玩家）: " + packet.getType());
            return false;
        }
        byte[] data = PacketCodec.encode(packet);
        try {
            player.sendPluginMessage(plugin, channel, data);
            return true;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.FINE, "发送跨服消息失败: " + packet.getType(), e);
            return false;
        }
    }

    public boolean sendWithFailHandler(OutgoingPacket packet, Consumer<IncomingPacket> onFail) {
        return sendWithHandlers(packet, null, onFail, false);
    }

    public boolean sendWithResponseHandlers(OutgoingPacket packet,
                                            Consumer<IncomingPacket> onAck,
                                            Consumer<IncomingPacket> onFail) {
        return sendWithHandlers(packet, onAck, onFail, true);
    }

    public void sendAck(String requestId, String targetServerId) {
        OutgoingPacket packet = new OutgoingPacket(PacketType.REQUEST_ACK, requestId, serverId);
        packet.put("targetServerId", targetServerId);
        send(packet);
    }

    public void sendFail(String requestId, String targetServerId, String reason) {
        OutgoingPacket packet = new OutgoingPacket(PacketType.REQUEST_FAIL, requestId, serverId);
        packet.put("targetServerId", targetServerId);
        packet.put("reason", reason);
        send(packet);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!this.channel.equals(channel)) return;

        IncomingPacket packet;
        try {
            packet = PacketCodec.decode(message);
        } catch (UncheckedIOException e) {
            plugin.getLogger().log(Level.WARNING, "无法解码来自 Velocity 的消息", e);
            return;
        }

        plugin.getLogger().fine("收到跨服消息: type=" + packet.type() + ", source=" + packet.sourceServerId());

        if (packet.type() == PacketType.REQUEST_FAIL || packet.type() == PacketType.REQUEST_ACK) {
            PendingRequestContext context = pendingRequests.remove(packet.requestId());
            if (context != null) {
                if (context.timeoutTask() != null) {
                    context.timeoutTask().cancel();
                }
                if (packet.type() == PacketType.REQUEST_ACK && context.onAck() != null) {
                    context.onAck().accept(packet);
                    return;
                }
                if (packet.type() == PacketType.REQUEST_FAIL && context.onFail() != null) {
                    context.onFail().accept(packet);
                    return;
                }
                return;
            }
        }

        if (remoteActionHandler != null) {
            remoteActionHandler.handle(packet);
        }
    }

    public String getServerId() {
        return serverId;
    }

    public String newRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Player pickOnlinePlayer() {
        var players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return null;
        return players.iterator().next();
    }

    private boolean sendWithHandlers(OutgoingPacket packet,
                                     Consumer<IncomingPacket> onAck,
                                     Consumer<IncomingPacket> onFail,
                                     boolean failOnTimeout) {
        PendingRequestContext context = null;
        if (onAck != null || onFail != null) {
            BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PendingRequestContext removed = pendingRequests.remove(packet.getRequestId());
                if (failOnTimeout && removed != null && removed.onFail() != null) {
                    removed.onFail().accept(null);
                }
            }, 200L);
            context = new PendingRequestContext(onAck, onFail, timeoutTask);
            pendingRequests.put(packet.getRequestId(), context);
        }

        boolean sent = send(packet);
        if (!sent && context != null) {
            pendingRequests.remove(packet.getRequestId());
            context.timeoutTask().cancel();
            if (context.onFail() != null) {
                context.onFail().accept(null);
            }
        }
        return sent;
    }
}
