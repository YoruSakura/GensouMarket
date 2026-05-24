package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.config.PricingConfigResolver;
import net.scarletphantasy.gensouMarket.config.RecyclePricingConfig;
import net.scarletphantasy.gensouMarket.economy.EconomySnapshotService;
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

import net.scarletphantasy.gensouMarket.config.ShopPricingConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
            if (configItem.isFixedLimited()) {
                int initial = config.getShopInitialStock(id, -1);
                if (configItem.getAvailableStock() < 0) {
                    configItem.setAvailableStock(initial >= 0 ? initial : 0);
                }
                configItem.setInitialStock(initial >= 0 ? initial : configItem.getAvailableStock());
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

    // ========== v1.1.1 统一价格计算入口 ==========

    /**
     * 构造当前时刻的出售价格上下文。
     * <p>
     * 集成模块 13（压力窗口 activeVolume）和模块 15（经济倍率）。
     */
    private PriceContext buildSellPriceContext(ShopItem shopItem, RecycleItem recycleSource) {
        long now = System.currentTimeMillis();

        // 经济倍率（模块 15）
        EconomySnapshotService economy = plugin.getEconomySnapshotService();
        double econRecycleMultiplier = economy != null ? economy.getRecycleMultiplier() : 1.0;
        double econShopMultiplier = economy != null ? economy.getShopMultiplier() : 1.0;

        // 回收压力窗口 activeVolume（模块 13）
        int activeVolume = 0;
        if (recycleSource != null) {
            RecycleManager recycleManager = plugin.getRecycleManager();
            PricingConfigResolver resolver = recycleManager.getPriceEngine().getConfigResolver();
            RecyclePricingConfig rcfg = resolver.resolveForRecycleItem(recycleSource.getId());
            activeVolume = recycleManager.getPressureWindow().getActiveVolume(recycleSource.getId(), rcfg, now);
        }

        // 库存信息
        int recycledStock = recycleSource != null ? recycleSource.getRecycledStock() : 0;
        int currentStock = shopItem.isFixedLimited() ? Math.max(shopItem.getAvailableStock(), 0) : 0;
        int referenceStock = 0;
        if (shopItem.isFixedLimited()) {
            PricingConfigResolver resolver = plugin.getRecycleManager().getPriceEngine().getConfigResolver();
            ShopPricingConfig shopConfig = resolver.resolveForShopItem(shopItem.getId());
            if (shopConfig.pricingReferenceStock() != null) {
                referenceStock = shopConfig.pricingReferenceStock();
            } else {
                referenceStock = Math.max(shopItem.getInitialStock(), 1);
            }
        }

        return new PriceContext(
                now,
                econRecycleMultiplier,
                econShopMultiplier,
                activeVolume,
                recycledStock,
                currentStock,
                referenceStock
        );
    }

    /**
     * 计算当前出售价格结果（包含中间值，供 GUI 和调试使用）。
     *
     * @param shopItem 商店物品
     * @return PriceResult 包含 unitPrice 和中间值
     */
    public PriceResult computeCurrentPriceResult(ShopItem shopItem) {
        RecycleItem recycleSource = resolveRecycleSource(shopItem);
        PriceContext ctx = buildSellPriceContext(shopItem, recycleSource);
        PriceEngine priceEngine = plugin.getRecycleManager().getPriceEngine();
        return priceEngine.calculateSellPrice(shopItem, recycleSource, ctx);
    }

    /**
     * 根据模式计算当前单价（v1.1.1 统一入口）。
     * <p>
     * 三类商品均通过 {@link PriceEngine#calculateSellPrice} 计算：
     * <ul>
     *   <li>fixed+unlimited：sellBaseValue × cycleMultiplier × economyShopMultiplier</li>
     *   <li>fixed+limited：+ 库存稀缺倍率</li>
     *   <li>recycled：+ 回流库存倍率 + 防套利地板</li>
     * </ul>
     */
    public double computeCurrentPrice(ShopItem shopItem) {
        PriceResult result = computeCurrentPriceResult(shopItem);
        double price = result.unitPrice();
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

    public void buyFromShop(Player player, String itemId, int amount) {
        buyFromShop(player, itemId, amount, null);
    }

    public void buyFromShop(Player player, String itemId, int amount, Runnable onComplete) {
        if (!config.isShopEnabled()) {
            MessageUtil.send(player, "&c服务器商店未启用！");
            if (onComplete != null) onComplete.run();
            return;
        }

        ShopItem shopItem = shopItems.get(itemId);
        if (shopItem == null) {
            MessageUtil.send(player, "&c未找到该商品！");
            if (onComplete != null) onComplete.run();
            return;
        }

        if (amount <= 0) amount = 1;

        // recycled 模式：必须有可用来源
        RecycleItem recycleSource = null;
        if (shopItem.isRecycled()) {
            recycleSource = resolveRecycleSource(shopItem);
            if (recycleSource == null) {
                MessageUtil.send(player, "&c该回流商品的回收来源未配置，无法购买！");
                if (onComplete != null) onComplete.run();
                return;
            }
        }

        // 先检查库存（本地快照只作为提前提示，不是最终购买凭证）
        int available = computeAvailableStock(shopItem);
        if (available <= 0) {
            MessageUtil.send(player, "&c该商品已缺货！");
            if (onComplete != null) onComplete.run();
            return;
        }
        if (available < amount) {
            MessageUtil.send(player, "&c库存不足，当前剩余 &e" + available + "&c 个！");
            if (onComplete != null) onComplete.run();
            return;
        }

        // v1.1.1 统一价格计算
        PriceResult result = computeCurrentPriceResult(shopItem);
        double pricePerUnit = Math.max(result.unitPrice(), 0.01);
        double totalCost = Math.round(pricePerUnit * amount * 100.0) / 100.0;

        if (!vault.has(player, totalCost)) {
            MessageUtil.send(player, "&c你没有足够的金币！需要: &e" + MessageUtil.formatMoney(totalCost));
            if (onComplete != null) onComplete.run();
            return;
        }

        // ---- v1.1.2 跨服 recycled 购买分支 ----
        if (shopItem.isRecycled() && plugin.isClusterEnabled() && recycleSource != null) {
            buyRecycledCluster(player, shopItem, recycleSource, amount, pricePerUnit, totalCost, onComplete);
            return;
        }

        // 非跨服或非 recycled：同步流程
        if (!vault.withdraw(player, totalCost)) {
            MessageUtil.send(player, "&c扣款失败，请稍后重试购买！");
            if (onComplete != null) onComplete.run();
            return;
        }

        ItemStack item = new ItemStack(shopItem.getMaterial(), amount);
        java.util.HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
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
            storage.saveShopData(shopItem);
        } else if (shopItem.isRecycled() && recycleSource != null) {
            // 非跨服 recycled——本地保存
            recycleSource.setRecycledStock(Math.max(recycleSource.getRecycledStock() - amount, 0));
            recycleSource.setLastUpdate(System.currentTimeMillis());
            storage.saveRecycleData(recycleSource);
            storage.saveShopData(shopItem);
        } else {
            // fixed+unlimited 只更新 totalBought
            storage.saveShopData(shopItem);
        }

        MessageUtil.send(player, Component.text("成功购买 ", NamedTextColor.GREEN)
                .append(Component.text(amount + "x ", NamedTextColor.YELLOW))
                .append(ItemNameUtil.getLocalizedName(shopItem.getMaterial()).color(NamedTextColor.YELLOW))
                .append(Component.text(" 花费: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(totalCost), NamedTextColor.YELLOW))
                .append(Component.text(" (单价: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(pricePerUnit), NamedTextColor.YELLOW))
                .append(Component.text(")", NamedTextColor.GREEN)));

        if (onComplete != null) onComplete.run();
    }

    /**
     * v1.1.2 跨服 recycled 商品购买流程。
     * <p>
     * 使用 MySQL 条件扣减避免超卖，成功后回主线程交付物品。
     */
    private void buyRecycledCluster(Player player, ShopItem shopItem, RecycleItem recycleSource,
                                     int amount, double pricePerUnit, double totalCost, Runnable onComplete) {
        final long now = System.currentTimeMillis();
        final UUID playerUuid = player.getUniqueId();

        // 异步执行 MySQL 条件扣减
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean consumed = storage.consumeRecycleStockIfEnough(recycleSource, amount, now);
            // 回主线程处理结果
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!consumed) {
                    MessageUtil.send(player, "&c库存不足或已被其他服务器抢购，请稍后重试！");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                // MySQL 扣减成功，再次确认玩家状态
                if (!player.isOnline()) {
                    // 玩家离线，异步补偿库存
                    compensateRecycleStockAsync(recycleSource, amount, playerUuid,
                            "shop-buy", "玩家已离线");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                if (!vault.has(player, totalCost)) {
                    // 余额不足，异步补偿库存
                    MessageUtil.send(player, "&c余额不足，购买已取消！");
                    compensateRecycleStockAsync(recycleSource, amount, playerUuid,
                            "shop-buy", "余额不足");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                if (!vault.withdraw(player, totalCost)) {
                    // 扣款失败，异步补偿库存
                    MessageUtil.send(player, "&c扣款失败，购买已取消！");
                    compensateRecycleStockAsync(recycleSource, amount, playerUuid,
                            "shop-buy", "Vault扣款失败");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                // 扣款成功：发放物品
                ItemStack item = new ItemStack(shopItem.getMaterial(), amount);
                java.util.HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                if (!overflow.isEmpty()) {
                    for (ItemStack drop : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                    MessageUtil.send(player, "&e背包已满，物品已掉落在你脚下！");
                }

                // 更新本地内存
                recycleSource.setRecycledStock(Math.max(recycleSource.getRecycledStock() - amount, 0));
                recycleSource.setLastUpdate(now);
                shopItem.addBought(amount);
                storage.saveShopData(shopItem);

                // 跨服库存同步：广播负增量给其他子服
                plugin.getClusterEventPublisher().publishRecycleStockSync(
                        recycleSource.getId(), -amount, 0, now, "shop-buy");

                MessageUtil.send(player, Component.text("成功购买 ", NamedTextColor.GREEN)
                        .append(Component.text(amount + "x ", NamedTextColor.YELLOW))
                        .append(ItemNameUtil.getLocalizedName(shopItem.getMaterial()).color(NamedTextColor.YELLOW))
                        .append(Component.text(" 花费: ", NamedTextColor.GREEN))
                        .append(Component.text(MessageUtil.formatMoney(totalCost), NamedTextColor.YELLOW))
                        .append(Component.text(" (单价: ", NamedTextColor.GREEN))
                        .append(Component.text(MessageUtil.formatMoney(pricePerUnit), NamedTextColor.YELLOW))
                        .append(Component.text(")", NamedTextColor.GREEN)));

                if (onComplete != null) onComplete.run();
            });
        });
    }

    /**
     * v1.1.2 异步补偿回流库存。检查返回值，失败时写 SEVERE 日志。
     */
    private void compensateRecycleStockAsync(RecycleItem recycleSource, int amount,
                                              UUID playerUuid, String action, String reason) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = storage.addRecycleStockDelta(recycleSource, amount, 0, System.currentTimeMillis());
            if (!ok) {
                plugin.getLogger().severe(
                        "[Shop] 回流库存补偿失败！item=" + recycleSource.getId()
                        + " amount=" + amount
                        + " player=" + playerUuid
                        + " action=" + action
                        + " reason=" + reason);
            }
        });
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
        storage.saveShopData(item);
        return true;
    }
}
