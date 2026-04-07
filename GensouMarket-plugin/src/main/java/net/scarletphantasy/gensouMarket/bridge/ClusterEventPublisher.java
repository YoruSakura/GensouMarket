package net.scarletphantasy.gensouMarket.bridge;

import net.scarletphantasy.gensouMarket.bridge.protocol.IncomingPacket;
import net.scarletphantasy.gensouMarket.bridge.protocol.OutgoingPacket;
import net.scarletphantasy.gensouMarket.bridge.protocol.PacketType;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 语义化跨服事件发布器。业务层调用此类发布事件，由 ProxyBridge 编码发送到 Velocity。
 * 当 cluster 未启用时，所有方法为空操作。
 */
public class ClusterEventPublisher {

    private final ProxyBridge bridge;

    public ClusterEventPublisher(ProxyBridge bridge) {
        this.bridge = bridge;
    }

    // ========== 市场事件 ==========

    public void publishListingSold(int listingId, UUID sellerUuid, String sellerName,
                                   UUID buyerUuid, String buyerName,
                                   double price, double sellerReceive, double tax) {
        OutgoingPacket packet = newPacket(PacketType.MARKET_LISTING_SOLD);
        packet.put("listingId", String.valueOf(listingId));
        packet.put("sellerUuid", sellerUuid.toString());
        packet.put("sellerName", sellerName);
        packet.put("buyerUuid", buyerUuid.toString());
        packet.put("buyerName", buyerName);
        packet.put("price", String.valueOf(price));
        packet.put("sellerReceive", String.valueOf(sellerReceive));
        packet.put("tax", String.valueOf(tax));
        bridge.send(packet);
    }

    public void publishListingChanged(int listingId, String newStatus, UUID sellerUuid) {
        OutgoingPacket packet = newPacket(PacketType.MARKET_LISTING_CHANGED);
        packet.put("listingId", String.valueOf(listingId));
        packet.put("newStatus", newStatus);
        packet.put("sellerUuid", sellerUuid.toString());
        bridge.send(packet);
    }

    // ========== 拍卖事件 ==========

    public void publishAuctionCreated(int auctionId, UUID sellerUuid, String sellerName,
                                      double startingPrice, double currentPrice, long endTime) {
        OutgoingPacket packet = newPacket(PacketType.AUCTION_CREATED);
        packet.put("auctionId", String.valueOf(auctionId));
        packet.put("sellerUuid", sellerUuid.toString());
        packet.put("sellerName", sellerName);
        packet.put("startingPrice", String.valueOf(startingPrice));
        packet.put("currentPrice", String.valueOf(currentPrice));
        packet.put("endTime", String.valueOf(endTime));
        bridge.send(packet);
    }

    public void publishAuctionBidUpdated(int auctionId, UUID sellerUuid,
                                         UUID bidderUuid, String bidderName,
                                         UUID previousBidderUuid,
                                         double oldPrice, double newPrice, long endTime) {
        OutgoingPacket packet = newPacket(PacketType.AUCTION_BID_UPDATED);
        packet.put("auctionId", String.valueOf(auctionId));
        packet.put("sellerUuid", sellerUuid.toString());
        packet.put("bidderUuid", bidderUuid.toString());
        packet.put("bidderName", bidderName);
        packet.put("previousBidderUuid", previousBidderUuid != null ? previousBidderUuid.toString() : "");
        packet.put("oldPrice", String.valueOf(oldPrice));
        packet.put("newPrice", String.valueOf(newPrice));
        packet.put("endTime", String.valueOf(endTime));
        bridge.send(packet);
    }

    public void publishAuctionEnded(int auctionId, UUID sellerUuid,
                                    UUID winnerUuid, String winnerName,
                                    double finalPrice, double sellerReceive, boolean hasWinner) {
        OutgoingPacket packet = newPacket(PacketType.AUCTION_ENDED);
        packet.put("auctionId", String.valueOf(auctionId));
        packet.put("sellerUuid", sellerUuid.toString());
        packet.put("winnerUuid", winnerUuid != null ? winnerUuid.toString() : "");
        packet.put("winnerName", winnerName != null ? winnerName : "");
        packet.put("finalPrice", String.valueOf(finalPrice));
        packet.put("sellerReceive", String.valueOf(sellerReceive));
        packet.put("hasWinner", String.valueOf(hasWinner));
        bridge.send(packet);
    }

