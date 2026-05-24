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
        if (plugin.isClusterEnabled()) {
            storage.saveAllRecycleDefinitionData(recycleItems);
        } else {
            storage.saveAllRecycleData(recycleItems);
        }
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

        // ---- v1.1.2 跨服/非跨服分支 ----
        if (plugin.isClusterEnabled()) {
            // 跨服模式：先写 MySQL 原子增量
            final int finalAmount = amount;
            boolean dbSuccess = storage.addRecycleStockDelta(recycleItem, finalAmount, finalAmount, now);
            if (!dbSuccess) {
                // MySQL 写入失败：补偿 — 扣回金额并返还物品
                plugin.getLogger().severe("[Recycle] MySQL 库存增量写入失败，尝试补偿！" +
                        " player=" + player.getUniqueId() + " item=" + recycleItem.getId() +
                        " amount=" + finalAmount + " earning=" + totalEarning);
                if (!vault.withdraw(player, totalEarning)) {
                    plugin.getLogger().severe("[Recycle] 补偿扣款失败！player=" + player.getUniqueId() +
                            " amount=" + totalEarning);
                }
                ItemStack returnItem = new ItemStack(material, finalAmount);
                java.util.HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(returnItem);
                if (!overflow.isEmpty()) {
                    for (ItemStack drop : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                }
                MessageUtil.send(player, "&c回收操作失败，物品和金额已返还！");
                return false;
            }

            // MySQL 成功：合并本地内存
            recycleItem.addRecycled(finalAmount);
            recycleItem.addRecycledStock(finalAmount);
            recycleItem.setLastUpdate(now);

            // 写入压力窗口（库存增量成功后才写入）
            int newActiveVolume = pressureWindow.recordRecycle(recycleItem.getId(), pricingConfig, finalAmount, now);

            // 跨服压力同步
            long bucketMillis = pricingConfig.bucketSeconds() * 1000L;
            if (bucketMillis <= 0) bucketMillis = 60_000L;
            long bucketStart = (now / bucketMillis) * bucketMillis;
            plugin.getClusterEventPublisher().publishPressureSync(
                    recycleItem.getId(), bucketStart, finalAmount, newActiveVolume, now);

            // 跨服库存同步
            plugin.getClusterEventPublisher().publishRecycleStockSync(
                    recycleItem.getId(), finalAmount, finalAmount, now, "recycle");
        } else {
            // 非跨服模式：按原本本地保存路径处理

            // 7. 写入压力窗口（入账和移除成功后才写入）
            pressureWindow.recordRecycle(recycleItem.getId(), pricingConfig, amount, now);

            // 8. 增加 totalRecycled 和 recycledStock
            recycleItem.addRecycled(amount);
            recycleItem.addRecycledStock(amount);
            recycleItem.setLastUpdate(now);

            // 9. 立即保存回收数据
            storage.saveRecycleData(recycleItem);
        }

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

    /**
     * v1.1.2 接收远程回流库存增量并合并到本地内存。
     * <p>
     * 只修改本地内存，不写 MySQL，不自动创建 RecycleItem。
     * 本方法必须在主线程调用。
     *
     * @param itemId              物品 ID
     * @param recycledStockDelta  回流库存增量（正=回收，负=购买）
     * @param totalRecycledDelta  累计回收量增量（正=回收，0=购买）
     * @param eventTime           事件发生时间毫秒
     */
    public void applyRemoteRecycleStockDelta(String itemId,
                                              int recycledStockDelta,
                                              int totalRecycledDelta,
                                              long eventTime) {
        RecycleItem item = recycleItems.get(itemId);
        if (item == null) {
            // 本地不存在该物品，忽略事件，不自动创建
            plugin.getLogger().fine("[RecycleStockSync] 本地不存在 itemId=" + itemId + "，忽略远程库存事件");
            return;
        }

        // 合并规则：recycledStock = max(0, recycledStock + delta)
        int newStock = Math.max(0, item.getRecycledStock() + recycledStockDelta);
        item.setRecycledStock(newStock);

        // 合并规则：totalRecycled = max(0, totalRecycled + delta)
        int newTotal = Math.max(0, item.getTotalRecycled() + totalRecycledDelta);
        item.setTotalRecycled(newTotal);

        // 合并规则：lastUpdate = max(lastUpdate, eventTime)
        if (eventTime > item.getLastUpdate()) {
            item.setLastUpdate(eventTime);
        }
    }

    /**
     * v1.1.2 定期从 MySQL 校准本地回流库存运行时字段。
     * <p>
     * 异步从 MySQL 读取 recycle_data，回主线程合并到本地已配置的 RecycleItem。
     * 只覆盖已配置的本地 itemId，不自动创建配置外物品。
     * 校准使用 MySQL 绝对值覆盖本地运行时字段。
     */
    public void scheduleRecycleRuntimeCalibration() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, RecycleItem> dbData = storage.loadRecycleData();
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Map.Entry<String, RecycleItem> entry : recycleItems.entrySet()) {
                    String id = entry.getKey();
                    RecycleItem local = entry.getValue();
                    RecycleItem db = dbData.get(id);
                    if (db == null) continue;
                    // 用 MySQL 绝对值覆盖运行时字段
                    local.setRecycledStock(db.getRecycledStock());
                    local.setTotalRecycled(db.getTotalRecycled());
                    local.setLastUpdate(db.getLastUpdate());
                }
                plugin.getLogger().fine("[Cluster] 回流库存定期校准完成");
            });
        });
    }

    public boolean addItem(String id, Material material, double recyclePrice) {
        if (recycleItems.containsKey(id)) return false;
        RecycleItem item = new RecycleItem(id, material, recyclePrice);
        recycleItems.put(id, item);
        config.saveRecycleItem(id, material, recyclePrice);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (plugin.isClusterEnabled()) {
                storage.saveRecycleDefinitionData(item);
            } else {
                storage.saveRecycleData(item);
            }
        });
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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (plugin.isClusterEnabled()) {
                storage.saveRecycleDefinitionData(item);
            } else {
                storage.saveRecycleData(item);
            }
        });
        return true;
    }
}
