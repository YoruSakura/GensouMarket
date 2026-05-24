package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.ViewSessionRegistry;
import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.shop.PriceEngine;
import net.scarletphantasy.gensouMarket.shop.PriceResult;
import net.scarletphantasy.gensouMarket.trade.TradeManager;
import net.scarletphantasy.gensouMarket.trade.TradeManager.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.scarletphantasy.gensouMarket.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GuiListener implements Listener {

    private final GensouMarket plugin;
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private record PendingAction(String type, int targetId, long timestamp, double snapshotPrice) {}
    private final Map<UUID, PendingAction> pendingConfirmations = new ConcurrentHashMap<>();

    public GuiListener(GensouMarket plugin) {
        this.plugin = plugin;
    }

    /**
     * 检查二次确认（无价格快照版本，向后兼容市场/拍卖）。
     */
    private boolean checkConfirmation(Player player, String actionType, int targetId, String confirmMessage) {
        return checkConfirmationWithPrice(player, actionType, targetId, confirmMessage, -1);
    }

    /**
     * 检查二次确认（带价格快照）。
     * @param snapshotPrice 首次点击时的价格快照；-1 表示不进行锁价校验
     */
    private boolean checkConfirmationWithPrice(Player player, String actionType, int targetId,
                                                String confirmMessage, double snapshotPrice) {
        UUID uuid = player.getUniqueId();
        PendingAction pending = pendingConfirmations.get(uuid);
        long now = System.currentTimeMillis();

        if (pending != null && pending.type().equals(actionType) && pending.targetId() == targetId
                && (now - pending.timestamp()) < CONFIRM_TIMEOUT_MS) {
            pendingConfirmations.remove(uuid);
            return true;
        }

        pendingConfirmations.put(uuid, new PendingAction(actionType, targetId, now, snapshotPrice));
        MessageUtil.send(player, confirmMessage);
        return false;
    }


    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;

        if (!(event.getWhoClicked() instanceof Player player)) return;

        // 交易 GUI 需要特殊处理（不能一律取消事件）
        if (holder.getType() == GuiHolder.GuiType.TRADE) {
            handleTrade(player, holder, event);
            return;
        }

        event.setCancelled(true);

        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        switch (holder.getType()) {
            case MAIN_MENU -> handleMainMenu(player, slot);
            case MARKET_BROWSE -> handleMarketBrowse(player, holder, slot, event);
            case PERSONAL_SHOP -> handlePersonalShop(player, holder, slot, event);
            case SHOP -> handleShop(player, holder, slot, event);
            case RECYCLE -> handleRecycle(player, holder, slot, event);
            case AUCTION_LIST -> handleAuctionList(player, holder, slot);
            case AUCTION_DETAIL -> handleAuctionDetail(player, holder, slot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;

        if (holder.getType() == GuiHolder.GuiType.TRADE) {
            // 检查拖拽是否涉及只读 slot
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < event.getInventory().getSize() && TradeGui.isReadOnlySlot(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
            }
            // 拖拽涉及自己物品区或仅在玩家背包内，允许并延迟同步
            boolean touchesTopInv = false;
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < event.getInventory().getSize()) {
                    touchesTopInv = true;
                    break;
                }
            }
            if (touchesTopInv) {
                Player player = (Player) event.getWhoClicked();
                scheduleTradeSync(player, event.getInventory());
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        // 跨服 GUI 视图注销
        unregisterView(player);

        if (holder.getType() == GuiHolder.GuiType.RECYCLE) {
            RecycleGui.cancelRefreshTask(player.getUniqueId());
            return;
        }

        if (holder.getType() != GuiHolder.GuiType.TRADE) return;

        TradeManager tradeManager = plugin.getTradeManager();
        if (tradeManager == null) return;

        TradeSession session = tradeManager.getSession(player.getUniqueId());
        if (session == null || session.isEnded()) return;

        // 交易未完成就关闭了 GUI，取消交易
        Player other = Bukkit.getPlayer(session.getOtherUuid(player.getUniqueId()));
        if (other != null) {
            MessageUtil.send(other, "&c对方取消了交易！");
        }
        MessageUtil.send(player, "&e交易已取消");
        tradeManager.endSession(session, false);
    }

    private void handleTrade(Player player, GuiHolder holder, InventoryClickEvent event) {
        TradeManager tradeManager = plugin.getTradeManager();
        if (tradeManager == null) return;

        TradeSession session = tradeManager.getSession(player.getUniqueId());
        if (session == null || session.isEnded()) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        Inventory topInv = event.getInventory();
        int topSize = topInv.getSize();

        // 点击在玩家自己的背包区域
        if (rawSlot >= topSize) {
            if (event.isShiftClick()) {
                // Shift-Click 从背包移入: 手动放入自己物品区的第一个空位
                event.setCancelled(true);
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || clicked.getType().isAir()) return;

                int emptySlot = TradeGui.findFirstEmptyMySlot(topInv);
                if (emptySlot == -1) {
                    MessageUtil.send(player, "&c交易栏已满！");
                    return;
                }

                topInv.setItem(emptySlot, clicked.clone());
                event.setCurrentItem(null);
                scheduleTradeSync(player, topInv);
            }
            // 非 Shift-Click 在背包区域的操作（如正常拿取物品）允许
            return;
        }

        // 点击在交易 GUI 的顶部 inventory 中

        // 确认按钮
        if (rawSlot == TradeGui.SLOT_CONFIRM) {
            event.setCancelled(true);
            if (session.isConfirmed(player.getUniqueId())) {
                // 已确认，点击取消确认
                session.setConfirmed(player.getUniqueId(), false);
                MessageUtil.send(player, "&e已取消确认");
            } else {
                session.setConfirmed(player.getUniqueId(), true);
                MessageUtil.send(player, "&a已确认交易！等待对方确认...");

                Player other = Bukkit.getPlayer(session.getOtherUuid(player.getUniqueId()));
                if (other != null) {
                    MessageUtil.send(other, "&e对方已确认交易！");
                }
            }

            // 刷新双方状态栏
            refreshBothStatusBars(session);

            // 双方都确认则完成交易
            if (session.areBothConfirmed()) {
                MessageUtil.send(player, "&a交易完成！");
                Player other = Bukkit.getPlayer(session.getOtherUuid(player.getUniqueId()));
                if (other != null) {
                    MessageUtil.send(other, "&a交易完成！");
                }
                tradeManager.endSession(session, true);
            }
            return;
        }

        // 取消按钮
        if (rawSlot == TradeGui.SLOT_CANCEL) {
            event.setCancelled(true);
            Player other = Bukkit.getPlayer(session.getOtherUuid(player.getUniqueId()));
            if (other != null) {
                MessageUtil.send(other, "&c对方取消了交易！");
            }
            MessageUtil.send(player, "&e交易已取消");
            tradeManager.endSession(session, false);
            return;
        }

        // 只读区域（对方物品区、分隔栏、状态灯、填充）
        if (TradeGui.isReadOnlySlot(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        // 自己的物品区 — 允许自由操作
        if (TradeGui.isMyItemSlot(rawSlot)) {
            // 允许操作，延迟同步
            scheduleTradeSync(player, topInv);
            return;
        }

        // 其他未预期的 slot，取消
        event.setCancelled(true);
    }

    /**
     * 延迟 1 tick 后从 GUI 同步物品状态到 session，并刷新对方 GUI
     */
    private void scheduleTradeSync(Player player, Inventory topInv) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            TradeManager tradeManager = plugin.getTradeManager();
            if (tradeManager == null) return;

            TradeSession session = tradeManager.getSession(uuid);
            if (session == null || session.isEnded()) return;

            // 从 GUI 读取自己物品区的实际内容
            ItemStack[] myItems = TradeGui.readMyItems(topInv);
            session.updateItems(uuid, myItems);

            // 重置双方确认状态
            session.resetConfirmations();

            // 刷新对方 GUI 的对方物品区
            Inventory opponentInv = session.getOpponentInventory(uuid);
            if (opponentInv != null) {
                TradeGui.refreshOpponentItems(opponentInv, session, session.getOtherUuid(uuid));
            }

            // 刷新双方状态栏
            refreshBothStatusBars(session);
        });
    }

    private void refreshBothStatusBars(TradeSession session) {
        Inventory invA = session.getPlayerAInventory();
        Inventory invB = session.getPlayerBInventory();
        if (invA != null) {
            TradeGui.refreshStatusBar(invA, session, session.getPlayerAUuid());
        }
        if (invB != null) {
            TradeGui.refreshStatusBar(invB, session, session.getPlayerBUuid());
        }
    }

    private void handleMainMenu(Player player, int slot) {
        var cm = plugin.getConfigManager();
        switch (slot) {
            case 4 -> {
                if (!cm.isPersonalShopEnabled()) return;
                UUID sellerUuid = player.getUniqueId();
                String sellerName = player.getName();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<MarketListing> all = plugin.getStorage().getPlayerListings(sellerUuid);
                    List<MarketListing> active = new ArrayList<>();
                    for (MarketListing l : all) {
                        if (l.getStatus() == MarketListing.Status.ACTIVE) active.add(l);
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (active.isEmpty()) {
                            MessageUtil.send(player, "&e你当前没有在售物品！");
                            return;
                        }
                        MarketGui.openPersonalShop(plugin, player, sellerUuid, sellerName, active, 0);
                    });
                });
            }
            case 10 -> {
                if (!cm.isMarketEnabled()) return;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<MarketListing> listings = plugin.getMarketManager().getActiveListings();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            MarketGui.openMarketBrowse(plugin, player, listings, 0);
                        }
                    });
                });
            }
            case 12 -> {
                if (!cm.isAuctionEnabled()) return;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<Auction> auctions = plugin.getAuctionManager().getActiveAuctions();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            AuctionGui.openAuctions(plugin, player, auctions, 0);
                        }
                    });
                });
            }
            case 14 -> {
                if (!cm.isShopEnabled()) return;
                ShopGui.openShop(plugin, player, 0);
            }
            case 16 -> {
                if (!cm.isRecycleEnabled()) return;
                RecycleGui.openRecycle(plugin, player, 0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMarketBrowse(Player player, GuiHolder holder, int slot, InventoryClickEvent event) {
        int page = holder.getIntData("page", 0);
        List<MarketListing> listings = (List<MarketListing>) holder.getData("listings");

        if (slot == 45 && page > 0) {
            MarketGui.openMarketBrowse(plugin, player, listings, page - 1);
            return;
        }
        if (slot == 49) {
            MarketGui.openMainMenu(plugin, player);
            return;
        }
        if (slot == 53) {
            int totalPages = (int) Math.ceil((double) listings.size() / 45);
            if (page < totalPages - 1) {
                MarketGui.openMarketBrowse(plugin, player, listings, page + 1);
            }
            return;
        }

        if (slot >= 0 && slot < 45) {
            int index = page * 45 + slot;
            if (index < listings.size()) {
                MarketListing listing = listings.get(index);
                boolean isOwn = listing.getSellerUuid().equals(player.getUniqueId());
                if (event.isRightClick() && isOwn) {
                    player.closeInventory();
                    plugin.getMarketManager().cancelListing(player, listing.getId());
                } else if (event.isLeftClick()) {
                    if (!isOwn || plugin.getConfigManager().isDebug()) {
                        if (!checkConfirmation(player, "market_buy", listing.getId(),
                                "&e再次点击确认购买，价格: &6" + MessageUtil.formatMoney(listing.getPrice()))) {
                            return;
                        }
                    }
                    player.closeInventory();
                    plugin.getMarketManager().buyListing(player, listing.getId());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePersonalShop(Player player, GuiHolder holder, int slot, InventoryClickEvent event) {
        int page = holder.getIntData("page", 0);
        List<MarketListing> listings = (List<MarketListing>) holder.getData("listings");
        UUID sellerUuid = (UUID) holder.getData("sellerUuid");
        String sellerName = (String) holder.getData("sellerName");

        if (slot == 45 && page > 0) {
            MarketGui.openPersonalShop(plugin, player, sellerUuid, sellerName, listings, page - 1);
            return;
        }
        if (slot == 49) {
            MarketGui.openMainMenu(plugin, player);
            return;
        }
        if (slot == 53) {
            int totalPages = (int) Math.ceil((double) listings.size() / 45);
            if (page < totalPages - 1) {
                MarketGui.openPersonalShop(plugin, player, sellerUuid, sellerName, listings, page + 1);
            }
            return;
        }

        if (slot >= 0 && slot < 45) {
            int index = page * 45 + slot;
            if (index < listings.size()) {
                MarketListing listing = listings.get(index);
                boolean isOwn = listing.getSellerUuid().equals(player.getUniqueId());
                if (event.isRightClick() && isOwn) {
                    player.closeInventory();
                    plugin.getMarketManager().cancelListing(player, listing.getId());
                } else if (event.isLeftClick()) {
                    if (!isOwn || plugin.getConfigManager().isDebug()) {
                        if (!checkConfirmation(player, "personal_buy", listing.getId(),
                                "&e再次点击确认购买，价格: &6" + MessageUtil.formatMoney(listing.getPrice()))) {
                            return;
                        }
                    }
                    player.closeInventory();
                    plugin.getMarketManager().buyListing(player, listing.getId());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleAuctionList(Player player, GuiHolder holder, int slot) {
        int page = holder.getIntData("page", 0);
        List<Auction> auctions = (List<Auction>) holder.getData("auctions");

        if (slot == 45 && page > 0) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<Auction> refreshed = plugin.getAuctionManager().getActiveAuctions();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) AuctionGui.openAuctions(plugin, player, refreshed, page - 1);
                });
            });
            return;
        }
        if (slot == 49) {
            MarketGui.openMainMenu(plugin, player);
            return;
        }
        if (slot == 53) {
            int totalPages = (int) Math.ceil((double) auctions.size() / 45);
            if (page < totalPages - 1) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<Auction> refreshed = plugin.getAuctionManager().getActiveAuctions();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) AuctionGui.openAuctions(plugin, player, refreshed, page + 1);
                    });
                });
            }
            return;
        }

        if (slot >= 0 && slot < 45) {
            int index = page * 45 + slot;
            if (index < auctions.size()) {
                Auction auction = auctions.get(index);
                int auctionId = auction.getId();
                // 异步获取最新数据
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Auction fresh = plugin.getStorage().getAuction(auctionId);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (fresh != null && fresh.getStatus() == Auction.Status.ACTIVE) {
                            AuctionGui.openAuctionDetail(plugin, player, fresh);
                        } else {
                            MessageUtil.send(player, "&c该拍卖已结束！");
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                List<Auction> refreshed = plugin.getAuctionManager().getActiveAuctions();
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (player.isOnline()) AuctionGui.openAuctions(plugin, player, refreshed, page);
                                });
                            });
                        }
                    });
                });
            }
        }
    }

    private void handleAuctionDetail(Player player, GuiHolder holder, int slot) {
        int auctionId = holder.getIntData("auctionId", -1);
        if (auctionId < 0) return;

        // 返回拍卖列表
        if (slot == 49) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<Auction> auctions = plugin.getAuctionManager().getActiveAuctions();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) AuctionGui.openAuctions(plugin, player, auctions, 0);
                });
            });
            return;
        }

        // 加价按钮区域: 45-48 (固定金额), 50-53 (百分比)
        if (slot < 45 || slot > 53) return;

        // 从 holder 缓存中获取当前价格用于计算竞拍金额
        // 实际竞拍时 placeBid 会异步读取最新数据验证
        Auction cachedAuction = (Auction) holder.getData("auction");
        double currentPrice = cachedAuction != null ? cachedAuction.getCurrentPrice() : 0;
        if (currentPrice <= 0) return;

        double bidAmount = switch (slot) {
            case 45 -> currentPrice + 10;
            case 46 -> currentPrice + 100;
            case 47 -> currentPrice + 1000;
            case 48 -> currentPrice + 5000;
            case 50 -> Math.round(currentPrice * 1.01 * 100.0) / 100.0;
            case 51 -> Math.round(currentPrice * 1.05 * 100.0) / 100.0;
            case 52 -> Math.round(currentPrice * 1.10 * 100.0) / 100.0;
            case 53 -> Math.round(currentPrice * 1.20 * 100.0) / 100.0;
            default -> -1;
        };

        if (bidAmount <= 0) return;

        // placeBid 内部已经是异步的，竞拍完成后异步刷新详情页
        plugin.getAuctionManager().placeBid(player, auctionId, bidAmount);

        // 延迟刷新详情页（等竞拍处理完）
        Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Auction updated = plugin.getStorage().getAuction(auctionId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (updated != null && updated.getStatus() == Auction.Status.ACTIVE) {
                    AuctionGui.openAuctionDetail(plugin, player, updated);
                } else {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        List<Auction> auctions = plugin.getAuctionManager().getActiveAuctions();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) AuctionGui.openAuctions(plugin, player, auctions, 0);
                        });
                    });
                }
            });
        }), 5L);
    }

    @SuppressWarnings("unchecked")
    private void handleShop(Player player, GuiHolder holder, int slot, InventoryClickEvent event) {
        int page = holder.getIntData("page", 0);
        List<ShopItem> items = (List<ShopItem>) holder.getData("items");

        if (slot == 45 && page > 0) {
            ShopGui.openShop(plugin, player, page - 1);
            return;
        }
        if (slot == 49) {
            MarketGui.openMainMenu(plugin, player);
            return;
        }
        if (slot == 53) {
            int totalPages = (int) Math.ceil((double) items.size() / 45);
            if (page < totalPages - 1) {
                ShopGui.openShop(plugin, player, page + 1);
            }
            return;
        }

        if (slot >= 0 && slot < 45) {
            int index = page * 45 + slot;
            if (index < items.size()) {
                ShopItem shopItem = items.get(index);
                int amount = event.isShiftClick() ? 64 : 1;
                double currentUnitPrice = plugin.getShopManager().computeCurrentPrice(shopItem);
                double totalCost = Math.round(currentUnitPrice * amount * 100.0) / 100.0;

                String actionKey = "shop_buy_" + amount;
                UUID uuid = player.getUniqueId();
                PendingAction pending = pendingConfirmations.get(uuid);
                long now = System.currentTimeMillis();

                if (pending != null && pending.type().equals(actionKey) && pending.targetId() == index
                        && (now - pending.timestamp()) < CONFIRM_TIMEOUT_MS) {
                    // 确认阶段：锁价校验
                    pendingConfirmations.remove(uuid);
                    double snapshotPrice = pending.snapshotPrice();
                    double tolerance = plugin.getConfigManager().getPriceChangeTolerance();
                    if (snapshotPrice > 0 && Math.abs(totalCost - snapshotPrice) / snapshotPrice > tolerance) {
                        MessageUtil.send(player, String.format("&c价格已变化超过 %d%%，请重新确认！ (快照: &e%s&c → 当前: &e%s&c)",
                                (int) (tolerance * 100), MessageUtil.formatMoney(snapshotPrice), MessageUtil.formatMoney(totalCost)));
                        ShopGui.openShop(plugin, player, page);
                        return;
                    }
                    plugin.getShopManager().buyFromShop(player, shopItem.getId(), amount, () -> {
                        if (player.isOnline() && isViewingShopPage(player, page)) {
                            ShopGui.openShop(plugin, player, page);
                        }
                    });
                } else {
                    // v1.1.2 缺货预检查：在创建确认会话前检查库存
                    int available = plugin.getShopManager().computeAvailableStock(shopItem);
                    if (available <= 0) {
                        MessageUtil.send(player, "&c该商品已缺货！");
                        return;
                    }
                    if (available < amount) {
                        MessageUtil.send(player, "&c库存不足，当前剩余 &e" + available + "&c 个！");
                        return;
                    }
                    // 首次点击：记录价格快照
                    pendingConfirmations.put(uuid, new PendingAction(actionKey, index, now, totalCost));
                    MessageUtil.send(player, "&e再次点击确认购买 &6" + amount + "x&e，花费: &6" + MessageUtil.formatMoney(totalCost));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRecycle(Player player, GuiHolder holder, int slot, InventoryClickEvent event) {
        int page = holder.getIntData("page", 0);
        List<RecycleItem> items = (List<RecycleItem>) holder.getData("items");

        if (slot == 45 && page > 0) {
            RecycleGui.openRecycle(plugin, player, page - 1);
            return;
        }
        if (slot == 49) {
            MarketGui.openMainMenu(plugin, player);
            return;
        }
        if (slot == 53) {
            int totalPages = (int) Math.ceil((double) items.size() / 45);
            if (page < totalPages - 1) {
                RecycleGui.openRecycle(plugin, player, page + 1);
            }
            return;
        }

        if (slot >= 0 && slot < 45) {
            int index = page * 45 + slot;
            if (index < items.size()) {
                RecycleItem recycleItem = items.get(index);
                
                int available = 0;
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType() == recycleItem.getMaterial()) {
                        available += item.getAmount();
                    }
                }
                
                if (available <= 0) {
                    MessageUtil.send(player, "&c你的背包中没有该物品！");
                    return;
                }

                int amount = event.isLeftClick() ? 1 : Math.min(64, available);

                // 获取当前回收单价/总价（用于锁价快照，使用完整压力上下文）
                PriceEngine engine = plugin.getRecycleManager().getPriceEngine();
                PriceResult currentResult = engine.calculateBatchRecyclePrice(recycleItem,
                        RecycleGui.buildRecyclePriceContext(plugin, recycleItem), amount);
                double currentPriceToCompare = currentResult.totalPrice();

                String actionKey = "recycle_" + amount;
                UUID uuid = player.getUniqueId();
                PendingAction pending = pendingConfirmations.get(uuid);
                long now = System.currentTimeMillis();

                if (pending != null && pending.type().equals(actionKey) && pending.targetId() == index
                        && (now - pending.timestamp()) < CONFIRM_TIMEOUT_MS) {
                    // 确认阶段：锁价校验
                    pendingConfirmations.remove(uuid);
                    double snapshotPrice = pending.snapshotPrice();
                    double tolerance = plugin.getConfigManager().getPriceChangeTolerance();
                    if (snapshotPrice > 0 && Math.abs(currentPriceToCompare - snapshotPrice) / snapshotPrice > tolerance) {
                        MessageUtil.send(player, String.format("&c回收价格已变化超过 %d%%，请重新确认！ (快照: &e%s&c → 当前: &e%s&c)",
                                (int) (tolerance * 100), MessageUtil.formatMoney(snapshotPrice), MessageUtil.formatMoney(currentPriceToCompare)));
                        RecycleGui.openRecycle(plugin, player, page);
                        return;
                    }
                    plugin.getRecycleManager().recycleItem(player, recycleItem, amount);
                    RecycleGui.openRecycle(plugin, player, page);
                } else {
                    // 首次点击：记录价格快照
                    pendingConfirmations.put(uuid, new PendingAction(actionKey, index, now, currentPriceToCompare));
                    MessageUtil.send(player, "&e再次点击确认回收 &6" + amount + "x " + recycleItem.getMaterial().name());
                }
            }
        }
    }

    private boolean isViewingShopPage(Player player, int expectedPage) {
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        if (!(topInventory.getHolder() instanceof GuiHolder holder)) {
            return false;
        }
        return holder.getType() == GuiHolder.GuiType.SHOP
                && holder.getIntData("page", -1) == expectedPage;
    }

    private void unregisterView(Player player) {
        ViewSessionRegistry registry = plugin.getViewSessionRegistry();
        if (registry != null) {
            registry.unregister(player.getUniqueId());
        }
    }
}
