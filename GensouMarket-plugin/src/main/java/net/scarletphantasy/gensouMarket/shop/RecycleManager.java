package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.config.PricingConfigResolver;
import net.scarletphantasy.gensouMarket.config.RecyclePricingConfig;
import net.scarletphantasy.gensouMarket.economy.EconomySnapshotService;
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
    /** v1.1.1 新价格引擎 */
    private final PriceEngine priceEngine;
    private final PricingConfigResolver configResolver;
    /** v1.1.1 压力窗口管理器 */
    private final PressureWindowManager pressureWindow;
    private final Map<String, RecycleItem> recycleItems = new LinkedHashMap<>();

    public RecycleManager(GensouMarket plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        this.config = plugin.getConfigManager();
        this.vault = plugin.getVaultHook();
        this.configResolver = new PricingConfigResolver(config, plugin.getLogger());
        this.priceEngine = new PriceEngine(configResolver);
        this.pressureWindow = new PressureWindowManager(plugin, storage, plugin.getLogger());
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
                configItem.setLastUpdate(dbItem.getLastUpdate());
                // 回流库存必须恢复，否则 recycled 商品在重启/reload 后会误显示为缺货
                configItem.setRecycledStock(dbItem.getRecycledStock());
            }

            recycleItems.put(id, configItem);
        }

        // v1.1.1 冷启动：加载压力桶并清理过期
        Map<String, RecyclePricingConfig> configMap = new java.util.HashMap<>();
        for (String id : recycleItems.keySet()) {
            configMap.put(id, configResolver.resolveForRecycleItem(id));
        }
        pressureWindow.loadAll(configMap);
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

    /**
     * 执行回收操作（v1.1.1 重写）。
     * <p>
     * 流程：
     * <ol>
     *   <li>校验回收模块启用</li>
     *   <li>校验物品和数量</li>
     *   <li>读取压力状态并清理过期 bucket</li>
     *   <li>用 PriceEngine 计算本次 totalEarning</li>
     *   <li>Vault 入账</li>
     *   <li>移除玩家物品</li>
     *   <li>写入压力窗口</li>
     *   <li>增加 totalRecycled 和 recycledStock</li>
     *   <li>异步保存回收数据和压力数据</li>
     * </ol>
     * 如果入账或物品移除失败，不写入压力。
     */
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

        // ---- v1.1.1 压力窗口 + PriceEngine 结算 ----
        long now = System.currentTimeMillis();
        RecyclePricingConfig pricingConfig = configResolver.resolveForRecycleItem(recycleItem.getId());

        // 3. 读取当前 activeVolume（清理过期桶后的窗口内回收量）
        int activeVolume = pressureWindow.getActiveVolume(recycleItem.getId(), pricingConfig, now);

        // 获取经济倍率（模块 15）
        EconomySnapshotService economy = plugin.getEconomySnapshotService();
        double econRecycleMultiplier = economy != null ? economy.getRecycleMultiplier() : 1.0;

        // 4. 构造 PriceContext 并用 PriceEngine 计算批量总额
        PriceContext ctx = new PriceContext(
                now,
                econRecycleMultiplier,
                1.0,                    // shopMultiplier 回收用不到
                activeVolume,
                recycleItem.getRecycledStock(),
                0, 0                    // 库存相关字段回收用不到
        );
        PriceResult result = priceEngine.calculateBatchRecyclePrice(recycleItem, pricingConfig, ctx, amount);
        double totalEarning = result.totalPrice();

        // 5. Vault 入账（失败则不写压力）
        if (!vault.deposit(player, totalEarning)) {
            MessageUtil.send(player, "&c入账失败，请稍后重试回收！");
            return false;
        }

        // 6. 移除玩家物品
        removeMaterial(player, material, amount);

        // 7. 写入压力窗口（入账和移除成功后才写入）
        int newActiveVolume = pressureWindow.recordRecycle(recycleItem.getId(), pricingConfig, amount, now);

        // 7.5. 跨服压力同步（Velocity）
        if (plugin.isClusterEnabled()) {
            long bucketMillis = pricingConfig.bucketSeconds() * 1000L;
            if (bucketMillis <= 0) bucketMillis = 60_000L;
            long bucketStart = (now / bucketMillis) * bucketMillis;
            plugin.getClusterEventPublisher().publishPressureSync(
                    recycleItem.getId(), bucketStart, amount, newActiveVolume, now);
        }

        // 8. 增加 totalRecycled 和 recycledStock
        recycleItem.addRecycled(amount);
        recycleItem.addRecycledStock(amount);
        recycleItem.setLastUpdate(now);

        // 9. 立即保存回收数据。回流库存是关键库存状态，必须在操作成功返回前落库，
        // 避免玩家回收后立刻 reload / restart 时库存回退。
        storage.saveRecycleData(recycleItem);

        // 发送消息
        MessageUtil.send(player, Component.text("成功回收 ", NamedTextColor.GREEN)
                .append(Component.text(amount + "x ", NamedTextColor.YELLOW))
                .append(ItemNameUtil.getLocalizedName(recycleItem.getMaterial()).color(NamedTextColor.YELLOW))
                .append(Component.text(" 获得: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(totalEarning), NamedTextColor.YELLOW))
                .append(Component.text(" (均价: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(result.averagePrice()), NamedTextColor.YELLOW))
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

    /**
     * 获取 v1.1.1 新价格引擎。
     */
    public PriceEngine getPriceEngine() {
        return priceEngine;
    }

    /**
     * 获取 v1.1.1 压力窗口管理器。
     */
    public PressureWindowManager getPressureWindow() {
        return pressureWindow;
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
