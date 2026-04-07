package net.scarletphantasy.gensouMarket.bridge.protocol;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息编解码器。须与 Velocity 端保持一致。
 *
 * 二进制格式:
 *   protocolVersion  (short)
 *   messageType      (byte)
 *   requestId        (UTF)
 *   timestamp        (long)
 *   sourceServerId   (UTF)
 *   payloadSize      (short)
 *   [key (UTF), value (UTF)] * payloadSize
 */
public final class PacketCodec {

    private PacketCodec() {}

    public static byte[] encode(OutgoingPacket packet) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(baos);

            out.writeShort(packet.getProtocolVersion());
            out.writeByte(packet.getType().getId());
            out.writeUTF(packet.getRequestId());
            out.writeLong(System.currentTimeMillis());
            out.writeUTF(packet.getSourceServerId());

            Map<String, String> payload = packet.getPayload();
            out.writeShort(payload.size());
            for (Map.Entry<String, String> entry : payload.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }

            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode packet", e);
        }
    }

    public static IncomingPacket decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

            short protocolVersion = in.readShort();
            byte typeId = in.readByte();
            PacketType type = PacketType.fromId(typeId);
            if (type == null) {
                throw new IOException("Unknown packet type: 0x" + String.format("%02X", typeId));
            }

            String requestId = in.readUTF();
            long timestamp = in.readLong();
            String sourceServerId = in.readUTF();

            short payloadSize = in.readShort();
            Map<String, String> payload = new LinkedHashMap<>(payloadSize);
            for (int i = 0; i < payloadSize; i++) {
                String key = in.readUTF();
                String value = in.readUTF();
                payload.put(key, value);
            }

            return new IncomingPacket(protocolVersion, type, requestId, timestamp, sourceServerId, payload);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode packet", e);
        }
    }
}
