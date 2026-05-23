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

    // v1.1.1 跨服消息去重，防止重复消费（如重复收到 Velocity 转发）
    private final Map<String, Long> processedEvents = new java.util.LinkedHashMap<>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 1000 || System.currentTimeMillis() - eldest.getValue() > 60000;
        }
    };

    public RemoteActionHandler(GensouMarket plugin, ViewSessionRegistry viewRegistry) {
        this.plugin = plugin;
        this.viewRegistry = viewRegistry;
    }

    public void handle(IncomingPacket packet) {
        // 被动同步入口：PLAYER_NOTIFY/REMOTE_DEPOSIT/REMOTE_GIVE_ITEM/MAIL_CREATED
        // 必须独立于本地模块开关，以保证跨服结果总能落地。
        // GUI 刷新类则按对应模块开关门控（模块关闭时本地不应打开相关 GUI）。
        switch (packet.type()) {
            case PLAYER_NOTIFY -> handlePlayerNotify(packet.payload());
            case REMOTE_DEPOSIT -> handleRemoteDeposit(packet);
            case REMOTE_GIVE_ITEM -> handleRemoteGiveItem(packet);
            case MAIL_CREATED -> handleMailCreated(packet.payload());
            case REFRESH_MARKET_VIEW -> { if (plugin.getConfigManager().isMarketEnabled()) handleRefreshMarketView(); }
            case REFRESH_AUCTION_VIEW -> { if (plugin.getConfigManager().isAuctionEnabled()) handleRefreshAuctionView(packet.payload()); }
            case MARKET_LISTING_SOLD -> handleMarketListingSold(packet.payload());
            case MARKET_LISTING_CHANGED -> handleMarketListingChanged(packet.payload());
            case AUCTION_BID_UPDATED -> { if (plugin.getConfigManager().isAuctionEnabled()) handleAuctionBidUpdated(packet.payload()); }
            case AUCTION_ENDED -> { if (plugin.getConfigManager().isAuctionEnabled()) handleAuctionEnded(packet.payload()); }
            case AUCTION_CANCELLED -> { if (plugin.getConfigManager().isAuctionEnabled()) handleAuctionCancelled(packet.payload()); }
            case AUCTION_CREATED -> { if (plugin.getConfigManager().isAuctionEnabled()) handleRefreshAuctionList(); }
            case PRESSURE_SYNC -> handlePressureSync(packet.payload());
            default -> plugin.getLogger().fine("忽略跨服消息类型: " + packet.type());
        }
    }

    // ========== v1.1.1 压力同步 ==========

    private void handlePressureSync(Map<String, String> payload) {
        String eventId = payload.get("eventId");
        if (eventId != null) {
            synchronized (processedEvents) {
                if (processedEvents.containsKey(eventId)) {
                    plugin.getLogger().fine("[PressureSync] 忽略重复的压力同步事件: " + eventId);
                    return;
                }
                processedEvents.put(eventId, System.currentTimeMillis());
            }
        }

        String itemId = payload.get("itemId");
        long bucketStart = parseLong(payload.get("bucketStart"), -1);
        int amountDelta = parseInt(payload.get("amountDelta"), 0);
        if (itemId == null || bucketStart < 0 || amountDelta <= 0) return;

        // 在主线程合并压力到本地缓存
        Bukkit.getScheduler().runTask(plugin, () -> {
            var recycleManager = plugin.getRecycleManager();
            if (recycleManager == null) return;
            var pressureWindow = recycleManager.getPressureWindow();
            if (pressureWindow == null) return;
            pressureWindow.mergeRemotePressure(itemId, bucketStart, amountDelta);
            plugin.getLogger().fine("[PressureSync] 合并远程压力: item=" + itemId
                    + " bucket=" + bucketStart + " delta=" + amountDelta);
        });
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

        if (plugin.getMailNotificationService() != null) {
            plugin.getMailNotificationService().recordExternalNotice(targetUuid);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(targetUuid);
            if (player != null && player.isOnline()) {
                MessageUtil.send(player, "&e你有新的待领取物品/金币！使用 &a/gmarket collect &e领取");
            }
        });
        if (plugin.getMailNotificationService() != null) {
            plugin.getMailNotificationService().rememberCurrentMailAsync(targetUuid);
        }
    }

    // ========== 市场事件处理 ==========

    private void handleMarketListingSold(Map<String, String> payload) {
        if (plugin.getConfigManager().isMarketEnabled()) {
            handleRefreshMarketView();
        }
        UUID sellerUuid = parseUuid(payload.get("sellerUuid"));
        if (plugin.getConfigManager().isPersonalShopEnabled() && sellerUuid != null) {
            refreshPersonalShopView(sellerUuid);
        }
    }

    private void handleMarketListingChanged(Map<String, String> payload) {
        if (plugin.getConfigManager().isMarketEnabled()) {
            handleRefreshMarketView();
        }
        UUID sellerUuid = parseUuid(payload.get("sellerUuid"));
        if (plugin.getConfigManager().isPersonalShopEnabled() && sellerUuid != null) {
            refreshPersonalShopView(sellerUuid);
        }
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

    /**
     * 刷新查看指定卖家个人商店的玩家。刷到第一页。
     * 内部捕获异常，避免影响其他刷新链路。
     */
    private void refreshPersonalShopView(UUID sellerUuid) {
        List<UUID> viewers = viewRegistry.getPersonalShopViewers(sellerUuid);
        if (viewers.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<MarketListing> all = plugin.getStorage().getPlayerListings(sellerUuid);
                List<MarketListing> active = new java.util.ArrayList<>();
                for (MarketListing l : all) {
                    if (l.getStatus() == MarketListing.Status.ACTIVE) active.add(l);
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (UUID uuid : viewers) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null || !player.isOnline()) continue;
                        try {
                            if (active.isEmpty()) {
                                player.closeInventory();
                                MessageUtil.send(player, "&e该卖家当前已无在售物品。");
                            } else {
                                String sellerName = active.get(0).getSellerName();
                                MarketGui.openPersonalShop(plugin, player, sellerUuid,
                                        sellerName != null ? sellerName : "?", active, 0);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING,
                                    "刷新玩家 " + uuid + " 的个人商店页失败", e);
                        }
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "加载卖家 " + sellerUuid + " 的上架用于刷新个人商店失败", e);
            }
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

    private static long parseLong(String str, long def) {
        if (str == null) return def;
        try {
            return Long.parseLong(str);
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
