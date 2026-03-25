package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class AuctionGui {

    private static final int PAGE_SIZE = 45;

    private AuctionGui() {}

    public static void openAuctions(GensouMarket plugin, Player player, int page) {
        List<Auction> auctions = plugin.getAuctionManager().getActiveAuctions();
        openAuctions(plugin, player, auctions, page);
    }

    public static void openAuctions(GensouMarket plugin, Player player, List<Auction> auctions, int page) {

        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.AUCTION_LIST);
        holder.setData("page", page);
        holder.setData("auctions", auctions);

        int totalPages = Math.max(1, (int) Math.ceil((double) auctions.size() / PAGE_SIZE));
        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.YELLOW + "拍卖行 " + ChatColor.GRAY + "(" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        if (auctions.isEmpty()) {
            inv.setItem(22, MarketGui.createMenuItem(Material.BARRIER, "&7当前没有进行中的拍卖"));
        } else {
            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, auctions.size());

            for (int i = start; i < end; i++) {
                Auction auction = auctions.get(i);
                ItemStack display = auction.getItemStack() != null ? auction.getItemStack().clone() : new ItemStack(Material.BARRIER);
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add("");
                    lore.add(MessageUtil.color("&6起拍价: &e" + MessageUtil.formatMoney(auction.getStartingPrice())));
                    lore.add(MessageUtil.color("&6当前价: &e" + MessageUtil.formatMoney(auction.getCurrentPrice())));
                    lore.add(MessageUtil.color("&7卖家: &f" + auction.getSellerName()));
                    String bidder = auction.hasBidder() ? auction.getHighestBidderName() : "无";
                    lore.add(MessageUtil.color("&7最高出价人: &f" + bidder));
                    long remainMs = auction.getEndTime() - System.currentTimeMillis();
                    lore.add(MessageUtil.color("&7剩余时间: &f" + formatTime(remainMs)));
                    lore.add(MessageUtil.color("&7ID: &f#" + auction.getId()));
                    lore.add("");
                    lore.add(MessageUtil.color("&a点击查看详情/竞拍"));
                    meta.setLore(lore);
                    display.setItemMeta(meta);
                }
                inv.setItem(i - start, display);
            }
        }

        // 导航栏
        ItemStack filler = MarketGui.createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        if (page > 0) {
            inv.setItem(45, MarketGui.createMenuItem(Material.ARROW, "&a上一页"));
        }
        inv.setItem(49, MarketGui.createMenuItem(Material.BARRIER, "&c返回主菜单"));
        if (page < totalPages - 1) {
            inv.setItem(53, MarketGui.createMenuItem(Material.ARROW, "&a下一页"));
        }

        player.openInventory(inv);
    }

    public static void openAuctionDetail(GensouMarket plugin, Player player, Auction auction) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.AUCTION_DETAIL);
        holder.setData("auctionId", auction.getId());
        holder.setData("auction", auction);

        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.YELLOW + "拍卖详情 #" + auction.getId());
        holder.setInventory(inv);

        // 上方区域填充黑色玻璃板
        ItemStack bgFiller = MarketGui.createMenuItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 45; i++) inv.setItem(i, bgFiller);

        // 中央展示拍卖物品 (slot 13)
        ItemStack display = auction.getItemStack() != null ? auction.getItemStack().clone() : new ItemStack(Material.BARRIER);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(MessageUtil.color("&6起拍价: &e" + MessageUtil.formatMoney(auction.getStartingPrice())));
            lore.add(MessageUtil.color("&6当前价: &e" + MessageUtil.formatMoney(auction.getCurrentPrice())));
            lore.add(MessageUtil.color("&7卖家: &f" + auction.getSellerName()));
            String bidder = auction.hasBidder() ? auction.getHighestBidderName() : "无";
            lore.add(MessageUtil.color("&7最高出价人: &f" + bidder));
            long remainMs = auction.getEndTime() - System.currentTimeMillis();
            lore.add(MessageUtil.color("&7剩余时间: &f" + formatTime(remainMs)));
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        inv.setItem(13, display);

        // 信息展示
        double currentPrice = auction.getCurrentPrice();

        inv.setItem(22, MarketGui.createMenuItem(Material.PAPER, "&e拍卖信息",
                "&7当前价格: &e" + MessageUtil.formatMoney(currentPrice),
                "&7出价人: &f" + (auction.hasBidder() ? auction.getHighestBidderName() : "暂无"),
                "&7剩余: &f" + formatTime(auction.getEndTime() - System.currentTimeMillis()),
                "",
                "&7使用下方按钮进行竞拍"));

        // 底部导航栏 - 加价按钮
        // 左侧：固定金额加价 (45-48)
        inv.setItem(45, createBidButton(Material.LIME_STAINED_GLASS_PANE,
                "&a+10", currentPrice + 10));
        inv.setItem(46, createBidButton(Material.GREEN_STAINED_GLASS_PANE,
                "&a+100", currentPrice + 100));
        inv.setItem(47, createBidButton(Material.CYAN_STAINED_GLASS_PANE,
                "&b+1,000", currentPrice + 1000));
        inv.setItem(48, createBidButton(Material.BLUE_STAINED_GLASS_PANE,
                "&9+5,000", currentPrice + 5000));

        // 中间：返回按钮 (49)
        inv.setItem(49, MarketGui.createMenuItem(Material.BARRIER, "&c返回拍卖列表"));

        // 右侧：百分比加价 (50-53)
        inv.setItem(50, createBidButton(Material.YELLOW_STAINED_GLASS_PANE,
                "&e+1%", Math.round(currentPrice * 1.01 * 100.0) / 100.0));
        inv.setItem(51, createBidButton(Material.ORANGE_STAINED_GLASS_PANE,
                "&6+5%", Math.round(currentPrice * 1.05 * 100.0) / 100.0));
        inv.setItem(52, createBidButton(Material.RED_STAINED_GLASS_PANE,
                "&c+10%", Math.round(currentPrice * 1.10 * 100.0) / 100.0));
        inv.setItem(53, createBidButton(Material.PURPLE_STAINED_GLASS_PANE,
                "&5+20%", Math.round(currentPrice * 1.20 * 100.0) / 100.0));

        player.openInventory(inv);
    }

    private static ItemStack createBidButton(Material material, String label, double bidAmount) {
        return MarketGui.createMenuItem(material, label,
                "&7点击出价: &e" + MessageUtil.formatMoney(bidAmount));
    }

    static String formatTime(long ms) {
        if (ms <= 0) return "已结束";
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return hours + "时" + (minutes % 60) + "分";
        if (minutes > 0) return minutes + "分" + (seconds % 60) + "秒";
        return seconds + "秒";
    }
}
