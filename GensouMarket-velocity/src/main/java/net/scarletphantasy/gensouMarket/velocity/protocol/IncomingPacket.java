package net.scarletphantasy.gensouMarket.velocity.protocol;

import java.util.Map;

/**
 * 从后端服收到的已解码消息包。
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
