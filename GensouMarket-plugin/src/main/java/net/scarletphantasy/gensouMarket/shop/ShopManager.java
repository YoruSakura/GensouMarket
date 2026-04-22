package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import net.scarletphantasy.gensouMarket.util.ItemNameUtil;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ShopManager {

    private final GensouMarket plugin;
    private final StorageProvider storage;
    private final ConfigManager config;
    private final VaultHook vault;
    private final Map<String, ShopItem> shopItems = new LinkedHashMap<>();

    public ShopManager(GensouMarket plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        this.config = plugin.getConfigManager();
        this.vault = plugin.getVaultHook();
    }

    public void loadItems() {
        Map<String, ShopItem> configItems = config.loadShopItems();
        Map<String, ShopItem> dbData = storage.loadShopData();

        shopItems.clear();
        for (Map.Entry<String, ShopItem> entry : configItems.entrySet()) {
            String id = entry.getKey();
            ShopItem configItem = entry.getValue();

            if (dbData.containsKey(id)) {
                ShopItem dbItem = dbData.get(id);
                configItem.setTotalBought(dbItem.getTotalBought());
                configItem.setLastUpdate(dbItem.getLastUpdate());
                // fixed+limited：DB 有记录时持久化库存优先于配置
                if (configItem.isFixedLimited() && dbItem.getAvailableStock() >= 0) {
                    configItem.setAvailableStock(dbItem.getAvailableStock());
                }
            }

            // fixed+limited 且仍为 -1 哨兵：使用配置 initial-stock 作为初值
            if (configItem.isFixedLimited() && configItem.getAvailableStock() < 0) {
                int initial = config.getShopInitialStock(id, 0);
                configItem.setAvailableStock(initial);
            }

            shopItems.put(id, configItem);
        }
    }

    public void saveAllData() {
        storage.saveAllShopData(shopItems);
    }

    public Map<String, ShopItem> getShopItems() {
        return shopItems;
    }

    /**
     * 根据模式计算当前单价。recycled 模式按"当前回收价 × sellMultiplier"动态计算，下限 0.01。
     */
    public double computeCurrentPrice(ShopItem shopItem) {
        if (!shopItem.isRecycled()) {
            return shopItem.getCurrentBuyPrice();
        }
        RecycleItem source = resolveRecycleSource(shopItem);
        if (source == null) return 0.0;
        double fluctuation = plugin.getRecycleManager().getMarketFluctuation()
                .calculate(source.getId(), System.currentTimeMillis());
        double recyclePrice = source.getCurrentRecyclePrice(fluctuation);
        double price = recyclePrice * shopItem.getSellMultiplier();
        price = Math.round(price * 100.0) / 100.0;
        return Math.max(price, 0.01);
    }

    /**
     * 返回当前可售库存。fixed+unlimited 返回 Integer.MAX_VALUE，fixed+limited 返回 availableStock，
     * recycled 返回关联 RecycleItem.recycledStock；关联缺失返回 0。
     */
    public int computeAvailableStock(ShopItem shopItem) {
        if (shopItem.isFixedUnlimited()) return Integer.MAX_VALUE;
        if (shopItem.isFixedLimited()) return Math.max(shopItem.getAvailableStock(), 0);
        RecycleItem source = resolveRecycleSource(shopItem);
        return source == null ? 0 : Math.max(source.getRecycledStock(), 0);
    }

    public RecycleItem resolveRecycleSource(ShopItem shopItem) {
        if (!shopItem.isRecycled() || shopItem.getRecycleSourceId() == null) return null;
        return plugin.getRecycleManager().getRecycleItems().get(shopItem.getRecycleSourceId());
    }

    public boolean buyFromShop(Player player, String itemId, int amount) {
        if (!config.isShopEnabled()) {
            MessageUtil.send(player, "&c服务器商店未启用！");
            return false;
        }

        ShopItem shopItem = shopItems.get(itemId);
        if (shopItem == null) {
            MessageUtil.send(player, "&c未找到该商品！");
            return false;
        }

        if (amount <= 0) amount = 1;

        // recycled 模式：必须有可用来源
        RecycleItem recycleSource = null;
        if (shopItem.isRecycled()) {
            recycleSource = resolveRecycleSource(shopItem);
            if (recycleSource == null) {
                MessageUtil.send(player, "&c该回流商品的回收来源未配置，无法购买！");
                return false;
            }
        }

        // 先检查库存
        int available = computeAvailableStock(shopItem);
        if (available <= 0) {
            MessageUtil.send(player, "&c该商品已缺货！");
            return false;
        }
        if (available < amount) {
            MessageUtil.send(player, "&c库存不足，当前剩余 &e" + available + "&c 个！");
            return false;
        }

        // 再检查余额
        double pricePerUnit = computeCurrentPrice(shopItem);
        if (pricePerUnit <= 0) {
            MessageUtil.send(player, "&c该商品当前售价异常，无法购买！");
            return false;
        }
        double totalCost = pricePerUnit * amount;

        if (!vault.has(player, totalCost)) {
            MessageUtil.send(player, "&c你没有足够的金币！需要: &e" + MessageUtil.formatMoney(totalCost));
            return false;
        }

        if (!vault.withdraw(player, totalCost)) {
            MessageUtil.send(player, "&c扣款失败，请稍后重试购买！");
            return false;
        }

        ItemStack item = new ItemStack(shopItem.getMaterial(), amount);
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            MessageUtil.send(player, "&e背包已满，物品已掉落在你脚下！");
        }

        // 扣减库存（按模式）
        shopItem.addBought(amount);
        if (shopItem.isFixedLimited()) {
            shopItem.setAvailableStock(Math.max(shopItem.getAvailableStock() - amount, 0));
            shopItem.setLastUpdate(System.currentTimeMillis());
            ShopItem snapshot = shopItem;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(snapshot));
        } else if (shopItem.isRecycled() && recycleSource != null) {
            recycleSource.setRecycledStock(Math.max(recycleSource.getRecycledStock() - amount, 0));
            recycleSource.setLastUpdate(System.currentTimeMillis());
            RecycleItem snapshot = recycleSource;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveRecycleData(snapshot));
            // 同时更新 shop 端 totalBought
            ShopItem shopSnapshot = shopItem;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(shopSnapshot));
        } else {
            // fixed+unlimited 只更新 totalBought
            ShopItem snapshot = shopItem;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(snapshot));
        }

        MessageUtil.send(player, Component.text("成功购买 ", NamedTextColor.GREEN)
                .append(Component.text(amount + "x ", NamedTextColor.YELLOW))
                .append(ItemNameUtil.getLocalizedName(shopItem.getMaterial()).color(NamedTextColor.YELLOW))
                .append(Component.text(" 花费: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(totalCost), NamedTextColor.YELLOW))
                .append(Component.text(" (单价: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(pricePerUnit), NamedTextColor.YELLOW))
                .append(Component.text(")", NamedTextColor.GREEN)));
        return true;
    }

    public boolean addItem(String id, Material material, double buyPrice) {
        if (shopItems.containsKey(id)) return false;
        ShopItem item = new ShopItem(id, material, buyPrice);
        // /gmarket shop add 仅创建 fixed+unlimited 商品（task-05）
        item.setMode(ShopItem.Mode.FIXED);
        item.setStockMode(ShopItem.StockMode.UNLIMITED);
        shopItems.put(id, item);
        config.saveShopItem(id, material, buyPrice);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(item));
        return true;
    }

    public boolean removeItem(String id) {
        if (!shopItems.containsKey(id)) return false;
        shopItems.remove(id);
        config.removeShopItem(id);
        return true;
    }

    public boolean setPrice(String id, double buyPrice) {
        ShopItem item = shopItems.get(id);
        if (item == null) return false;
        if (item.isRecycled()) return false;
        item.setBaseBuyPrice(buyPrice);
        // 只改 buy-price，保留 mode / stock-mode / initial-stock 等原有配置
        config.updateShopBuyPrice(id, buyPrice);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(item));
        return true;
    }

    /**
     * 仅适用于 fixed+limited 商品。返回 true 表示设置成功。
     */
    public boolean setStock(String id, int amount) {
        ShopItem item = shopItems.get(id);
        if (item == null) return false;
        if (!item.isFixedLimited()) return false;
        if (amount < 0) return false;
        item.setAvailableStock(amount);
        item.setLastUpdate(System.currentTimeMillis());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(item));
        return true;
    }
}
