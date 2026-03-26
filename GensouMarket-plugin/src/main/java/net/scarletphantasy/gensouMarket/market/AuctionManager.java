package net.scarletphantasy.gensouMarket.market;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import net.scarletphantasy.gensouMarket.util.ItemSerializer;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AuctionManager {

    private final GensouMarket plugin;
    private final StorageProvider storage;
    private final ConfigManager config;
    private final VaultHook vault;
    private final Map<Integer, Integer> scheduledTasks = new ConcurrentHashMap<>();

    public AuctionManager(GensouMarket plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        this.config = plugin.getConfigManager();
        this.vault = plugin.getVaultHook();
    }

    public void createAuction(Player seller, double startingPrice, int durationMinutes) {
        ItemStack item = seller.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            MessageUtil.send(seller, "&c请手持要拍卖的物品！");
            return;
        }

        if (startingPrice <= 0) {
            MessageUtil.send(seller, "&c起拍价必须大于0！");
            return;
        }

        if (durationMinutes < config.getMinAuctionDuration()) {
            MessageUtil.send(seller, "&c拍卖时长不能少于 " + config.getMinAuctionDuration() + " 分钟！");
            return;
        }
        if (durationMinutes > config.getMaxAuctionDuration()) {
            MessageUtil.send(seller, "&c拍卖时长不能超过 " + config.getMaxAuctionDuration() + " 分钟！");
            return;
        }

        double tax = startingPrice * config.getListingTax();
        double listingFee = startingPrice * config.getAuctionListingFeeRate();
        double totalUpfront = tax + listingFee;
        if (vault.has(seller, totalUpfront)) {
            MessageUtil.send(seller, "&c你没有足够的金币支付上架费用 (" + MessageUtil.formatMoney(totalUpfront) + ")！");
            return;
        }

        vault.withdraw(seller, totalUpfront);

        Auction auction = new Auction();
        auction.setSellerUuid(seller.getUniqueId());
        auction.setSellerName(seller.getName());
        auction.setItemStack(item.clone());
        auction.setItemData(ItemSerializer.serialize(item));
        auction.setStartingPrice(startingPrice);
        auction.setCurrentPrice(startingPrice);
        auction.setStartTime(System.currentTimeMillis());
        auction.setEndTime(System.currentTimeMillis() + durationMinutes * 60000L);

        seller.getInventory().setItemInMainHand(null);

        // 异步写DB
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int id = storage.saveAuction(auction);
            auction.setId(id);

            // 注册定时结算任务
            long delayTicks = durationMinutes * 60 * 20L;
            int taskId = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                Auction dbAuction = storage.getAuction(id);
                if (dbAuction != null && dbAuction.getStatus() == Auction.Status.ACTIVE) {
                    endAuctionAsync(dbAuction);
                }
                scheduledTasks.remove(id);
            }, delayTicks).getTaskId();
            scheduledTasks.put(id, taskId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (seller.isOnline()) {
                    String feeMsg = listingFee > 0
                            ? "，手续费: &e" + MessageUtil.formatMoney(listingFee) + "&a(不退还)"
                            : "";
                    MessageUtil.send(seller, "&a成功发起拍卖！ID: &e" + id +
                            "&a，起拍价: &e" + MessageUtil.formatMoney(startingPrice) +
                            "&a，时长: &e" + durationMinutes + "分钟" + feeMsg);
                }
            });
        });
    }

    public void placeBid(Player bidder, int auctionId, double amount) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Auction auction = storage.getAuction(auctionId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!bidder.isOnline()) return;
                if (auction == null) {
                    MessageUtil.send(bidder, "&c未找到该拍卖！");
                    return;
                }
                if (auction.getStatus() != Auction.Status.ACTIVE) {
                    MessageUtil.send(bidder, "&c该拍卖已结束！");
                    return;
                }
                if (auction.hasEnded()) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> endAuctionAsync(auction));
                    MessageUtil.send(bidder, "&c该拍卖已结束！");
                    return;
                }
                if (auction.getSellerUuid().equals(bidder.getUniqueId()) && !config.isDebug()) {
                    MessageUtil.send(bidder, "&c你不能竞拍自己的拍卖！");
                    return;
                }

                double minBid = auction.getCurrentPrice();
                if (!auction.hasBidder()) {
                    minBid = auction.getStartingPrice();
                }

                if (amount <= minBid && auction.hasBidder()) {
                    MessageUtil.send(bidder, "&c出价必须高于当前价 &e" + MessageUtil.formatMoney(minBid) + "&c！");
                    return;
                }
                if (amount < minBid) {
                    MessageUtil.send(bidder, "&c出价不能低于起拍价 &e" + MessageUtil.formatMoney(minBid) + "&c！");
                    return;
                }

                if (vault.has(bidder, amount)) {
                    MessageUtil.send(bidder, "&c你没有足够的金币！");
                    return;
                }

                // 退还上一个竞拍者的钱
                MailEntry refundMail = null;
                if (auction.hasBidder()) {
                    Player prevBidder = Bukkit.getPlayer(auction.getHighestBidderUuid());
                    if (prevBidder != null && prevBidder.isOnline()) {
                        vault.deposit(prevBidder, auction.getCurrentPrice());
                        MessageUtil.send(prevBidder, "&e你在拍卖 #" + auctionId +
                                " 中的出价已被超越！已退还 &a" + MessageUtil.formatMoney(auction.getCurrentPrice()));
                    } else {
                        refundMail = new MailEntry();
                        refundMail.setPlayerUuid(auction.getHighestBidderUuid());
                        refundMail.setMoney(auction.getCurrentPrice());
                        refundMail.setMessage("你在拍卖 #" + auctionId + " 中的出价已被超越");
                    }
                }

                vault.withdraw(bidder, amount);

                auction.setCurrentPrice(amount);
                auction.setHighestBidderUuid(bidder.getUniqueId());
                auction.setHighestBidderName(bidder.getName());

                MessageUtil.send(bidder, "&a成功出价 &e" + MessageUtil.formatMoney(amount) + " &a在拍卖 #" + auctionId);

                // 通知卖家
                Player seller = Bukkit.getPlayer(auction.getSellerUuid());
                if (seller != null && seller.isOnline()) {
                    MessageUtil.send(seller, "&a你的拍卖 #" + auctionId + " 收到新出价: &e" +
                            MessageUtil.formatMoney(amount) + " &a来自 &e" + bidder.getName());
                }

                // 异步写DB
                final MailEntry finalRefundMail = refundMail;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    storage.updateAuction(auction);
                    if (finalRefundMail != null) storage.saveMail(finalRefundMail);
                });
            });
        });
    }

    /**
     * 结束拍卖。应从异步线程调用。DB写入在异步线程，Bukkit API在主线程。
     */
    public void endAuctionAsync(Auction auction) {
        if (auction.getStatus() != Auction.Status.ACTIVE) return;

        auction.setStatus(Auction.Status.ENDED);
        storage.updateAuction(auction);

        // 清理定时任务
        scheduledTasks.remove(auction.getId());

        if (auction.hasBidder()) {
            double tax = auction.getCurrentPrice() * config.getTransactionTax();
            double sellerReceive = auction.getCurrentPrice() - tax;

            MailEntry sellerMail = new MailEntry();
            sellerMail.setPlayerUuid(auction.getSellerUuid());
            sellerMail.setMoney(sellerReceive);
            sellerMail.setMessage("拍卖 #" + auction.getId() + " 已结束，" +
                    auction.getHighestBidderName() + " 以 " +
                    MessageUtil.formatMoney(auction.getCurrentPrice()) + " 中标");

            MailEntry winnerMail = new MailEntry();
            winnerMail.setPlayerUuid(auction.getHighestBidderUuid());
            winnerMail.setItemData(auction.getItemData());
            winnerMail.setItemStack(auction.getItemStack());
            winnerMail.setMessage("你赢得了拍卖 #" + auction.getId());

            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean sellerHandled = false;
                Player seller = Bukkit.getPlayer(auction.getSellerUuid());
                if (seller != null && seller.isOnline()) {
                    vault.deposit(seller, sellerReceive);
                    MessageUtil.send(seller, "&a你的拍卖 #" + auction.getId() +
                            " 已结束！&e" + auction.getHighestBidderName() +
                            " &a以 &e" + MessageUtil.formatMoney(auction.getCurrentPrice()) +
                            " &a中标，你获得: &e" + MessageUtil.formatMoney(sellerReceive));
                    sellerHandled = true;
                }

                boolean winnerHandled = false;
                Player winner = Bukkit.getPlayer(auction.getHighestBidderUuid());
                if (winner != null && winner.isOnline()) {
                    if (MessageUtil.hasInventorySpace(winner)) {
                        MessageUtil.giveItem(winner, auction.getItemStack());
                        MessageUtil.send(winner, "&a你赢得了拍卖 #" + auction.getId() + "！");
                        winnerHandled = true;
                    }
                }

                final boolean needSellerMail = !sellerHandled;
                final boolean needWinnerMail = !winnerHandled;
                if (needSellerMail || needWinnerMail) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        if (needSellerMail) storage.saveMail(sellerMail);
                        if (needWinnerMail) storage.saveMail(winnerMail);
                    });
                }
                if (needWinnerMail && winner != null && winner.isOnline()) {
                    MessageUtil.send(winner, "&a你赢得了拍卖 #" + auction.getId() + "！背包已满，物品已发送至邮箱");
                }
            });
        } else {
            MailEntry mail = new MailEntry();
            mail.setPlayerUuid(auction.getSellerUuid());
            mail.setItemData(auction.getItemData());
            mail.setItemStack(auction.getItemStack());
            mail.setMessage("拍卖 #" + auction.getId() + " 无人竞拍");

            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean handled = false;
                Player seller = Bukkit.getPlayer(auction.getSellerUuid());
                if (seller != null && seller.isOnline()) {
                    if (MessageUtil.hasInventorySpace(seller)) {
                        MessageUtil.giveItem(seller, auction.getItemStack());
                        MessageUtil.send(seller, "&e你的拍卖 #" + auction.getId() + " 无人竞拍，物品已退回！");
                        handled = true;
                    } else {
                        MessageUtil.send(seller, "&e你的拍卖 #" + auction.getId() + " 无人竞拍，背包已满，物品已发送至邮箱！");
                    }
                }
                if (!handled) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveMail(mail));
                }
            });
        }
    }

    public List<Auction> getActiveAuctions() {
        return storage.getActiveAuctions();
    }

    public void cancelAuction(Player player, int auctionId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Auction auction = storage.getAuction(auctionId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (auction == null) {
                    MessageUtil.send(player, "&c未找到该拍卖！");
                    return;
                }
                if (auction.getStatus() != Auction.Status.ACTIVE) {
                    MessageUtil.send(player, "&c该拍卖已结束！");
                    return;
                }
                if (!auction.getSellerUuid().equals(player.getUniqueId())
                        && !player.hasPermission("gensoumarket.admin")) {
                    MessageUtil.send(player, "&c你没有权限取消此拍卖！");
                    return;
                }

                auction.setStatus(Auction.Status.CANCELLED);

                // 退还竞拍者金额
                MailEntry bidderMail = null;
                if (auction.hasBidder()) {
                    Player bidder = Bukkit.getPlayer(auction.getHighestBidderUuid());
                    if (bidder != null && bidder.isOnline()) {
                        vault.deposit(bidder, auction.getCurrentPrice());
                        MessageUtil.send(bidder, "&e拍卖 #" + auctionId + " 已被卖家取消，已退还 &a" +
                                MessageUtil.formatMoney(auction.getCurrentPrice()));
                    } else {
                        bidderMail = new MailEntry();
                        bidderMail.setPlayerUuid(auction.getHighestBidderUuid());
                        bidderMail.setMoney(auction.getCurrentPrice());
                        bidderMail.setMessage("拍卖 #" + auctionId + " 已被卖家取消，竞拍金额已退还");
                    }
                }

                // 退还物品给卖家
                MessageUtil.giveItem(player, auction.getItemStack());

                MessageUtil.send(player, "&a已取消拍卖 #" + auctionId + "，手续费不退还");

                // 取消定时任务
                Integer taskId = scheduledTasks.remove(auctionId);
                if (taskId != null) {
                    Bukkit.getScheduler().cancelTask(taskId);
                }

                // 异步写DB
                final MailEntry finalBidderMail = bidderMail;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    storage.updateAuction(auction);
                    if (finalBidderMail != null) storage.saveMail(finalBidderMail);
                });
            });
        });
    }

    /**
     * 异步检查并结束到期拍卖。应从异步线程调用。
     */
    public void checkEndedAuctionsAsync() {
        List<Auction> active = storage.getActiveAuctions();
        for (Auction auction : active) {
            if (auction.hasEnded()) {
                endAuctionAsync(auction);
            }
        }
    }

    /**
     * 启动时取消所有遗留拍卖（同步，仅启动时调用）。
     */
    public void cancelAllActiveAuctions() {
        List<Auction> active = storage.getActiveAuctions();
        for (Auction auction : active) {
            auction.setStatus(Auction.Status.CANCELLED);
            storage.updateAuction(auction);

            if (auction.hasBidder()) {
                MailEntry bidderMail = new MailEntry();
                bidderMail.setPlayerUuid(auction.getHighestBidderUuid());
                bidderMail.setMoney(auction.getCurrentPrice());
                bidderMail.setMessage("拍卖 #" + auction.getId() + " 因服务器重启已取消，竞拍金额已退还");
                storage.saveMail(bidderMail);
            }

            MailEntry sellerMail = new MailEntry();
            sellerMail.setPlayerUuid(auction.getSellerUuid());
            sellerMail.setItemData(auction.getItemData());
            sellerMail.setItemStack(auction.getItemStack());
            sellerMail.setMessage("拍卖 #" + auction.getId() + " 因服务器重启已取消，物品已退还");
            storage.saveMail(sellerMail);
        }

        if (!active.isEmpty()) {
            plugin.getLogger().info("已取消 " + active.size() + " 个遗留拍卖并退还物品/金额");
        }
    }

    /**
     * 启动时恢复活跃拍卖的定时任务。
     */
    public void restoreActiveAuctions() {
        List<Auction> active = storage.getActiveAuctions();
        long now = System.currentTimeMillis();

        for (Auction auction : active) {
            long remaining = auction.getEndTime() - now;
            if (remaining <= 0) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> endAuctionAsync(auction));
            } else {
                long delayTicks = remaining / 50;
                int taskId = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    Auction dbAuction = storage.getAuction(auction.getId());
                    if (dbAuction != null && dbAuction.getStatus() == Auction.Status.ACTIVE) {
                        endAuctionAsync(dbAuction);
                    }
                    scheduledTasks.remove(auction.getId());
                }, delayTicks).getTaskId();
                scheduledTasks.put(auction.getId(), taskId);
            }
        }

        if (!active.isEmpty()) {
            plugin.getLogger().info("已恢复 " + active.size() + " 个活跃拍卖的定时任务");
        }
    }
}
