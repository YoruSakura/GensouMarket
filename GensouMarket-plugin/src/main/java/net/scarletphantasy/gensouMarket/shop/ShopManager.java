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
        // 初始化缺失库存；UPSERT 保留现有权威值，包括另一台服务器刚扣减的库存。
        storage.saveAllShopData(shopItems);
    }

    public void saveAllData() {
        storage.saveAllShopData(shopItems);
    }

    public void refreshFixedStocks(Runnable onComplete) {
        Map<String, ShopItem> snapshot = new LinkedHashMap<>(shopItems);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Integer> stocks = new LinkedHashMap<>();
            snapshot.forEach((id, item) -> {
                if (item.isFixedLimited()) {
                    Integer stock = storage.getShopStock(id);
                    if (stock != null) stocks.put(id, stock);
                }
            });
            Bukkit.getScheduler().runTask(plugin, () -> {
                stocks.forEach((id, stock) -> {
                    if (shopItems.get(id) == snapshot.get(id)) shopItems.get(id).setAvailableStock(stock);
                });
                onComplete.run();
            });
        });
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

    public PriceResult computePurchasePriceResult(ShopItem shopItem, int amount) {
        RecycleItem recycleSource = resolveRecycleSource(shopItem);
        PriceContext ctx = buildSellPriceContext(shopItem, recycleSource);
        return plugin.getRecycleManager().getPriceEngine()
                .calculateBatchSellPrice(shopItem, recycleSource, ctx, amount);
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

    public ItemStack createSaleItem(ShopItem item, int amount) {
        if (item.isRecycled()) {
            RecycleItem source = resolveRecycleSource(item);
            if (source == null) throw new IllegalStateException("回流商品缺少回收来源: " + item.getId());
            return source.createItemStack(amount);
        }
        return item.createItemStack(amount);
    }

    public void buyFromShop(Player player, String itemId, int amount) {
        buyFromShop(player, itemId, amount, null);
    }

    public void buyFromShop(Player player, String itemId, int amount, Runnable onComplete) {
        if (!MessageUtil.requirePermission(player, "gensoumarket.shop")) {
            if (onComplete != null) onComplete.run();
            return;
        }
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
        if (shopItem.isFixedLimited()) {
            buyFixedLimited(player, shopItem, amount, onComplete);
            return;
        }

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
        PriceResult result = computePurchasePriceResult(shopItem, amount);
        double pricePerUnit = result.averagePrice();
        double totalCost = result.totalPrice();

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

        // 发货使用本次校验过的定义，避免异步操作期间重载/移除来源后丢失物品数据。
        ItemStack item = shopItem.isRecycled() ? recycleSource.createItemStack(amount) : shopItem.createItemStack(amount);
        // 非跨服或非 recycled：同步流程
        if (!vault.withdraw(player, totalCost)) {
            MessageUtil.send(player, "&c扣款失败，请稍后重试购买！");
            if (onComplete != null) onComplete.run();
            return;
        }

        java.util.HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            MessageUtil.send(player, "&e背包已满，物品已掉落在你脚下！");
        }

        // 扣减库存（按模式）
        shopItem.addBought(amount);
        if (shopItem.isRecycled() && recycleSource != null) {
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
                .append(Component.text(amount > 1 ? " (平均单价: " : " (单价: ", NamedTextColor.GREEN))
                .append(Component.text(MessageUtil.formatMoney(pricePerUnit), NamedTextColor.YELLOW))
                .append(Component.text(")", NamedTextColor.GREEN)));

        if (onComplete != null) onComplete.run();
    }

    /** fixed+limited 使用共享数据库扣减；本地库存仅供展示和价格快照。 */
    private void buyFixedLimited(Player player, ShopItem item, int amount, Runnable onComplete) {
        double quotedCost = item.getAvailableStock() >= amount
                ? computePurchasePriceResult(item, amount).totalPrice() : 0;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Integer stock = storage.getShopStock(item.getId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (stock == null || stock < amount) {
                    if (stock != null) item.setAvailableStock(stock);
                    MessageUtil.send(player, "&c库存不足或读取库存失败，请刷新后重试！");
                    if (onComplete != null) onComplete.run();
                    return;
                }
                item.setAvailableStock(stock);
                double cost = computePurchasePriceResult(item, amount).totalPrice();
                if (quotedCost > 0 && Math.abs(cost - quotedCost) / quotedCost > config.getPriceChangeTolerance()) {
                    MessageUtil.send(player, "&e库存价格已变化，请刷新后重新确认购买。");
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (!Double.isFinite(cost) || cost <= 0 || !vault.has(player, cost)) {
                    MessageUtil.send(player, "&c余额不足或价格无效，购买已取消！");
                    if (onComplete != null) onComplete.run();
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean consumed = storage.consumeShopStockIfEnough(item.getId(), amount);
                    Integer remaining = storage.getShopStock(item.getId());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (remaining != null) item.setAvailableStock(remaining);
                        if (!consumed) {
                            MessageUtil.send(player, "&c库存不足或已被其他玩家抢购，请重试！");
                            if (onComplete != null) onComplete.run();
                            return;
                        }
                        if (!player.isOnline() || !vault.withdraw(player, cost)) {
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                if (!storage.addShopStockDelta(item.getId(), amount)) {
                                    plugin.getLogger().severe("[Shop] 固定库存补偿失败 item=" + item.getId()
                                            + " amount=" + amount + " player=" + player.getUniqueId());
                                }
                                Integer restored = storage.getShopStock(item.getId());
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (restored != null) item.setAvailableStock(restored);
                                    if (onComplete != null) onComplete.run();
                                });
                            });
                            if (player.isOnline()) MessageUtil.send(player, "&c扣款失败，购买已取消！");
                            return;
                        }
                        MessageUtil.giveItem(player, item.createItemStack(amount));
                        item.addBought(amount);
                        item.setLastUpdate(System.currentTimeMillis());
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.saveShopData(item));
                        MessageUtil.send(player, "&a成功购买 &e" + amount + "x " + item.getMaterial().name()
                                + "&a，花费: &e" + MessageUtil.formatMoney(cost));
                        if (onComplete != null) onComplete.run();
                    });
                });
            });
        });
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
                ItemStack item = recycleSource.createItemStack(amount);
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
                        .append(Component.text(amount > 1 ? " (平均单价: " : " (单价: ", NamedTextColor.GREEN))
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
        return addItem(id, material, null, buyPrice, null);
    }

    public boolean addItem(String id, ItemStack template, double buyPrice, String group) {
        if (template == null || template.getType().isAir()) return false;
        if (shopItems.values().stream().anyMatch(item -> item.matches(template))) return false;
        return addItem(id, template.getType(), template, buyPrice, group);
    }

    private boolean addItem(String id, Material material, ItemStack template, double buyPrice, String group) {
        if (!net.scarletphantasy.gensouMarket.config.PricingGroupStore.validId(id)
                || !Double.isFinite(buyPrice) || buyPrice <= 0) return false;
        if (shopItems.containsKey(id) || config.hasShopItem(id)) return false;
        if (!config.saveShopItem(id, material, template, buyPrice, group)) return false;
        ShopItem item = new ShopItem(id, material, buyPrice);
        if (template != null) item.setItemTemplate(template);
        // /gmarket shop add 仅创建 fixed+unlimited 商品（task-05）
        item.setMode(ShopItem.Mode.FIXED);
        item.setStockMode(ShopItem.StockMode.UNLIMITED);
        shopItems.put(id, item);
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
        if (!Double.isFinite(buyPrice) || buyPrice <= 0 || !config.updateShopBuyPrice(id, buyPrice)) return false;
        item.setBaseBuyPrice(buyPrice);
        // 只改 buy-price，保留 mode / stock-mode / initial-stock 等原有配置
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
        if (!storage.setShopStock(id, amount)) return false;
        item.setAvailableStock(amount);
        item.setLastUpdate(System.currentTimeMillis());
        return true;
    }
}
