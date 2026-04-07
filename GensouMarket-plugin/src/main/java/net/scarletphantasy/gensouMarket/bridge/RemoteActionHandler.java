package net.scarletphantasy.gensouMarket.bridge;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.protocol.IncomingPacket;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.gui.AuctionGui;
import net.scarletphantasy.gensouMarket.gui.MarketGui;
import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 处理来自 Velocity 转发的远程动作和事件广播。
 * 所有 Bukkit API 操作均在主线程执行。
 */
public class RemoteActionHandler {

    private final GensouMarket plugin;
    private final ViewSessionRegistry viewRegistry;

    public RemoteActionHandler(GensouMarket plugin, ViewSessionRegistry viewRegistry) {
        this.plugin = plugin;
        this.viewRegistry = viewRegistry;
    }

    public void handle(IncomingPacket packet) {
        switch (packet.type()) {
            case PLAYER_NOTIFY -> handlePlayerNotify(packet.payload());
            case REMOTE_DEPOSIT -> handleRemoteDeposit(packet);
            case REMOTE_GIVE_ITEM -> handleRemoteGiveItem(packet);
            case MAIL_CREATED -> handleMailCreated(packet.payload());
            case REFRESH_MARKET_VIEW -> handleRefreshMarketView();
            case REFRESH_AUCTION_VIEW -> handleRefreshAuctionView(packet.payload());
            case MARKET_LISTING_SOLD -> handleMarketListingSold(packet.payload());
            case MARKET_LISTING_CHANGED -> handleMarketListingChanged(packet.payload());
            case AUCTION_BID_UPDATED -> handleAuctionBidUpdated(packet.payload());
            case AUCTION_ENDED -> handleAuctionEnded(packet.payload());
            case AUCTION_CANCELLED -> handleAuctionCancelled(packet.payload());
            case AUCTION_CREATED -> handleRefreshAuctionList();
            default -> plugin.getLogger().fine("忽略跨服消息类型: " + packet.type());
        }
    }

    // ========== 远程动作 ==========

