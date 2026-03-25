package net.scarletphantasy.gensouMarket.market;

import net.scarletphantasy.gensouMarket.GensouMarket;
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

        // 异步检查上架数量
        UUID sellerUuid = seller.getUniqueId();
        String sellerName = seller.getName();
        ItemStack cloned = item.clone();
        String itemData = ItemSerializer.serialize(item);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketListing> existing = storage.getPlayerListings(sellerUuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!seller.isOnline()) return;
                if (existing.size() >= config.getMaxListings()) {
                    MessageUtil.send(seller, "&c你的上架数量已达上限 (" + config.getMaxListings() + ")！");
                    return;
                }
                // 再次检查手持物品是否还在
                ItemStack currentHand = seller.getInventory().getItemInMainHand();
                if (!currentHand.isSimilar(cloned) || currentHand.getAmount() < cloned.getAmount()) {
                    MessageUtil.send(seller, "&c物品已变化，请重新操作！");
                    return;
                }
                if (!vault.has(seller, tax)) {
                    MessageUtil.send(seller, "&c你没有足够的金币支付上架税！");
                    return;
                }

                vault.withdraw(seller, tax);
                seller.getInventory().setItemInMainHand(null);

                MarketListing listing = new MarketListing();
                listing.setSellerUuid(sellerUuid);
                listing.setSellerName(sellerName);
                listing.setItemStack(cloned);
                listing.setItemData(itemData);
                listing.setPrice(price);
                listing.setListTime(System.currentTimeMillis());
                listing.setExpireTime(System.currentTimeMillis() + config.getExpireHours() * 3600000L);

                // 异步写DB
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    int id = storage.saveListing(listing);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (seller.isOnline()) {
                            MessageUtil.send(seller, "&a成功上架物品！ID: &e" + id + "&a，价格: &e" +
                                    MessageUtil.formatMoney(price) + "&a，上架税: &e" + MessageUtil.formatMoney(tax));
                        }
                    });
                });
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

                listing.setStatus(MarketListing.Status.CANCELLED);

                ItemStack item = listing.getItemStack();
                if (item != null) {
                    HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                    if (!overflow.isEmpty()) {
                        for (ItemStack drop : overflow.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                        MessageUtil.send(player, "&e背包已满，物品已掉落在你脚下！");
                    }
                }

                MessageUtil.send(player, "&a已成功下架物品 #" + listingId);

                // 异步写DB
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    storage.updateListing(listing);
                });
            });
        });
    }

    public void buyListing(Player buyer, int listingId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MarketListing listing = storage.getListing(listingId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!buyer.isOnline()) return;
                if (listing == null) {
                    MessageUtil.send(buyer, "&c未找到该上架物品！");
                    return;
                }
                if (listing.getStatus() != MarketListing.Status.ACTIVE) {
                    MessageUtil.send(buyer, "&c该物品已不在出售中！");
                    return;
                }
                if (listing.getSellerUuid().equals(buyer.getUniqueId()) && !config.isDebug()) {
                    MessageUtil.send(buyer, "&c你不能购买自己上架的物品！");
                    return;
                }

                double totalCost = listing.getPrice();
                double tax = totalCost * config.getTransactionTax();

                if (!vault.has(buyer, totalCost)) {
                    MessageUtil.send(buyer, "&c你没有足够的金币！需要: " + MessageUtil.formatMoney(totalCost));
                    return;
                }

                vault.withdraw(buyer, totalCost);

                double sellerReceive = totalCost - tax;

                listing.setStatus(MarketListing.Status.SOLD);
                listing.setBuyerUuid(buyer.getUniqueId());
                listing.setBuyerName(buyer.getName());

                // 给买家物品
                ItemStack item = listing.getItemStack();
                if (item != null) {
                    HashMap<Integer, ItemStack> overflow = buyer.getInventory().addItem(item);
                    if (!overflow.isEmpty()) {
                        for (ItemStack drop : overflow.values()) {
                            buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop);
                        }
                        MessageUtil.send(buyer, "&e背包已满，物品已掉落在你脚下！");
                    }
                }

                // 给卖家钱
                Player seller = Bukkit.getPlayer(listing.getSellerUuid());
                MailEntry mail = null;
                if (seller != null && seller.isOnline()) {
                    vault.deposit(seller, sellerReceive);
                    MessageUtil.send(seller, "&a你上架的物品已被 &e" + buyer.getName() +
                            " &a购买！收入: &e" + MessageUtil.formatMoney(sellerReceive));
                } else {
                    mail = new MailEntry();
                    mail.setPlayerUuid(listing.getSellerUuid());
                    mail.setMoney(sellerReceive);
                    mail.setMessage("你上架的物品已被 " + buyer.getName() + " 购买");
                }

                MessageUtil.send(buyer, "&a成功购买物品！花费: &e" + MessageUtil.formatMoney(totalCost) +
                        " &a(含税: &e" + MessageUtil.formatMoney(tax) + "&a)");

                // 异步写DB
                final MailEntry finalMail = mail;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    storage.updateListing(listing);
                    if (finalMail != null) storage.saveMail(finalMail);
                });
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
                listing.setStatus(MarketListing.Status.EXPIRED);
                expired.add(listing);

                MailEntry mail = new MailEntry();
                mail.setPlayerUuid(listing.getSellerUuid());
                mail.setItemData(listing.getItemData());
                mail.setItemStack(listing.getItemStack());
                mail.setMessage("你上架的物品已过期");
                mails.add(mail);
            }
        }

        if (expired.isEmpty()) return;

        // 写DB（仍在异步线程）
        for (MarketListing listing : expired) {
            storage.updateListing(listing);
        }
        for (MailEntry mail : mails) {
            storage.saveMail(mail);
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
}
