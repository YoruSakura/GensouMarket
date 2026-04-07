package net.scarletphantasy.gensouMarket.bridge.protocol;

/**
 * 消息类型枚举。须与 Velocity 端保持一致。
 */
public enum PacketType {

    // 后端服注册
    SERVER_HELLO((byte) 0x01),
    SERVER_GOODBYE((byte) 0x02),

    // 市场事件
    MARKET_LISTING_CHANGED((byte) 0x10),
    MARKET_LISTING_SOLD((byte) 0x11),

    // 拍卖事件
    AUCTION_CREATED((byte) 0x20),
    AUCTION_BID_UPDATED((byte) 0x21),
    AUCTION_ENDED((byte) 0x22),
    AUCTION_CANCELLED((byte) 0x23),

    // 邮箱
    MAIL_CREATED((byte) 0x30),

    // 远程动作
    PLAYER_NOTIFY((byte) 0x40),
    REMOTE_DEPOSIT((byte) 0x41),
    REMOTE_GIVE_ITEM((byte) 0x42),

    // GUI 刷新
    REFRESH_MARKET_VIEW((byte) 0x50),
    REFRESH_AUCTION_VIEW((byte) 0x51),

    // 请求应答
    REQUEST_ACK((byte) 0x60),
    REQUEST_FAIL((byte) 0x61);

    private final byte id;

    PacketType(byte id) {
        this.id = id;
    }

    public byte getId() {
        return id;
    }

    public static PacketType fromId(byte id) {
        for (PacketType type : values()) {
            if (type.id == id) return type;
        }
        return null;
    }
}