    private void handlePlayerNotify(Map<String, String> payload) {
        UUID targetUuid = parseUuid(payload.get("targetPlayerUuid"));
        String message = payload.get("message");
        if (targetUuid == null || message == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(targetUuid);
            if (player != null && player.isOnline()) {
                MessageUtil.send(player, message);
            }
        });
    }

    private void handleRemoteDeposit(IncomingPacket packet) {
        UUID targetUuid = parseUuid(packet.payload().get("targetPlayerUuid"));
        double amount = parseDouble(packet.payload().get("amount"), 0);
        if (targetUuid == null || amount <= 0) {
            plugin.getProxyBridge().sendFail(packet.requestId(), packet.sourceServerId(), "invalid remote deposit payload");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(targetUuid);
            if (player == null || !player.isOnline()) {
                plugin.getProxyBridge().sendFail(packet.requestId(), packet.sourceServerId(), "player offline on target server");
                return;
            }

            VaultHook vault = plugin.getVaultHook();
            if (!vault.deposit(player, amount)) {
                plugin.getProxyBridge().sendFail(packet.requestId(), packet.sourceServerId(), "vault deposit failed");
                return;
            }

            String reason = packet.payload().get("reason");
            if (reason != null && !reason.isEmpty()) {
                MessageUtil.send(player, "&a" + reason + " 收入: &e" + MessageUtil.formatMoney(amount));
            }
            plugin.getProxyBridge().sendAck(packet.requestId(), packet.sourceServerId());
        });
    }

    private void handleRemoteGiveItem(IncomingPacket packet) {
        UUID targetUuid = parseUuid(packet.payload().get("targetPlayerUuid"));
        String itemData = packet.payload().get("itemData");
        if (targetUuid == null || itemData == null || itemData.isEmpty()) {
            plugin.getProxyBridge().sendFail(packet.requestId(), packet.sourceServerId(), "invalid remote give item payload");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(targetUuid);
            if (player == null || !player.isOnline()) {
                plugin.getProxyBridge().sendFail(packet.requestId(), packet.sourceServerId(), "player offline on target server");
                return;
            }

            if (!MessageUtil.hasInventorySpace(player)) {
                plugin.getProxyBridge().sendFail(packet.requestId(), packet.sourceServerId(), "inventory full");
                return;
            }

            var itemStack = net.scarletphantasy.gensouMarket.util.ItemSerializer.deserialize(itemData);
            if (itemStack == null) {
                plugin.getProxyBridge().sendFail(packet.requestId(), packet.sourceServerId(), "item deserialize failed");
                return;
            }

            MessageUtil.giveItem(player, itemStack);
            String message = packet.payload().get("message");
            if (message != null && !message.isEmpty()) {
                MessageUtil.send(player, "&a" + message);
            }
            plugin.getProxyBridge().sendAck(packet.requestId(), packet.sourceServerId());
        });
    }

    private void handleMailCreated(Map<String, String> payload) {
        UUID targetUuid = parseUuid(payload.get("targetPlayerUuid"));
        if (targetUuid == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(targetUuid);
            if (player != null && player.isOnline()) {
                MessageUtil.send(player, "&e你有新的待领取物品/金币！使用 &a/gmarket collect &e领取");
            }
        });
    }

    // ========== 市场事件处理 ==========

    private void handleMarketListingSold(Map<String, String> payload) {
        // 刷新市场 GUI
        handleRefreshMarketView();
    }

    private void handleMarketListingChanged(Map<String, String> payload) {
        handleRefreshMarketView();
    }

    // ========== 拍卖事件处理 ==========

    private void handleAuctionBidUpdated(Map<String, String> payload) {
        int auctionId = parseInt(payload.get("auctionId"), -1);
        // 刷新拍卖 GUI
        refreshAuctionViews(auctionId);
    }

    private void handleAuctionEnded(Map<String, String> payload) {
        int auctionId = parseInt(payload.get("auctionId"), -1);
        // 刷新拍卖 GUI
        refreshAuctionViews(auctionId);
    }

    private void handleAuctionCancelled(Map<String, String> payload) {
        int auctionId = parseInt(payload.get("auctionId"), -1);
        refreshAuctionViews(auctionId);
    }

    // ========== GUI 刷新 ==========

    private void handleRefreshMarketView() {
        List<UUID> viewers = viewRegistry.getMarketBrowseViewers();
        if (viewers.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketListing> listings = plugin.getMarketManager().getActiveListings();
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID uuid : viewers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        MarketGui.openMarketBrowse(plugin, player, listings, 0);
                    }
                }
            });
        });
    }

    private void handleRefreshAuctionView(Map<String, String> payload) {
        int auctionId = parseInt(payload.get("auctionId"), -1);
        String mode = payload.getOrDefault("mode", "LIST_AND_DETAIL");
        if ("DETAIL_ONLY".equals(mode) && auctionId > 0) {
            refreshAuctionDetailOnly(auctionId);
        } else {
            refreshAuctionViews(auctionId);
        }
    }

    private void handleRefreshAuctionList() {
        List<UUID> viewers = viewRegistry.getAuctionListViewers();
        if (viewers.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Auction> auctions = plugin.getAuctionManager().getActiveAuctions();
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID uuid : viewers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        AuctionGui.openAuctions(plugin, player, auctions, 0);
                    }
                }
            });
        });
    }

    private void refreshAuctionViews(int auctionId) {
        // 刷新列表
        handleRefreshAuctionList();

        // 刷新详情
        if (auctionId > 0) {
            refreshAuctionDetailOnly(auctionId);
        }
    }

    private void refreshAuctionDetailOnly(int auctionId) {
        List<UUID> detailViewers = viewRegistry.getAuctionDetailViewers(auctionId);
        if (detailViewers.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Auction auction = plugin.getStorage().getAuction(auctionId);
            if (auction == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID uuid : detailViewers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        AuctionGui.openAuctionDetail(plugin, player, auction);
                    }
                }
            });
        });
    }

    // ========== 工具方法 ==========

    private static UUID parseUuid(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double parseDouble(String str, double def) {
        if (str == null) return def;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseInt(String str, int def) {
        if (str == null) return def;
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void saveMoneyMail(UUID playerUuid, double amount, String message, String notifyMessage) {
        if (amount <= 0) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var mail = new net.scarletphantasy.gensouMarket.model.MailEntry();
            mail.setPlayerUuid(playerUuid);
            mail.setMoney(amount);
            mail.setMessage(message);
            plugin.getStorage().saveMail(mail);

            Player player = Bukkit.getPlayer(playerUuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player != null && player.isOnline()) {
                    MessageUtil.send(player, notifyMessage);
                }
            });
        });
    }
}
