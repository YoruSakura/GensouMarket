package net.scarletphantasy.gensouMarket.market;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.ClusterEventPublisher;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import net.scarletphantasy.gensouMarket.util.ItemSerializer;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class MarketManager {

    private final GensouMarket plugin;
    private final StorageProvider storage;
    private final ConfigManager config;
    private final VaultHook vault;

    public MarketManager(GensouMarket plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        this.config = plugin.getConfigManager();
        this.vault = plugin.getVaultHook();
    }

    public void sellItem(Player seller, double price) {
        ItemStack item = seller.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            MessageUtil.send(seller, "&c请手持要上架的物品！");
            return;
        }

        if (price <= 0) {
            MessageUtil.send(seller, "&c价格必须大于0！");
            return;
        }

        double tax = price * config.getListingTax();
        if (!vault.has(seller, tax)) {
            MessageUtil.send(seller, "&c你没有足够的金币支付上架税 (" + MessageUtil.formatMoney(tax) + ")！");
            return;
        }

        // 先锁定物品与税金，避免玩家切服导致异步回调直接中断
        UUID sellerUuid = seller.getUniqueId();
        String sellerName = seller.getName();
        ItemStack cloned = item.clone();
        String itemData = ItemSerializer.serialize(item);
        if (!vault.withdraw(seller, tax)) {
            MessageUtil.send(seller, "&c扣除上架税失败，请稍后重试！");
            return;
        }
        seller.getInventory().setItemInMainHand(null);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketListing> existing = storage.getPlayerListings(sellerUuid);
            if (existing.size() >= config.getMaxListings()) {
                rollbackSellReservation(sellerUuid, cloned, itemData, tax,
                        "&c你的上架数量已达上限 (" + config.getMaxListings() + ")！物品和税金已退回。",
                        "市场上架未完成：上架数量已达上限，物品和税金已退回");
                return;
            }

            MarketListing listing = new MarketListing();
            listing.setSellerUuid(sellerUuid);
            listing.setSellerName(sellerName);
            listing.setItemStack(cloned);
            listing.setItemData(itemData);
            listing.setPrice(price);
            listing.setListTime(System.currentTimeMillis());
            listing.setExpireTime(System.currentTimeMillis() + config.getExpireHours() * 3600000L);

            int id = storage.saveListing(listing);
            if (id <= 0) {
                rollbackSellReservation(sellerUuid, cloned, itemData, tax,
                        "&c上架失败，物品和税金已退回。",
                        "市场上架失败，物品和税金已退回");
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player currentSeller = Bukkit.getPlayer(sellerUuid);
                if (currentSeller != null && currentSeller.isOnline()) {
                    MessageUtil.send(currentSeller, "&a成功上架物品！ID: &e" + id + "&a，价格: &e" +
                            MessageUtil.formatMoney(price) + "&a，上架税: &e" + MessageUtil.formatMoney(tax));
                }
                // 跨服广播上架
                publishIfCluster(p -> p.publishListingChanged(id, "ACTIVE", sellerUuid));
            });
        });
    }

    public void cancelListing(Player player, int listingId) {
        UUID playerUuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MarketListing listing = storage.getListing(listingId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (listing == null) {
                    MessageUtil.send(player, "&c未找到该上架物品！");
                    return;
                }
                if (!listing.getSellerUuid().equals(playerUuid)
                        && !player.hasPermission("gensoumarket.admin")) {
                    MessageUtil.send(player, "&c你没有权限取消此上架！");
                    return;
                }
                if (listing.getStatus() != MarketListing.Status.ACTIVE) {
                    MessageUtil.send(player, "&c该物品已不在出售中！");
                    return;
                }

                // 异步原子取消
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean cancelled = storage.markListingExpiredIfActive(listingId);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!cancelled) {
                            if (player.isOnline()) {
                                MessageUtil.send(player, "&c该物品已不在出售中！");
                            }
                            return;
                        }
                        // 原子取消后更新为 CANCELLED 状态（markListingExpiredIfActive 设置的是 EXPIRED）
                        listing.setStatus(MarketListing.Status.CANCELLED);
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.updateListing(listing));

                        // 玩家离线时物品进邮箱
                        if (player.isOnline()) {
                            MessageUtil.giveItem(player, listing.getItemStack());
                            MessageUtil.send(player, "&a已成功下架物品 #" + listingId);
                        } else {
                            MailEntry mail = new MailEntry();
                            mail.setPlayerUuid(playerUuid);
                            mail.setItemData(listing.getItemData());
                            mail.setMessage("你下架的物品 #" + listingId + "（下架时已离线）");
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                storage.saveMail(mail);
                                publishIfCluster(p -> p.publishMailCreated(playerUuid,
                                        false, true, mail.getMessage()));
                            });
                        }

                        // 跨服广播下架
                        publishIfCluster(p -> p.publishListingChanged(listingId, "CANCELLED", listing.getSellerUuid()));
                    });
                });
            });
        });
    }

    public void buyListing(Player buyer, int listingId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MarketListing listing = storage.getListing(listingId);
            if (listing == null || listing.getStatus() != MarketListing.Status.ACTIVE) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (buyer.isOnline()) MessageUtil.send(buyer, "&c该物品已不在出售中！");
                });
                return;
            }

            // 原子抢占：ACTIVE -> SOLD
            boolean claimed = storage.markListingSoldIfActive(listingId, buyer.getUniqueId(), buyer.getName());
            if (!claimed) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (buyer.isOnline()) MessageUtil.send(buyer, "&c该物品已被其他玩家购买！");
                });
                return;
            }

            // 抢占成功，回主线程执行经济操作
            Bukkit.getScheduler().runTask(plugin, () -> {
                // 买家离线时改为邮箱兜底，不直接返回
                boolean buyerOnline = buyer.isOnline();

                if (listing.getSellerUuid().equals(buyer.getUniqueId()) && !config.isDebug()) {
                    // 不应购买自己的商品，回滚 DB 状态
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        listing.setStatus(MarketListing.Status.ACTIVE);
                        listing.setBuyerUuid(null);
                        listing.setBuyerName(null);
                        storage.updateListing(listing);
                    });
                    MessageUtil.send(buyer, "&c你不能购买自己上架的物品！");
                    return;
                }

                double totalCost = listing.getPrice();
                double tax = totalCost * config.getTransactionTax();

                // 买家离线时：余额检查和扣款都跳过，直接回滚
                if (!buyerOnline) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        listing.setStatus(MarketListing.Status.ACTIVE);
                        listing.setBuyerUuid(null);
                        listing.setBuyerName(null);
                        storage.updateListing(listing);
                    });
                    return;
                }

                if (!vault.has(buyer, totalCost)) {
                    // 余额不足，回滚 DB 状态
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        listing.setStatus(MarketListing.Status.ACTIVE);
                        listing.setBuyerUuid(null);
                        listing.setBuyerName(null);
                        storage.updateListing(listing);
                    });
                    MessageUtil.send(buyer, "&c你没有足够的金币！需要: " + MessageUtil.formatMoney(totalCost));
                    return;
                }

                if (!vault.withdraw(buyer, totalCost)) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        listing.setStatus(MarketListing.Status.ACTIVE);
                        listing.setBuyerUuid(null);
                        listing.setBuyerName(null);
                        storage.updateListing(listing);
                    });
                    MessageUtil.send(buyer, "&c扣款失败，请稍后重试购买！");
                    return;
                }

                double sellerReceive = totalCost - tax;

                // 给买家物品（离线时进邮箱）
                if (buyerOnline) {
                    MessageUtil.giveItem(buyer, listing.getItemStack());
                    MessageUtil.send(buyer, "&a成功购买物品！花费: &e" + MessageUtil.formatMoney(totalCost) +
                            " &a(含税: &e" + MessageUtil.formatMoney(tax) + "&a)");
                } else {
                    // 买家已离线，物品进邮箱
                    MailEntry buyerMail = new MailEntry();
                    buyerMail.setPlayerUuid(buyer.getUniqueId());
                    buyerMail.setItemData(listing.getItemData());
                    buyerMail.setMessage("你购买的物品（购买时已离线）");
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        storage.saveMail(buyerMail);
                        publishIfCluster(p -> p.publishMailCreated(buyer.getUniqueId(),
                                false, true, buyerMail.getMessage()));
                    });
                }

                // 给卖家钱
                Player seller = Bukkit.getPlayer(listing.getSellerUuid());
                MailEntry mail = null;
                if (seller != null && seller.isOnline()) {
                    if (vault.deposit(seller, sellerReceive)) {
                        MessageUtil.send(seller, "&a你上架的物品已被 &e" + buyer.getName() +
                                " &a购买！收入: &e" + MessageUtil.formatMoney(sellerReceive));
                    } else {
                        mail = createMoneyMail(listing.getSellerUuid(), sellerReceive,
                                "你上架的物品已被 " + buyer.getName() + " 购买");
                        MessageUtil.send(seller, "&e经济入账失败，收入已暂存到邮箱，使用 &a/gmarket collect &e领取");
                    }
                } else if (plugin.isClusterEnabled()) {
                    MailEntry sellerMail = createMoneyMail(listing.getSellerUuid(), sellerReceive,
                            "你上架的物品已被 " + buyer.getName() + " 购买");
                    plugin.getClusterEventPublisher().requestRemoteDeposit(
                            listing.getSellerUuid(), sellerReceive,
                            "你上架的物品已被 " + buyer.getName() + " 购买！收入: " + MessageUtil.formatMoney(sellerReceive),
                            ignored -> saveMailAsync(sellerMail, true, false, null));
                } else {
                    mail = createMoneyMail(listing.getSellerUuid(), sellerReceive,
                            "你上架的物品已被 " + buyer.getName() + " 购买");
                }

                // 异步写邮箱（listing 状态已在原子操作中更新，无需再 updateListing）
                final MailEntry finalMail = mail;
                if (finalMail != null) {
                    saveMailAsync(finalMail, true, false, null);
                }

                // 跨服广播售出
                publishIfCluster(p -> p.publishListingSold(listingId,
                        listing.getSellerUuid(), listing.getSellerName(),
                        buyer.getUniqueId(), buyer.getName(),
                        totalCost, sellerReceive, tax));
            });
        });
    }

    public List<MarketListing> getActiveListings() {
        return storage.getActiveListings();
    }

    public List<MarketListing> getPlayerListings(UUID playerUuid) {
        return storage.getPlayerListings(playerUuid);
    }

    public List<MarketListing> searchListings(String keyword, List<MarketListing> all) {
        if (keyword == null || keyword.isEmpty()) return all;
        String lower = keyword.toLowerCase();
        List<MarketListing> result = new ArrayList<>();
        for (MarketListing l : all) {
            if (l.getItemStack() != null &&
                    l.getItemStack().getType().name().toLowerCase().contains(lower)) {
                result.add(l);
            }
        }
        return result;
    }

    /**
     * 异步检查过期上架物品。应从异步线程调用。
     * 会在主线程发送通知，在异步线程执行DB写入。
     */
    public void checkExpiredListingsAsync() {
        List<MarketListing> active = storage.getActiveListings();
        List<MarketListing> expired = new ArrayList<>();
        List<MailEntry> mails = new ArrayList<>();

        for (MarketListing listing : active) {
            if (listing.isExpired()) {
                // 原子抢占：ACTIVE -> EXPIRED
                if (storage.markListingExpiredIfActive(listing.getId())) {
                    expired.add(listing);

                    MailEntry mail = new MailEntry();
                    mail.setPlayerUuid(listing.getSellerUuid());
                    mail.setItemData(listing.getItemData());
                    mail.setItemStack(listing.getItemStack());
                    mail.setMessage("你上架的物品已过期");
                    mails.add(mail);
                }
            }
        }

        if (expired.isEmpty()) return;

        // 写邮箱（仍在异步线程）
        for (MailEntry mail : mails) {
            storage.saveMail(mail);
            // 跨服通知邮箱
            publishIfCluster(p -> p.publishMailCreated(mail.getPlayerUuid(), false, true, mail.getMessage()));
        }

        // 跨服广播过期
        for (MarketListing listing : expired) {
            publishIfCluster(p -> p.publishListingChanged(listing.getId(), "EXPIRED", listing.getSellerUuid()));
        }

        // 主线程通知在线玩家
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (MarketListing listing : expired) {
                Player seller = Bukkit.getPlayer(listing.getSellerUuid());
                if (seller != null && seller.isOnline()) {
                    MessageUtil.send(seller, "&e你上架的物品 #" + listing.getId() + " 已过期，请使用 /gmarket collect 领取！");
                }
            }
        });
    }

    private void publishIfCluster(java.util.function.Consumer<ClusterEventPublisher> action) {
        if (plugin.isClusterEnabled()) {
            action.accept(plugin.getClusterEventPublisher());
        }
    }

    private void rollbackSellReservation(UUID sellerUuid, ItemStack itemStack, String itemData, double tax,
                                         String localMessage, String mailMessage) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player seller = Bukkit.getPlayer(sellerUuid);
            if (seller != null && seller.isOnline()) {
                MessageUtil.giveItem(seller, itemStack);
                if (tax <= 0 || vault.deposit(seller, tax)) {
                    MessageUtil.send(seller, localMessage);
                } else {
                    MailEntry mail = createMoneyMail(sellerUuid, tax, mailMessage);
                    saveMailAsync(mail, true, false,
                            "&e市场上架税退款已发送到邮箱，使用 &a/gmarket collect &e领取");
                    MessageUtil.send(seller, "&e物品已退回，但税金退款失败，已转入邮箱");
                }
                return;
            }

            MailEntry mail = new MailEntry();
            mail.setPlayerUuid(sellerUuid);
            mail.setItemStack(itemStack);
            mail.setItemData(itemData);
            mail.setMoney(tax);
            mail.setMessage(mailMessage);
            saveMailAsync(mail, true, true,
                    "&e市场上架未完成，物品和税金已发送到邮箱，使用 &a/gmarket collect &e领取");
        });
    }

    private MailEntry createMoneyMail(UUID playerUuid, double money, String message) {
        MailEntry mail = new MailEntry();
        mail.setPlayerUuid(playerUuid);
        mail.setMoney(money);
        mail.setMessage(message);
        return mail;
    }

    private void saveMailAsync(MailEntry mail, boolean hasMoney, boolean hasItem, String notifyMessage) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            storage.saveMail(mail);
            publishIfCluster(p -> p.publishMailCreated(mail.getPlayerUuid(), hasMoney, hasItem, mail.getMessage()));
        });
    }
}
