package net.scarletphantasy.gensouMarket.market;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.ClusterEventPublisher;
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
import java.util.UUID;
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
        if (!vault.has(seller, totalUpfront)) {
            MessageUtil.send(seller, "&c你没有足够的金币支付上架费用 (" + MessageUtil.formatMoney(totalUpfront) + ")！");
            return;
        }

        if (!vault.withdraw(seller, totalUpfront)) {
            MessageUtil.send(seller, "&c扣除上架费用失败，请稍后重试！");
            return;
        }

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
            if (id <= 0) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!seller.isOnline()) {
                        MailEntry mail = new MailEntry();
                        mail.setPlayerUuid(auction.getSellerUuid());
                        mail.setItemStack(auction.getItemStack());
                        mail.setItemData(auction.getItemData());
                        mail.setMoney(totalUpfront);
                        mail.setMessage("拍卖创建失败，物品和上架费用已退回");
                        saveMailAsync(mail, true, true,
                                "&e拍卖创建失败，物品和上架费用已发送到邮箱，使用 &a/gmarket collect &e领取");
                        return;
                    }

                    MessageUtil.giveItem(seller, auction.getItemStack());
                    if (!vault.deposit(seller, totalUpfront)) {
                        MailEntry mail = new MailEntry();
                        mail.setPlayerUuid(auction.getSellerUuid());
                        mail.setMoney(totalUpfront);
                        mail.setMessage("拍卖创建失败，上架费用已退回");
                        saveMailAsync(mail, true, false,
                                "&e拍卖创建失败，上架费用已发送到邮箱，使用 &a/gmarket collect &e领取");
                        MessageUtil.send(seller, "&e拍卖创建失败，物品已退回，但上架费用已转入邮箱");
                    } else {
                        MessageUtil.send(seller, "&c拍卖创建失败，物品和上架费用已退回！");
                    }
                });
                return;
            }
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

                // 跨服广播拍卖创建
                publishIfCluster(p -> p.publishAuctionCreated(id, seller.getUniqueId(), seller.getName(),
                        startingPrice, startingPrice, auction.getEndTime()));
            });
        });
    }

    public void placeBid(Player bidder, int auctionId, double amount) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Auction auction = storage.getAuction(auctionId);
            if (auction == null || auction.getStatus() != Auction.Status.ACTIVE) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (bidder.isOnline()) MessageUtil.send(bidder, "&c该拍卖已结束！");
                });
                return;
            }
            if (auction.hasEnded()) {
                endAuctionAsync(auction);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (bidder.isOnline()) MessageUtil.send(bidder, "&c该拍卖已结束！");
                });
                return;
            }

            // 记录旧竞拍信息用于退款
            final double oldPrice = auction.getCurrentPrice();
            final UUID oldBidderUuid = auction.getHighestBidderUuid();
            final String oldBidderName = auction.getHighestBidderName();
            final boolean hadBidder = auction.hasBidder();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!bidder.isOnline()) return;
                if (auction.getSellerUuid().equals(bidder.getUniqueId()) && !config.isDebug()) {
                    MessageUtil.send(bidder, "&c你不能竞拍自己的拍卖！");
                    return;
                }

                double minBid = oldPrice;
                if (!hadBidder) {
                    minBid = auction.getStartingPrice();
                }

                if (amount <= minBid && hadBidder) {
                    MessageUtil.send(bidder, "&c出价必须高于当前价 &e" + MessageUtil.formatMoney(minBid) + "&c！");
                    return;
                }
                if (amount < minBid) {
                    MessageUtil.send(bidder, "&c出价不能低于起拍价 &e" + MessageUtil.formatMoney(minBid) + "&c！");
                    return;
                }

                if (!vault.has(bidder, amount)) {
                    MessageUtil.send(bidder, "&c你没有足够的金币！");
                    return;
                }

                // 异步原子出价
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean success = storage.updateAuctionBidIfMatch(
                            auctionId, oldPrice, amount, bidder.getUniqueId(), bidder.getName());

                    if (!success) {
                        // 出价失败 - 价格已变化，提示重试
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (bidder.isOnline()) {
                                MessageUtil.send(bidder, "&c出价失败，拍卖价格已变化，请重新查看后再出价！");
                            }
                        });
                        return;
                    }

                    // 出价成功，回主线程处理经济操作
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!bidder.isOnline()) return;

                        if (!vault.withdraw(bidder, amount)) {
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                auction.setCurrentPrice(oldPrice);
                                auction.setHighestBidderUuid(oldBidderUuid);
                                auction.setHighestBidderName(oldBidderName);
                                storage.updateAuction(auction);
                            });
                            MessageUtil.send(bidder, "&c扣款失败，已取消本次出价！");
                            return;
                        }

                        // 退还上一个竞拍者的钱
                        MailEntry refundMail = null;
                        if (hadBidder) {
                            Player prevBidder = Bukkit.getPlayer(oldBidderUuid);
                            if (prevBidder != null && prevBidder.isOnline()) {
                                if (vault.deposit(prevBidder, oldPrice)) {
                                    MessageUtil.send(prevBidder, "&e你在拍卖 #" + auctionId +
                                            " 中的出价已被超越！已退还 &a" + MessageUtil.formatMoney(oldPrice));
                                } else {
                                    refundMail = new MailEntry();
                                    refundMail.setPlayerUuid(oldBidderUuid);
                                    refundMail.setMoney(oldPrice);
                                    refundMail.setMessage("你在拍卖 #" + auctionId + " 中的出价已被超越");
                                    MessageUtil.send(prevBidder, "&e拍卖退款入账失败，已转入邮箱，使用 &a/gmarket collect &e领取");
                                }
                            } else if (plugin.isClusterEnabled()) {
                                // 跨服尝试即时退款
                                MailEntry fallbackMail = new MailEntry();
                                fallbackMail.setPlayerUuid(oldBidderUuid);
                                fallbackMail.setMoney(oldPrice);
                                fallbackMail.setMessage("你在拍卖 #" + auctionId + " 中的出价已被超越");
                                plugin.getClusterEventPublisher().requestRemoteDeposit(
                                        oldBidderUuid, oldPrice,
                                        "你在拍卖 #" + auctionId + " 中的出价已被超越！已退还 " + MessageUtil.formatMoney(oldPrice),
                                        ignored -> {
                                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                                storage.saveMail(fallbackMail);
                                                publishIfCluster(p -> p.publishMailCreated(oldBidderUuid,
                                                        true, false, fallbackMail.getMessage()));
                                            });
                                        });
                            } else {
                                refundMail = new MailEntry();
                                refundMail.setPlayerUuid(oldBidderUuid);
                                refundMail.setMoney(oldPrice);
                                refundMail.setMessage("你在拍卖 #" + auctionId + " 中的出价已被超越");
                            }
                        }

                        MessageUtil.send(bidder, "&a成功出价 &e" + MessageUtil.formatMoney(amount) + " &a在拍卖 #" + auctionId);

                        // 通知卖家
                        Player seller = Bukkit.getPlayer(auction.getSellerUuid());
                        String sellerMessage = "&a你的拍卖 #" + auctionId + " 收到新出价: &e" +
                                MessageUtil.formatMoney(amount) + " &a来自 &e" + bidder.getName();
                        if (seller != null && seller.isOnline()) {
                            MessageUtil.send(seller, sellerMessage);
                        } else {
                            publishIfCluster(p -> p.requestPlayerNotify(auction.getSellerUuid(), sellerMessage));
                        }

                        // 异步写邮箱
                        final MailEntry finalRefundMail = refundMail;
                        if (finalRefundMail != null) {
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                storage.saveMail(finalRefundMail);
                                // 跨服通知退款邮箱
                                publishIfCluster(p -> p.publishMailCreated(oldBidderUuid,
                                        true, false, finalRefundMail.getMessage()));
                            });
                        }

                        // 跨服广播出价更新（通知其他服刷新 GUI、退款、提示卖家）
                        publishIfCluster(p -> p.publishAuctionBidUpdated(auctionId, auction.getSellerUuid(),
                                bidder.getUniqueId(), bidder.getName(),
                                hadBidder ? oldBidderUuid : null,
                                hadBidder ? oldPrice : 0, amount, auction.getEndTime()));
                    });
                });
            });
        });
    }

    /**
     * 结束拍卖。应从异步线程调用。使用原子抢占避免多服重复结算。
     */
    public void endAuctionAsync(Auction auction) {
        // 原子抢占：ACTIVE -> ENDED，只有成功的服才继续结算
        boolean claimed = storage.markAuctionEndedIfActive(auction.getId());
        if (!claimed) return;

        // 抢占成功，重新读取最新状态用于结算
        Auction latest = storage.getAuction(auction.getId());
        if (latest == null) return;

        // 清理定时任务
        scheduledTasks.remove(auction.getId());

        if (latest.getHighestBidderUuid() != null) {
            double tax = latest.getCurrentPrice() * config.getTransactionTax();
            double sellerReceive = latest.getCurrentPrice() - tax;

            MailEntry sellerMail = new MailEntry();
            sellerMail.setPlayerUuid(latest.getSellerUuid());
            sellerMail.setMoney(sellerReceive);
            sellerMail.setMessage("拍卖 #" + latest.getId() + " 已结束，" +
                    latest.getHighestBidderName() + " 以 " +
                    MessageUtil.formatMoney(latest.getCurrentPrice()) + " 中标");

            MailEntry winnerMail = new MailEntry();
            winnerMail.setPlayerUuid(latest.getHighestBidderUuid());
            winnerMail.setItemData(latest.getItemData());
            winnerMail.setItemStack(latest.getItemStack());
            winnerMail.setMessage("你赢得了拍卖 #" + latest.getId());

            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean sellerHandled = false;
                Player seller = Bukkit.getPlayer(latest.getSellerUuid());
                String sellerReason = "你的拍卖 #" + latest.getId() + " 已结束！" +
                        latest.getHighestBidderName() + " 以 " +
                        MessageUtil.formatMoney(latest.getCurrentPrice()) + " 中标，你获得: " +
                        MessageUtil.formatMoney(sellerReceive);
                if (seller != null && seller.isOnline()) {
                    if (vault.deposit(seller, sellerReceive)) {
                        MessageUtil.send(seller, "&a" + sellerReason);
                        sellerHandled = true;
                    } else {
                        MessageUtil.send(seller, "&e拍卖收入入账失败，已转入邮箱，使用 &a/gmarket collect &e领取");
                    }
                } else if (plugin.isClusterEnabled()) {
                    plugin.getClusterEventPublisher().requestRemoteDeposit(
                            latest.getSellerUuid(), sellerReceive, sellerReason,
                            ignored -> saveMailAsync(sellerMail, true, false, null));
                    sellerHandled = true;
                }

                boolean winnerHandled = false;
                Player winner = Bukkit.getPlayer(latest.getHighestBidderUuid());
                if (winner != null && winner.isOnline()) {
                    if (MessageUtil.hasInventorySpace(winner)) {
                        MessageUtil.giveItem(winner, latest.getItemStack());
                        MessageUtil.send(winner, "&a你赢得了拍卖 #" + latest.getId() + "！");
                        winnerHandled = true;
                    } else {
                        MessageUtil.send(winner, "&a你赢得了拍卖 #" + latest.getId() + "！背包已满，物品已发送至邮箱");
                    }
                } else if (plugin.isClusterEnabled()) {
                    plugin.getClusterEventPublisher().requestRemoteGiveItem(
                            latest.getHighestBidderUuid(), latest.getItemData(),
                            "你赢得了拍卖 #" + latest.getId() + "！",
                            ignored -> saveMailAsync(winnerMail, false, true, null));
                    winnerHandled = true;
                }

                final boolean needSellerMail = !sellerHandled;
                final boolean needWinnerMail = !winnerHandled;
                if (needSellerMail || needWinnerMail) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        if (needSellerMail) {
                            storage.saveMail(sellerMail);
                            publishIfCluster(p -> p.publishMailCreated(latest.getSellerUuid(),
                                    true, false, sellerMail.getMessage()));
                        }
                        if (needWinnerMail) {
                            storage.saveMail(winnerMail);
                            publishIfCluster(p -> p.publishMailCreated(latest.getHighestBidderUuid(),
                                    false, true, winnerMail.getMessage()));
                        }
                    });
                }

                // 跨服广播拍卖结束
                publishIfCluster(p -> p.publishAuctionEnded(latest.getId(), latest.getSellerUuid(),
                        latest.getHighestBidderUuid(), latest.getHighestBidderName(),
                        latest.getCurrentPrice(), sellerReceive, true));
            });
        } else {
            MailEntry mail = new MailEntry();
            mail.setPlayerUuid(latest.getSellerUuid());
            mail.setItemData(latest.getItemData());
            mail.setItemStack(latest.getItemStack());
            mail.setMessage("拍卖 #" + latest.getId() + " 无人竞拍");

            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean handled = false;
                Player seller = Bukkit.getPlayer(latest.getSellerUuid());
                if (seller != null && seller.isOnline()) {
                    if (MessageUtil.hasInventorySpace(seller)) {
                        MessageUtil.giveItem(seller, latest.getItemStack());
                        MessageUtil.send(seller, "&e你的拍卖 #" + latest.getId() + " 无人竞拍，物品已退回！");
                        handled = true;
                    } else {
                        MessageUtil.send(seller, "&e你的拍卖 #" + latest.getId() + " 无人竞拍，背包已满，物品已发送至邮箱！");
                    }
                }
                if (!handled) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        storage.saveMail(mail);
                        publishIfCluster(p -> p.publishMailCreated(latest.getSellerUuid(),
                                false, true, mail.getMessage()));
                    });
                }

                // 跨服广播拍卖结束（无人竞拍）
                publishIfCluster(p -> p.publishAuctionEnded(latest.getId(), latest.getSellerUuid(),
                        null, null, 0, 0, false));
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

                // 异步原子取消
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean cancelled = storage.markAuctionCancelledIfActive(auctionId);
                    if (!cancelled) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) MessageUtil.send(player, "&c该拍卖已结束，无法取消！");
                        });
                        return;
                    }

                    // 取消成功，回主线程处理退款和物品
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;

                        // 退还竞拍者金额
                        MailEntry bidderMail = null;
                        if (auction.hasBidder()) {
                            Player bidder = Bukkit.getPlayer(auction.getHighestBidderUuid());
                            if (bidder != null && bidder.isOnline()) {
                                if (vault.deposit(bidder, auction.getCurrentPrice())) {
                                    MessageUtil.send(bidder, "&e拍卖 #" + auctionId + " 已被卖家取消，已退还 &a" +
                                            MessageUtil.formatMoney(auction.getCurrentPrice()));
                                } else {
                                    bidderMail = new MailEntry();
                                    bidderMail.setPlayerUuid(auction.getHighestBidderUuid());
                                    bidderMail.setMoney(auction.getCurrentPrice());
                                    bidderMail.setMessage("拍卖 #" + auctionId + " 已被卖家取消，竞拍金额已退还");
                                    MessageUtil.send(bidder, "&e拍卖退款入账失败，已转入邮箱，使用 &a/gmarket collect &e领取");
                                }
                            } else if (plugin.isClusterEnabled()) {
                                // 跨服尝试即时退款
                                MailEntry fallbackMail = new MailEntry();
                                fallbackMail.setPlayerUuid(auction.getHighestBidderUuid());
                                fallbackMail.setMoney(auction.getCurrentPrice());
                                fallbackMail.setMessage("拍卖 #" + auctionId + " 已被卖家取消，竞拍金额已退还");
                                plugin.getClusterEventPublisher().requestRemoteDeposit(
                                        auction.getHighestBidderUuid(), auction.getCurrentPrice(),
                                        "拍卖 #" + auctionId + " 已被卖家取消，已退还 " + MessageUtil.formatMoney(auction.getCurrentPrice()),
                                        ignored -> {
                                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                                storage.saveMail(fallbackMail);
                                                publishIfCluster(p -> p.publishMailCreated(auction.getHighestBidderUuid(),
                                                        true, false, fallbackMail.getMessage()));
                                            });
                                        });
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

                        // 异步写邮箱
                        final MailEntry finalBidderMail = bidderMail;
                        if (finalBidderMail != null) {
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                storage.saveMail(finalBidderMail);
                                publishIfCluster(p -> p.publishMailCreated(auction.getHighestBidderUuid(),
                                        true, false, finalBidderMail.getMessage()));
                            });
                        }

                        // 跨服广播拍卖取消
                        publishIfCluster(p -> p.publishAuctionCancelled(auctionId, auction.getSellerUuid(),
                                auction.getHighestBidderUuid(),
                                auction.hasBidder() ? auction.getCurrentPrice() : 0));
                    });
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
     * 使用原子抢占避免多服重复取消。
     */
    public void cancelAllActiveAuctions() {
        List<Auction> active = storage.getActiveAuctions();
        int cancelled = 0;
        for (Auction auction : active) {
            // 原子抢占取消
            if (!storage.markAuctionCancelledIfActive(auction.getId())) continue;
            cancelled++;

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

        if (cancelled > 0) {
            plugin.getLogger().info("已取消 " + cancelled + " 个遗留拍卖并退还物品/金额");
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

    private void publishIfCluster(java.util.function.Consumer<ClusterEventPublisher> action) {
        if (plugin.isClusterEnabled()) {
            action.accept(plugin.getClusterEventPublisher());
        }
    }

    private void saveMailAsync(MailEntry mail, boolean hasMoney, boolean hasItem, String notifyMessage) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            storage.saveMail(mail);
            publishIfCluster(p -> p.publishMailCreated(mail.getPlayerUuid(), hasMoney, hasItem, mail.getMessage()));
            if (notifyMessage != null && !notifyMessage.isEmpty()) {
                publishIfCluster(p -> p.requestPlayerNotify(mail.getPlayerUuid(), notifyMessage));
            }
        });
    }
}
