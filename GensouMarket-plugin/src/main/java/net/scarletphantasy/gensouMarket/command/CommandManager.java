package net.scarletphantasy.gensouMarket.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.gui.AuctionGui;
import net.scarletphantasy.gensouMarket.gui.MarketGui;
import net.scarletphantasy.gensouMarket.gui.RecycleGui;
import net.scarletphantasy.gensouMarket.gui.ShopGui;
import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.util.ItemNameUtil;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandManager implements TabExecutor {

    private final GensouMarket plugin;

    public CommandManager(GensouMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                MarketGui.openMainMenu(plugin, player);
            } else {
                showHelp(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "sell" -> handleSell(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "buy" -> handleBuy(sender, args);
            case "search" -> handleSearch(sender, args);
            case "my" -> handleMyListings(sender);
            case "auction" -> handleAuction(sender, args);
            case "bid" -> handleBid(sender, args);
            case "shop" -> handleShop(sender, args);
            case "recycle" -> handleRecycle(sender, args);
            case "trade" -> handleTradeCommand(sender, args);
            case "collect" -> handleCollect(sender);
            case "reload" -> handleReload(sender);
            case "help" -> showHelp(sender);
            default -> MessageUtil.send(sender, "&c未知命令！使用 &e/" + label + " help &c查看帮助");
        }
        return true;
    }

    private void handleSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (!player.hasPermission("gensoumarket.sell")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(player, "&c用法: /gmarket sell <价格>");
            return;
        }
        try {
            double price = Double.parseDouble(args[1]);
            plugin.getMarketManager().sellItem(player, price);
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c无效的价格！");
        }
    }

    private void handleCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(player, "&c用法: /gmarket cancel <ID>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            plugin.getMarketManager().cancelListing(player, id);
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c无效的 ID！");
        }
    }

    private void handleBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (!player.hasPermission("gensoumarket.buy")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(player, "&c用法: /gmarket buy <ID>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            plugin.getMarketManager().buyListing(player, id);
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c无效的 ID！");
        }
    }

    private void handleSearch(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        String keyword = args.length > 1 ? args[1] : null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketListing> all = plugin.getMarketManager().getActiveListings();
            List<MarketListing> results = plugin.getMarketManager().searchListings(keyword, all);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (results.isEmpty()) {
                    MessageUtil.send(player, "&e没有找到相关物品！");
                    return;
                }
                MarketGui.openMarketBrowse(plugin, player, results, 0);
            });
        });
    }

    private void handleMyListings(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketListing> listings = plugin.getMarketManager().getPlayerListings(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (listings.isEmpty()) {
                    MessageUtil.send(player, "&e你没有正在出售的物品！");
                    return;
                }
                MessageUtil.send(player, "&6=== 你的上架物品 ===");
                for (MarketListing listing : listings) {
                    String itemName = listing.getItemStack() != null ? listing.getItemStack().getType().name() : "UNKNOWN";
                    int amount = listing.getItemStack() != null ? listing.getItemStack().getAmount() : 0;
                    MessageUtil.sendNoPrefix(player, "&e#" + listing.getId() + " &f" + amount + "x " + itemName
                            + " &a价格: &e" + MessageUtil.formatMoney(listing.getPrice()));
                }
            });
        });
    }

    private void handleAuction(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (!player.hasPermission("gensoumarket.auction")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(player, "&c用法: /gmarket auction <起拍价> [时长(分钟)]");
            MessageUtil.send(player, "&c用法: /gmarket auction cancel <拍卖ID>");
            MessageUtil.send(player, "&c用法: /gmarket auction list");
            return;
        }

        if (args[1].equalsIgnoreCase("cancel")) {
            if (args.length < 3) {
                MessageUtil.send(player, "&c用法: /gmarket auction cancel <拍卖ID>");
                return;
            }
            try {
                int auctionId = Integer.parseInt(args[2]);
                plugin.getAuctionManager().cancelAuction(player, auctionId);
            } catch (NumberFormatException e) {
                MessageUtil.send(player, "&c无效的拍卖 ID！");
            }
            return;
        }

        if (args[1].equalsIgnoreCase("list")) {
            handleAuctions(sender);
            return;
        }

        try {
            double startingPrice = Double.parseDouble(args[1]);
            int duration = args.length > 2 ? Integer.parseInt(args[2])
                    : plugin.getConfigManager().getDefaultAuctionDuration();
            plugin.getAuctionManager().createAuction(player, startingPrice, duration);
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c无效的参数！");
        }
    }

    private void handleBid(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (!player.hasPermission("gensoumarket.auction")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 3) {
            MessageUtil.send(player, "&c用法: /gmarket bid <拍卖ID> <出价>");
            return;
        }
        try {
            int auctionId = Integer.parseInt(args[1]);
            double amount = Double.parseDouble(args[2]);
            plugin.getAuctionManager().placeBid(player, auctionId, amount);
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c无效的参数！");
        }
    }

    private void handleAuctions(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Auction> auctions = plugin.getAuctionManager().getActiveAuctions();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    AuctionGui.openAuctions(plugin, player, auctions, 0);
                }
            });
        });
    }

    private void handleShop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (!player.hasPermission("gensoumarket.shop")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 2) {
            ShopGui.openShop(plugin, player, 0);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "buy" -> {
                if (args.length < 3) {
                    MessageUtil.send(player, "&c用法: /gmarket shop buy <物品ID> [数量]");
                    return;
                }
                String itemId = args[2].toLowerCase();
                int amount = args.length > 3 ? parseInt(args[3], 1) : 1;
                plugin.getShopManager().buyFromShop(player, itemId, amount);
            }
            case "prices" -> showShopPrices(player);
            case "add" -> handleShopAdd(player, args);
            case "remove" -> handleShopRemove(player, args);
            case "setprice" -> handleShopSetPrice(player, args);
            default -> MessageUtil.send(player, "&c未知子命令！用法: shop [buy|prices|add|remove|setprice]");
        }
    }

    private void handleRecycle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (!player.hasPermission("gensoumarket.recycle")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 2) {
            RecycleGui.openRecycle(plugin, player, 0);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "sell" -> {
                int amount = args.length > 2 ? parseInt(args[2], -1) : -1;
                ItemStack handItem = player.getInventory().getItemInMainHand();
                if (handItem.getType().isAir()) {
                    MessageUtil.send(player, "&c请手持要回收的物品！");
                    return;
                }
                RecycleItem recycleItem = plugin.getRecycleManager().getItemByMaterial(handItem.getType());
                plugin.getRecycleManager().recycleItem(player, recycleItem, amount);
            }
            case "prices" -> showRecyclePrices(player);
            case "add" -> handleRecycleAdd(player, args);
            case "remove" -> handleRecycleRemove(player, args);
            case "setprice" -> handleRecycleSetPrice(player, args);
            default -> MessageUtil.send(player, "&c未知子命令！用法: recycle [sell|prices|add|remove|setprice]");
        }
    }

    private void handleTradeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        if (!player.hasPermission("gensoumarket.trade")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(player, "&c用法: /gmarket trade <accept|deny|cancel>");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "accept" -> plugin.getTradeManager().acceptRequest(player);
            case "deny" -> plugin.getTradeManager().denyRequest(player);
            case "cancel" -> plugin.getTradeManager().cancelRequest(player);
            default -> MessageUtil.send(player, "&c用法: /gmarket trade <accept|deny|cancel>");
        }
    }

    private void handleCollect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&c该命令只能由玩家执行！");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MailEntry> mail = plugin.getStorage().getPlayerMail(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (mail.isEmpty()) {
                    MessageUtil.send(player, "&e你没有待领取的物品或金币！");
                    return;
                }

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<MailEntry> claimedMail = new ArrayList<>();
                    for (MailEntry entry : mail) {
                        if (plugin.getStorage().claimMail(entry.getId())) {
                            claimedMail.add(entry);
                        }
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            if (!claimedMail.isEmpty()) {
                                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                    for (MailEntry entry : claimedMail) {
                                        plugin.getStorage().unclaimMail(entry.getId());
                                    }
                                });
                            }
                            return;
                        }

                        if (claimedMail.isEmpty()) {
                            MessageUtil.send(player, "&e待领取内容已被其他操作处理，请重新执行 /gmarket collect");
                            return;
                        }

                        int itemCount = 0;
                        double totalMoney = 0;
                        List<Integer> rollbackIds = new ArrayList<>();

                        for (MailEntry entry : claimedMail) {
                            boolean success = !entry.hasItem() || entry.getItemStack() != null;

                            if (success && entry.hasMoney() && !plugin.getVaultHook().deposit(player, entry.getMoney())) {
                                success = false;
                            }

                            if (success && entry.hasItem()) {
                                HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(entry.getItemStack());
                                if (!overflow.isEmpty()) {
                                    for (ItemStack drop : overflow.values()) {
                                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                                    }
                                }
                                itemCount++;
                            }

                            if (success) {
                                totalMoney += entry.getMoney();
                            } else {
                                rollbackIds.add(entry.getId());
                            }
                        }

                        if (!rollbackIds.isEmpty()) {
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                for (int id : rollbackIds) {
                                    plugin.getStorage().unclaimMail(id);
                                }
                            });
                        }

                        if (totalMoney <= 0 && itemCount <= 0) {
                            MessageUtil.send(player, "&c领取失败，内容已保留在邮箱中，请稍后重试！");
                            return;
                        }

                        StringBuilder msg = new StringBuilder("&a已领取 ");
                        if (totalMoney > 0) {
                            msg.append("&e").append(MessageUtil.formatMoney(totalMoney)).append(" 金币 ");
                        }
                        if (itemCount > 0) {
                            msg.append("&e").append(itemCount).append(" 件物品");
                        }
                        if (!rollbackIds.isEmpty()) {
                            msg.append(" &e(部分内容领取失败，已保留在邮箱)");
                        }
                        MessageUtil.send(player, msg.toString());
                    });
                });
            });
        });
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("gensoumarket.admin.reload")) {
            MessageUtil.send(sender, "&c你没有权限执行此命令！");
            return;
        }
        plugin.getConfigManager().reload();
        plugin.getShopManager().loadItems();
        plugin.getRecycleManager().loadItems();
        MessageUtil.send(sender, "&a配置已重载！");
    }

    private void showHelp(CommandSender sender) {
        MessageUtil.sendNoPrefix(sender, "&6&l======= 幻想集市 帮助 =======");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket &f- 打开市场界面");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket sell <价格> &f- 上架手持物品");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket cancel <ID> &f- 下架物品");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket buy <ID> &f- 购买上架物品");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket search [关键词] &f- 搜索市场");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket my &f- 查看我的上架");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket auction <起拍价> [时长] &f- 发起拍卖");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket auction cancel <ID> &f- 取消拍卖(手续费不退)");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket auction list &f- 查看进行中的拍卖");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket bid <ID> <出价> &f- 竞拍");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket shop &f- 打开服务器商店");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket shop buy <物品ID> [数量] &f- 从商店购买");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket shop prices &f- 查看商店价格");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket recycle &f- 打开回收站");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket recycle sell [数量] &f- 向回收站出售手持物品");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket recycle prices &f- 查看回收价格");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket collect &f- 领取待收物品/金币");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket trade accept &f- 接受交易请求");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket trade deny &f- 拒绝交易请求");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket trade cancel &f- 取消已发起的交易请求");
        MessageUtil.sendNoPrefix(sender, "&7蹲下右键点击玩家可发起交易");
        if (sender.hasPermission("gensoumarket.admin.reload")) {
            MessageUtil.sendNoPrefix(sender, "&c/gmarket reload &f- 重载配置");
        }
        if (sender.hasPermission("gensoumarket.admin.shop.edit")) {
            MessageUtil.sendNoPrefix(sender, "&c/gmarket shop add <价格> &f- 手持物品添加商品");
            MessageUtil.sendNoPrefix(sender, "&c/gmarket shop add <物品> <价格> &f- 指定物品添加商品");
            MessageUtil.sendNoPrefix(sender, "&c/gmarket shop remove <物品ID> &f- 移除商品");
            MessageUtil.sendNoPrefix(sender, "&c/gmarket shop setprice <物品ID> <买入价> &f- 修改价格");
        }
        if (sender.hasPermission("gensoumarket.admin.recycle.edit")) {
            MessageUtil.sendNoPrefix(sender, "&c/gmarket recycle add <回收价> &f- 手持物品添加回收");
            MessageUtil.sendNoPrefix(sender, "&c/gmarket recycle add <物品> <回收价> &f- 指定物品添加回收");
            MessageUtil.sendNoPrefix(sender, "&c/gmarket recycle remove <物品ID> &f- 移除回收物品");
            MessageUtil.sendNoPrefix(sender, "&c/gmarket recycle setprice <物品ID> <回收价> &f- 修改回收价格");
        }
    }

    private MaterialAndPrice parseMaterialAndPrice(Player player, String[] args) {
        Material material;
        double price;
        if (args.length == 3) {
            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem.getType().isAir()) {
                return null;
            }
            material = handItem.getType();
            try {
                price = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                MessageUtil.send(player, "&c无效的价格！");
                return null;
            }
        } else {
            material = Material.matchMaterial(args[2].toUpperCase());
            if (material == null) {
                MessageUtil.send(player, "&c无效的物品名称: " + args[2]);
                return null;
            }
            try {
                price = Double.parseDouble(args[3]);
            } catch (NumberFormatException e) {
                MessageUtil.send(player, "&c无效的价格！");
                return null;
            }
        }
        return new MaterialAndPrice(material, price);
    }

    private record MaterialAndPrice(Material material, double price) {
    }

    private void handleShopAdd(Player player, String[] args) {
        if (!player.hasPermission("gensoumarket.admin.shop.edit")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 3) {
            MessageUtil.send(player, "&c用法: /gmarket shop add <价格> (手持物品)");
            MessageUtil.send(player, "&c用法: /gmarket shop add <物品> <价格>");
            return;
        }
        MaterialAndPrice parsed = parseMaterialAndPrice(player, args);
        if (parsed == null) {
            if (args.length == 3 && player.getInventory().getItemInMainHand().getType().isAir()) {
                MessageUtil.send(player, "&c请手持要添加的物品，或使用 /gmarket shop add <物品> <价格>");
            }
            return;
        }

        String itemId = parsed.material().name().toLowerCase();
        if (plugin.getShopManager().addItem(itemId, parsed.material(), parsed.price())) {
            MessageUtil.send(player, "&a成功添加商品: &e" + itemId + " &a(" + parsed.material().name()
                    + ") 价格: &e" + MessageUtil.formatMoney(parsed.price()));
        } else {
            MessageUtil.send(player, "&c该物品已存在！");
        }
    }

    private void handleShopRemove(Player player, String[] args) {
        if (!player.hasPermission("gensoumarket.admin.shop.edit")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 3) {
            MessageUtil.send(player, "&c用法: /gmarket shop remove <物品ID>");
            return;
        }
        String itemId = args[2].toLowerCase();
        if (plugin.getShopManager().removeItem(itemId)) {
            MessageUtil.send(player, "&a成功移除商品: &e" + itemId);
        } else {
            MessageUtil.send(player, "&c未找到该商品！");
        }
    }

    private void handleShopSetPrice(Player player, String[] args) {
        if (!player.hasPermission("gensoumarket.admin.shop.edit")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 4) {
            MessageUtil.send(player, "&c用法: /gmarket shop setprice <物品ID> <买入价>");
            return;
        }
        String itemId = args[2].toLowerCase();
        try {
            double buyPrice = Double.parseDouble(args[3]);
            if (plugin.getShopManager().setPrice(itemId, buyPrice)) {
                MessageUtil.send(player, "&a成功修改 &e" + itemId + " &a价格: &e"
                        + MessageUtil.formatMoney(buyPrice));
            } else {
                MessageUtil.send(player, "&c未找到该商品！");
            }
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c无效的价格！");
        }
    }

    private void handleRecycleAdd(Player player, String[] args) {
        if (!player.hasPermission("gensoumarket.admin.recycle.edit")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 3) {
            MessageUtil.send(player, "&c用法: /gmarket recycle add <回收价> (手持物品)");
            MessageUtil.send(player, "&c用法: /gmarket recycle add <物品> <回收价>");
            return;
        }
        MaterialAndPrice parsed = parseMaterialAndPrice(player, args);
        if (parsed == null) {
            if (args.length == 3 && player.getInventory().getItemInMainHand().getType().isAir()) {
                MessageUtil.send(player, "&c请手持要添加的物品，或使用 /gmarket recycle add <物品> <回收价>");
            }
            return;
        }

        String itemId = parsed.material().name().toLowerCase();
        if (plugin.getRecycleManager().addItem(itemId, parsed.material(), parsed.price())) {
            MessageUtil.send(player, "&a成功添加回收物品: &e" + itemId + " &a(" + parsed.material().name()
                    + ") 回收价: &e" + MessageUtil.formatMoney(parsed.price()));
        } else {
            MessageUtil.send(player, "&c该物品已存在！");
        }
    }

    private void handleRecycleRemove(Player player, String[] args) {
        if (!player.hasPermission("gensoumarket.admin.recycle.edit")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 3) {
            MessageUtil.send(player, "&c用法: /gmarket recycle remove <物品ID>");
            return;
        }
        String itemId = args[2].toLowerCase();
        if (plugin.getRecycleManager().removeItem(itemId)) {
            MessageUtil.send(player, "&a成功移除回收物品: &e" + itemId);
        } else {
            MessageUtil.send(player, "&c未找到该物品！");
        }
    }

    private void handleRecycleSetPrice(Player player, String[] args) {
        if (!player.hasPermission("gensoumarket.admin.recycle.edit")) {
            MessageUtil.send(player, "&c你没有权限执行此命令！");
            return;
        }
        if (args.length < 4) {
            MessageUtil.send(player, "&c用法: /gmarket recycle setprice <物品ID> <回收价>");
            return;
        }
        String itemId = args[2].toLowerCase();
        try {
            double recyclePrice = Double.parseDouble(args[3]);
            if (plugin.getRecycleManager().setPrice(itemId, recyclePrice)) {
                MessageUtil.send(player, "&a成功修改 &e" + itemId + " &a回收价: &e"
                        + MessageUtil.formatMoney(recyclePrice));
            } else {
                MessageUtil.send(player, "&c未找到该物品！");
            }
        } catch (NumberFormatException e) {
            MessageUtil.send(player, "&c无效的价格！");
        }
    }

    private void showShopPrices(Player player) {
        Map<String, ShopItem> items = plugin.getShopManager().getShopItems();
        if (items.isEmpty()) {
            MessageUtil.send(player, "&e商店没有任何物品！");
            return;
        }
        MessageUtil.send(player, "&6=== 服务器商店价格 ===");
        for (ShopItem item : items.values()) {
            MessageUtil.sendNoPrefix(player, ItemNameUtil.getLocalizedName(item.getMaterial())
                    .append(Component.text(" 购买: ", NamedTextColor.GREEN))
                    .append(Component.text(MessageUtil.formatMoney(item.getCurrentBuyPrice()), NamedTextColor.YELLOW)));
        }
    }

    private void showRecyclePrices(Player player) {
        Map<String, RecycleItem> items = plugin.getRecycleManager().getRecycleItems();
        if (items.isEmpty()) {
            MessageUtil.send(player, "&e回收站没有任何物品！");
            return;
        }
        MessageUtil.send(player, "&6=== 回收站价格 ===");
        for (RecycleItem item : items.values()) {
            MessageUtil.sendNoPrefix(player, ItemNameUtil.getLocalizedName(item.getMaterial())
                    .append(Component.text(" 回收: ", NamedTextColor.RED))
                    .append(Component.text(MessageUtil.formatMoney(item.getCurrentRecyclePrice()), NamedTextColor.YELLOW)));
        }
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String @NotNull [] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of(
                    "sell", "cancel", "buy", "search", "my",
                    "auction", "bid", "shop", "recycle", "collect", "trade", "help"
            ));
            if (sender.hasPermission("gensoumarket.admin.reload")) {
                subs.add("reload");
            }
            String input = args[0].toLowerCase();
            for (String sub : subs) {
                if (sub.startsWith(input)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "auction" -> {
                    for (String value : List.of("cancel", "list")) {
                        if (value.startsWith(args[1].toLowerCase())) {
                            completions.add(value);
                        }
                    }
                }
                case "trade" -> {
                    for (String value : List.of("accept", "deny", "cancel")) {
                        if (value.startsWith(args[1].toLowerCase())) {
                            completions.add(value);
                        }
                    }
                }
                case "shop" -> {
                    List<String> shopSubs = new ArrayList<>(List.of("buy", "prices"));
                    if (sender.hasPermission("gensoumarket.admin.shop.edit")) {
                        shopSubs.addAll(List.of("add", "remove", "setprice"));
                    }
                    for (String value : shopSubs) {
                        if (value.startsWith(args[1].toLowerCase())) {
                            completions.add(value);
                        }
                    }
                }
                case "recycle" -> {
                    List<String> recycleSubs = new ArrayList<>(List.of("sell", "prices"));
                    if (sender.hasPermission("gensoumarket.admin.recycle.edit")) {
                        recycleSubs.addAll(List.of("add", "remove", "setprice"));
                    }
                    for (String value : recycleSubs) {
                        if (value.startsWith(args[1].toLowerCase())) {
                            completions.add(value);
                        }
                    }
                }
                default -> {
                }
            }
        } else if (args.length == 3) {
            String mainCmd = args[0].toLowerCase();
            String subCmd = args[1].toLowerCase();
            String input = args[2].toLowerCase();

            if (mainCmd.equals("shop")) {
                if (subCmd.equals("buy") || subCmd.equals("remove") || subCmd.equals("setprice")) {
                    for (String id : plugin.getShopManager().getShopItems().keySet()) {
                        if (id.startsWith(input)) {
                            completions.add(id);
                        }
                    }
                } else if (subCmd.equals("add") && sender.hasPermission("gensoumarket.admin.shop.edit")) {
                    for (Material material : Material.values()) {
                        if (material.isItem() && material.name().toLowerCase().startsWith(input)) {
                            completions.add(material.name());
                        }
                    }
                }
            } else if (mainCmd.equals("recycle")) {
                if (subCmd.equals("remove") || subCmd.equals("setprice")) {
                    for (String id : plugin.getRecycleManager().getRecycleItems().keySet()) {
                        if (id.startsWith(input)) {
                            completions.add(id);
                        }
                    }
                } else if (subCmd.equals("add") && sender.hasPermission("gensoumarket.admin.recycle.edit")) {
                    for (Material material : Material.values()) {
                        if (material.isItem() && material.name().toLowerCase().startsWith(input)) {
                            completions.add(material.name());
                        }
                    }
                }
            } else if (mainCmd.equals("auction") && subCmd.equals("cancel")) {
                for (Auction auction : plugin.getAuctionManager().getActiveAuctions()) {
                    String auctionId = String.valueOf(auction.getId());
                    if (auctionId.startsWith(input)) {
                        completions.add(auctionId);
                    }
                }
            }
        }

        return completions;
    }
}
