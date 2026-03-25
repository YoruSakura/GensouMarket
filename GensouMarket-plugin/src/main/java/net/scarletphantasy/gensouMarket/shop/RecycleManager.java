package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import net.scarletphantasy.gensouMarket.util.ItemNameUtil;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public class RecycleManager {

    private final GensouMarket plugin;
    private final StorageProvider storage;
    private final ConfigManager config;
    private final VaultHook vault;
    private final PriceEngine priceEngine;
    private final MarketFluctuation marketFluctuation;
    private final Map<String, RecycleItem> recycleItems = new LinkedHashMap<>();

    public RecycleManager(GensouMarket plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        this.config = plugin.getConfigManager();
        this.vault = plugin.getVaultHook();
        this.priceEngine = new PriceEngine(config);
        this.marketFluctuation = new MarketFluctuation(config);
    }

    public void loadItems() {
        Map<String, RecycleItem> configItems = config.loadRecycleItems();
        Map<String, RecycleItem> dbData = storage.loadRecycleData();

        recycleItems.clear();
        for (Map.Entry<String, RecycleItem> entry : configItems.entrySet()) {
            String id = entry.getKey();
            RecycleItem configItem = entry.getValue();

            if (dbData.containsKey(id)) {
                RecycleItem dbItem = dbData.get(id);
                configItem.setTotalRecycled(dbItem.getTotalRecycled());
                configItem.setRecycleMultiplier(dbItem.getRecycleMultiplier());
                configItem.setLastUpdate(dbItem.getLastUpdate());
            }

            recycleItems.put(id, configItem);
        }
    }

    public void saveAllData() {
        storage.saveAllRecycleData(recycleItems);
    }

    public Map<String, RecycleItem> getRecycleItems() {
        return recycleItems;
    }

    public RecycleItem getItemByMaterial(Material material) {
        for (RecycleItem item : recycleItems.values()) {
            if (item.getMaterial() == material) return item;
        }
        return null;
    }

    public RecycleItem getItem(String id) {
        return recycleItems.get(id);
    }

    public boolean recycleItem(Player player, RecycleItem recycleItem, int amount) {
        if (!config.isRecycleEnabled()) {
            MessageUtil.send(player, "&c回收站未启用！");
            return false;
        }

        if (recycleItem == null) {
            MessageUtil.send(player, "&c该物品不在回收列表中！");
            return false;
        }

        Material material = recycleItem.getMaterial();
        int available = countMaterial(player, material);
        if (available <= 0) {
            MessageUtil.send(player, "&c你的背包中没有该物品！");
            return false;
        }

        if (amount <= 0 || amount > available) {
            amount = available;
        }

        double fluctuation = marketFluctuation.calculate(recycleItem.getId(), System.currentTimeMillis());
        double pricePerUnit = recycleItem.getCurrentRecyclePrice(fluctuation);
        double totalEarning = pricePerUnit * amount;

        removeMaterial(player, material, amount);

        vault.deposit(player, totalEarning);

        priceEngine.onPlayerRecycle(recycleItem, amount);

        // 异步写DB
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            storage.saveRecycleData(recycleItem);
        });

        MessageUtil.send(player, Component.text("成功回收 ", NamedTextColor.GREEN)
                .append(Component.text(amount + "x ", NamedTextColor.YELLOW))
                .append(ItemNameUtil.getLocalizedName(recycleItem.getMaterial()).color(NamedTextColor.YELLOW))
                .append(Component.text(" 获得: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(totalEarning), NamedTextColor.YELLOW))
                .append(Component.text(" (单价: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(pricePerUnit), NamedTextColor.YELLOW))
                .append(Component.text(")", NamedTextColor.GREEN)));
        return true;
    }

    private int countMaterial(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeMaterial(Player player, Material material, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == material) {
                if (item.getAmount() <= remaining) {
                    remaining -= item.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - remaining);
                    remaining = 0;
                }
            }
        }
    }

    public PriceEngine getPriceEngine() {
        return priceEngine;
    }

    public MarketFluctuation getMarketFluctuation() {
        return marketFluctuation;
    }

    public boolean addItem(String id, Material material, double recyclePrice) {
        if (recycleItems.containsKey(id)) return false;
        RecycleItem item = new RecycleItem(id, material, recyclePrice);
        recycleItems.put(id, item);
        config.saveRecycleItem(id, material, recyclePrice);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveRecycleData(item));
        return true;
    }

    public boolean removeItem(String id) {
        if (!recycleItems.containsKey(id)) return false;
        recycleItems.remove(id);
        config.removeRecycleItem(id);
        return true;
    }

    public boolean setPrice(String id, double recyclePrice) {
        RecycleItem item = recycleItems.get(id);
        if (item == null) return false;
        item.setBaseRecyclePrice(recyclePrice);
        config.saveRecycleItem(id, item.getMaterial(), recyclePrice);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveRecycleData(item));
        return true;
    }
}