    public void publishAuctionCancelled(int auctionId, UUID sellerUuid,
                                        UUID highestBidderUuid, double refundAmount) {
        OutgoingPacket packet = newPacket(PacketType.AUCTION_CANCELLED);
        packet.put("auctionId", String.valueOf(auctionId));
        packet.put("sellerUuid", sellerUuid.toString());
        packet.put("highestBidderUuid", highestBidderUuid != null ? highestBidderUuid.toString() : "");
        packet.put("refundAmount", String.valueOf(refundAmount));
        bridge.send(packet);
    }

    // ========== 邮箱通知 ==========

    public void publishMailCreated(UUID playerUuid, boolean hasMoney, boolean hasItem, String message) {
        OutgoingPacket packet = newPacket(PacketType.MAIL_CREATED);
        packet.put("targetPlayerUuid", playerUuid.toString());
        packet.put("hasMoney", String.valueOf(hasMoney));
        packet.put("hasItem", String.valueOf(hasItem));
        packet.put("message", message);
        bridge.send(packet);
    }

    // ========== 远程动作请求 ==========

    public void requestRemoteDeposit(UUID targetPlayerUuid, double amount, String reason) {
        requestRemoteDeposit(targetPlayerUuid, amount, reason, null);
    }

    public void requestRemoteDeposit(UUID targetPlayerUuid, double amount, String reason,
                                     Consumer<IncomingPacket> onFail) {
        requestRemoteDeposit(targetPlayerUuid, amount, reason, null, onFail);
    }

    public void requestRemoteDeposit(UUID targetPlayerUuid, double amount, String reason,
                                     Consumer<IncomingPacket> onAck,
                                     Consumer<IncomingPacket> onFail) {
        OutgoingPacket packet = newPacket(PacketType.REMOTE_DEPOSIT);
        packet.put("targetPlayerUuid", targetPlayerUuid.toString());
        packet.put("amount", String.valueOf(amount));
        packet.put("reason", reason);
        bridge.sendWithResponseHandlers(packet, onAck, onFail);
    }

    public void requestPlayerNotify(UUID targetPlayerUuid, String message) {
        requestPlayerNotify(targetPlayerUuid, message, null);
    }

    public void requestPlayerNotify(UUID targetPlayerUuid, String message,
                                    Consumer<IncomingPacket> onFail) {
        OutgoingPacket packet = newPacket(PacketType.PLAYER_NOTIFY);
        packet.put("targetPlayerUuid", targetPlayerUuid.toString());
        packet.put("message", message);
        bridge.sendWithFailHandler(packet, onFail);
    }

    public void requestRemoteGiveItem(UUID targetPlayerUuid, String itemData, String message,
                                      Consumer<IncomingPacket> onFail) {
        OutgoingPacket packet = newPacket(PacketType.REMOTE_GIVE_ITEM);
        packet.put("targetPlayerUuid", targetPlayerUuid.toString());
        packet.put("itemData", itemData);
        packet.put("message", message);
        bridge.sendWithFailHandler(packet, onFail);
    }

    // ========== GUI 刷新 ==========

    public void broadcastRefreshMarketView() {
        OutgoingPacket packet = newPacket(PacketType.REFRESH_MARKET_VIEW);
        bridge.send(packet);
    }

    public void broadcastRefreshAuctionView(int auctionId, String mode) {
        OutgoingPacket packet = newPacket(PacketType.REFRESH_AUCTION_VIEW);
        packet.put("auctionId", String.valueOf(auctionId));
        packet.put("mode", mode);
        bridge.send(packet);
    }

    private OutgoingPacket newPacket(PacketType type) {
        return new OutgoingPacket(type, bridge.newRequestId(), bridge.getServerId());
    }
}
