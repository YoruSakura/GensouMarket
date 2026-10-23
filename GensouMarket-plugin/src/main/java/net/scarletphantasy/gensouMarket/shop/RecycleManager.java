package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.config.PricingConfigResolver;
import net.scarletphantasy.gensouMarket.config.RecyclePricingConfig;
import net.scarletphantasy.gensouMarket.economy.EconomySnapshotService;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import net.scarletphantasy.gensouMarket.util.ItemSerializer;
import net.scarletphantasy.gensouMarket.util.ItemNameUtil;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
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
    private final java.util.Set<java.util.UUID> pendingClusterRecycles = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
     *   <li>按运行模式结算：单服本地保存；跨服先收物品、异步写权威库、成功后付款或写邮箱</li>
     *   <li>写入压力窗口并增加 totalRecycled/recycledStock</li>
     * </ol>
     * 如果结算失败，不写入压力。
     */
    public boolean recycleItem(Player player, RecycleItem recycleItem, int amount) {
        if (!MessageUtil.requirePermission(player, "gensoumarket.recycle")) return false;
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

        // ---- v1.1.2 跨服/非跨服分支 ----
        if (plugin.isClusterEnabled()) {
            // 跨服模式：先收走玩家物品作为凭据，再异步写 MySQL；写库成功后才付款。
            final int finalAmount = amount;
            final java.util.UUID playerUuid = player.getUniqueId();
            if (!pendingClusterRecycles.add(playerUuid)) {
                MessageUtil.send(player, "&c已有回收操作处理中，请稍后再试。");
                return false;
            }

            if (countMaterial(player, material) < finalAmount) {
                pendingClusterRecycles.remove(playerUuid);
                MessageUtil.send(player, "&c回收操作已取消：物品数量发生变化，请重新打开回收站。");
                return false;
            }

            List<ItemStack> removedItems = removeMaterial(player, material, finalAmount);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean dbSuccess = storage.addRecycleStockDelta(recycleItem, finalAmount, finalAmount, now);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean rewardHandled = false;
                    try {
                        if (!dbSuccess) {
                            returnRecycleItems(player, playerUuid, removedItems,
                                    "&c回收操作失败，物品已退回，请稍后重试！");
                            return;
                        }

                        deliverRecycleReward(player, playerUuid, totalEarning, recycleItem, finalAmount, result);
                        rewardHandled = true;

                        // MySQL 和玩家资产均成功：合并本地内存
                        recycleItem.addRecycled(finalAmount);
                        recycleItem.addRecycledStock(finalAmount);
                        recycleItem.setLastUpdate(now);

                        // 写入压力窗口
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
                    } catch (RuntimeException ex) {
                        if (dbSuccess && !rewardHandled) {
                            saveRecycleMoneyMailAsync(playerUuid, totalEarning,
                                    "回收 " + finalAmount + "x " + recycleItem.getMaterial().name() + " 的金币");
                        }
                        throw ex;
                    } finally {
                        pendingClusterRecycles.remove(playerUuid);
                    }
                });
            });
            return true;
        } else {
            // 非跨服模式：按原本本地保存路径处理

            // 5. Vault 入账（失败则不写压力）
            if (!vault.deposit(player, totalEarning)) {
                MessageUtil.send(player, "&c入账失败，请稍后重试回收！");
                return false;
            }

            // 6. 移除玩家物品
            removeMaterial(player, material, amount);

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
        sendRecycleSuccess(player, recycleItem, amount, totalEarning, result);
        return true;
    }

    private void sendRecycleSuccess(Player player, RecycleItem recycleItem, int amount,
                                    double totalEarning, PriceResult result) {
        MessageUtil.send(player, Component.text("成功回收 ", NamedTextColor.GREEN)
                .append(Component.text(amount + "x ", NamedTextColor.YELLOW))
                .append(ItemNameUtil.getLocalizedName(recycleItem.getMaterial()).color(NamedTextColor.YELLOW))
                .append(Component.text(" 获得: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(totalEarning), NamedTextColor.YELLOW))
                .append(Component.text(" (均价: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(result.averagePrice()), NamedTextColor.YELLOW))
                .append(Component.text(")", NamedTextColor.GREEN)));
    }

    private void deliverRecycleReward(Player player, java.util.UUID playerUuid, double totalEarning,
                                      RecycleItem recycleItem, int amount, PriceResult result) {
        if (player.isOnline()) {
            if (vault.deposit(player, totalEarning)) {
                sendRecycleSuccess(player, recycleItem, amount, totalEarning, result);
                return;
            }
            saveRecycleMoneyMailAsync(playerUuid, totalEarning,
                    "回收 " + amount + "x " + recycleItem.getMaterial().name() + " 的金币");
            MessageUtil.send(player, "&e回收成功，但金币入账失败，已转入邮箱，使用 &a/gmarket collect &e领取");
            return;
        }

        String message = "回收 " + amount + "x " + recycleItem.getMaterial().name() + " 的金币";
        if (plugin.isClusterEnabled()) {
            plugin.getClusterEventPublisher().requestRemoteDeposit(
                    playerUuid, totalEarning,
                    message + ": " + MessageUtil.formatMoney(totalEarning),
                    ignored -> saveRecycleMoneyMailAsync(playerUuid, totalEarning, message));
        } else {
            saveRecycleMoneyMailAsync(playerUuid, totalEarning, message);
        }
    }

    private void returnRecycleItems(Player player, java.util.UUID playerUuid, List<ItemStack> removedItems,
                                    String onlineMessage) {
        String message = "回收操作失败，物品已退回";
        for (ItemStack original : removedItems) {
            ItemStack returnItem = original.clone();
            if (player.isOnline()) {
                MessageUtil.giveItem(player, returnItem);
            } else if (plugin.isClusterEnabled()) {
                plugin.getClusterEventPublisher().requestRemoteGiveItem(
                        playerUuid, ItemSerializer.serialize(returnItem), message,
                        ignored -> saveRecycleItemMailAsync(playerUuid, returnItem, message));
            } else {
                saveRecycleItemMailAsync(playerUuid, returnItem, message);
            }
        }
        if (player.isOnline()) MessageUtil.send(player, onlineMessage);
    }

    private void saveRecycleMoneyMailAsync(java.util.UUID playerUuid, double amount, String message) {
        MailEntry mail = new MailEntry();
        mail.setPlayerUuid(playerUuid);
        mail.setMoney(amount);
        mail.setMessage(message);
        saveRecycleMailAsync(mail, true, false);
    }

    private void saveRecycleItemMailAsync(java.util.UUID playerUuid, ItemStack itemStack, String message) {
        MailEntry mail = new MailEntry();
        mail.setPlayerUuid(playerUuid);
        mail.setItemStack(itemStack);
        mail.setItemData(ItemSerializer.serialize(itemStack));
        mail.setMessage(message);
        saveRecycleMailAsync(mail, false, true);
    }

    private void saveRecycleMailAsync(MailEntry mail, boolean hasMoney, boolean hasItem) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            storage.saveMail(mail);
            if (plugin.isClusterEnabled()) {
                plugin.getClusterEventPublisher().publishMailCreated(
                        mail.getPlayerUuid(), hasMoney, hasItem, mail.getMessage());
            }
        });
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

    private List<ItemStack> removeMaterial(Player player, Material material, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        List<ItemStack> removed = new ArrayList<>();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == material) {
                int taken = Math.min(item.getAmount(), remaining);
                ItemStack original = item.clone();
                original.setAmount(taken);
                removed.add(original);
                remaining -= taken;
                if (taken == item.getAmount()) {
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - taken);
                    player.getInventory().setItem(i, item);
                }
            }
        }
        return removed;
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
