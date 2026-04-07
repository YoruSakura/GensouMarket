package net.scarletphantasy.gensouMarket.velocity.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向后端服发送的消息包构建器。
 */
public class OutgoingPacket {

    private static final short PROTOCOL_VERSION = 1;

    private final PacketType type;
    private final String requestId;
    private final String sourceServerId;
    private final Map<String, String> payload = new LinkedHashMap<>();

    public OutgoingPacket(PacketType type, String requestId, String sourceServerId) {
        this.type = type;
        this.requestId = requestId;
        this.sourceServerId = sourceServerId;
    }

    public OutgoingPacket put(String key, String value) {
        payload.put(key, value);
        return this;
    }

    public short getProtocolVersion() {
        return PROTOCOL_VERSION;
    }

    public PacketType getType() {
        return type;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getSourceServerId() {
        return sourceServerId;
    }

    public Map<String, String> getPayload() {
        return payload;
    }
}
