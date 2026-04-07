package net.scarletphantasy.gensouMarket.bridge.protocol;

import java.util.Map;

/**
 * 从 Velocity 收到的已解码消息包。
 */
public record IncomingPacket(
        short protocolVersion,
        PacketType type,
        String requestId,
        long timestamp,
        String sourceServerId,
        Map<String, String> payload
) {
}
