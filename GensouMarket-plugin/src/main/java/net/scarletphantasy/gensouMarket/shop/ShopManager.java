package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
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

        double pricePerUnit = shopItem.getCurrentBuyPrice();
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

        shopItem.addBought(amount);

        // 异步写DB
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(shopItem));

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
        item.setBaseBuyPrice(buyPrice);
        config.saveShopItem(id, item.getMaterial(), buyPrice);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(item));
        return true;
    }
}
