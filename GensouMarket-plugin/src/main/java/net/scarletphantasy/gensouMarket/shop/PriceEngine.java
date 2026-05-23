package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.config.PricingConfigResolver;
import net.scarletphantasy.gensouMarket.config.RecyclePricingConfig;
import net.scarletphantasy.gensouMarket.config.ShopPricingConfig;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;

/**
 * v1.1.1 统一价格计算引擎。
 * <p>
 * 所有价格计算集中在此类，不再散落在 RecycleItem / ShopManager / RecycleGui。
 * 所有方法均为纯函数（输入相同则输出相同），不修改任何模型状态，不访问数据库、Vault、背包或 GUI。
 * 所有入口都接受固定 {@code nowMillis}，方便测试和跨服一致性。
 */
public class PriceEngine {

    /** 用于 itemPhase 派生的质数，保证不同物品相位分散 */
    private static final int PHASE_PRIME = 31;

    private final PricingConfigResolver configResolver;

    public PriceEngine(PricingConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    /**
     * 获取配置解析器（供外部模块构造 PriceContext 时使用）。
     */
    public PricingConfigResolver getConfigResolver() {
        return configResolver;
    }

    // ========== 回收价格 ==========

    /**
     * 计算回收单价。
     *
     * @param item    回收物品（提供 baseRecyclePrice 和 itemId）
     * @param config  已解析的回收定价配置（物品级 > 模块级）
     * @param ctx     运行时上下文（时间、经济倍率、活跃量等）
     * @return 包含最终单价和中间值的 PriceResult
     */
    public PriceResult calculateRecyclePrice(RecycleItem item, RecyclePricingConfig config, PriceContext ctx) {
        double baseValue = item.getBaseRecyclePrice();
        double minPrice = config.effectiveMinPrice(baseValue);
        double maxPrice = config.effectiveMaxPrice(baseValue);

        if (!config.dynamicEnabled()) {
            double price = roundPrice(clamp(baseValue * ctx.economyRecycleMultiplier(), minPrice, maxPrice));
            return new PriceResult(price, price, price, baseValue, 1.0, 0.0, ctx.economyRecycleMultiplier(), 1.0, false);
        }

        // 周期波动
        double cycleMultiplier = computeCycleMultiplier(item.getId(), ctx.nowMillis(),
                config.cyclePeriodHours(), config.cycleAmplitude());
        double cycleValue = baseValue * cycleMultiplier;

        // smoothstep 压力
        double pressureRatio = computePressureRatio(ctx.activeVolume(), config.minPriceVolume());

        // 压力价格
        double pressurePrice = cycleValue - (cycleValue - minPrice) * pressureRatio;

        // 经济倍率
        double rawPrice = pressurePrice * ctx.economyRecycleMultiplier();

        // clamp + 精度
        double finalPrice = roundPrice(clamp(rawPrice, minPrice, maxPrice));

        return new PriceResult(finalPrice, finalPrice, finalPrice, baseValue,
                cycleMultiplier, pressureRatio, ctx.economyRecycleMultiplier(), 1.0, false);
    }

    /**
     * 使用已解析配置的快捷方法。
     */
    public PriceResult calculateRecyclePrice(RecycleItem item, PriceContext ctx) {
        RecyclePricingConfig config = configResolver.resolveForRecycleItem(item.getId());
        return calculateRecyclePrice(item, config, ctx);
    }

    /**
     * 计算批量回收总额。
     * <p>
     * 必须等价逐个回收：{@code price(i) = priceAt(activeVolume + i)}。
     * 总额先求和，再对总额保留两位小数。
     *
     * @param item    回收物品
     * @param config  已解析的回收定价配置
     * @param ctx     运行时上下文（activeVolume 为批量起始点）
     * @param amount  回收数量（必须 >= 1）
     * @return 包含 totalPrice、averagePrice 和首个单价作为 unitPrice 的 PriceResult
     */
    public PriceResult calculateBatchRecyclePrice(RecycleItem item, RecyclePricingConfig config,
                                                   PriceContext ctx, int amount) {
        if (amount <= 0) amount = 1;

        double baseValue = item.getBaseRecyclePrice();
        double minPrice = config.effectiveMinPrice(baseValue);
        double maxPrice = config.effectiveMaxPrice(baseValue);

        // 周期倍率（批量内时间不变，所有单位共享同一周期值）
        double cycleMultiplier = config.dynamicEnabled()
                ? computeCycleMultiplier(item.getId(), ctx.nowMillis(), config.cyclePeriodHours(), config.cycleAmplitude())
                : 1.0;
        double cycleValue = baseValue * cycleMultiplier;

        double totalPrice = 0.0;
        double firstUnitPrice = 0.0;
        double lastPressureRatio = 0.0;

        for (int i = 0; i < amount; i++) {
            double unitPrice;
            if (!config.dynamicEnabled()) {
                unitPrice = clamp(baseValue * ctx.economyRecycleMultiplier(), minPrice, maxPrice);
                lastPressureRatio = 0.0;
            } else {
                double pr = computePressureRatio(ctx.activeVolume() + i, config.minPriceVolume());
                double pressurePrice = cycleValue - (cycleValue - minPrice) * pr;
                double rawPrice = pressurePrice * ctx.economyRecycleMultiplier();
                unitPrice = clamp(rawPrice, minPrice, maxPrice);
                lastPressureRatio = pr;
            }
            if (i == 0) firstUnitPrice = roundPrice(unitPrice);
            totalPrice += unitPrice;
        }

        // 总额保留两位小数
        totalPrice = roundPrice(totalPrice);
        double averagePrice = roundPrice(totalPrice / amount);

        return new PriceResult(firstUnitPrice, totalPrice, averagePrice, baseValue,
                cycleMultiplier, lastPressureRatio, ctx.economyRecycleMultiplier(), 1.0, false);
    }

    /**
     * 使用已解析配置的快捷方法。
     */
    public PriceResult calculateBatchRecyclePrice(RecycleItem item, PriceContext ctx, int amount) {
        RecyclePricingConfig config = configResolver.resolveForRecycleItem(item.getId());
        return calculateBatchRecyclePrice(item, config, ctx, amount);
    }

    // ========== 出售价格 ==========

    /**
     * 计算服务器商店出售价格。
     * <p>
     * 出售标准价值优先级：{@code sell-value > recycle-source × sell-multiplier > buy-price}。
     *
     * @param shopItem      商店物品
     * @param recycleSource 关联的回收物品（recycled 模式必须提供；fixed 模式可为 null）
     * @param recycleConfig 回收定价配置（recycled 模式需要用于计算回收价；fixed 模式可为 null）
     * @param shopConfig    已解析的商店定价配置
     * @param ctx           运行时上下文
     * @return 包含最终单价和中间值的 PriceResult
     */
    public PriceResult calculateSellPrice(ShopItem shopItem, RecycleItem recycleSource,
                                           RecyclePricingConfig recycleConfig, ShopPricingConfig shopConfig,
                                           PriceContext ctx) {
        // ---- 出售标准价值 ----
        double sellBaseValue = resolveSellBaseValue(shopItem, recycleSource, recycleConfig, ctx);

        // ---- 模式分发 ----
        if (shopItem.isFixedUnlimited()) {
            return calculateFixedUnlimitedSellPrice(shopItem, shopConfig, sellBaseValue, ctx);
        } else if (shopItem.isFixedLimited()) {
            return calculateFixedLimitedSellPrice(shopItem, shopConfig, sellBaseValue, ctx);
        } else {
            // recycled
            return calculateRecycledSellPrice(shopItem, recycleSource, recycleConfig, shopConfig, sellBaseValue, ctx);
        }
    }

    /**
     * 使用已解析配置的快捷方法。
     */
    public PriceResult calculateSellPrice(ShopItem shopItem, RecycleItem recycleSource, PriceContext ctx) {
        ShopPricingConfig shopConfig = configResolver.resolveForShopItem(shopItem.getId());
        RecyclePricingConfig recycleConfig = recycleSource != null
                ? configResolver.resolveForRecycleItem(recycleSource.getId())
                : null;
        return calculateSellPrice(shopItem, recycleSource, recycleConfig, shopConfig, ctx);
    }

    // ========== 出售内部计算 ==========

    /**
     * 解析出售标准价值。
     * 优先级：sell-value > recycle-source × sell-multiplier > buy-price
     */
    private double resolveSellBaseValue(ShopItem shopItem, RecycleItem recycleSource,
                                         RecyclePricingConfig recycleConfig, PriceContext ctx) {
        // 1) sell-value 显式指定
        if (shopItem.getSellValue() != null) {
            return shopItem.getSellValue();
        }
        // 2) recycled 模式：recycle-source 基准价 × sell-multiplier
        if (shopItem.isRecycled() && recycleSource != null) {
            return recycleSource.getBaseRecyclePrice() * shopItem.getSellMultiplier();
        }
        // 3) 兜底：buy-price
        return shopItem.getBaseBuyPrice();
    }

    /**
     * fixed + unlimited 模式：动态关闭 → sellBaseValue；动态开启 → 周期 × 经济倍率。
     */
    private PriceResult calculateFixedUnlimitedSellPrice(ShopItem shopItem, ShopPricingConfig shopConfig,
                                                          double sellBaseValue, PriceContext ctx) {
        boolean dynamicEnabled = shopConfig.dynamicEnabled() && shopConfig.fixedDynamicEnabled();

        if (!dynamicEnabled) {
            double price = roundPrice(sellBaseValue);
            return new PriceResult(price, price, price, sellBaseValue, 1.0, 0.0, 1.0, 1.0, false);
        }

        double cycleMultiplier = computeCycleMultiplier(shopItem.getId(), ctx.nowMillis(),
                configResolver.resolveForRecycleItem(shopItem.getId()).cyclePeriodHours(),
                configResolver.resolveForRecycleItem(shopItem.getId()).cycleAmplitude());
        // 对于没有关联 recycleItem 的 shop item，使用全局默认周期参数
        // 实际上 fixed 商品的周期来自 recycle 全局配置，因为 01 总览写 "共用基础概念"
        RecyclePricingConfig rcfg = configResolver.resolveForRecycleItem(shopItem.getId());
        cycleMultiplier = computeCycleMultiplier(shopItem.getId(), ctx.nowMillis(),
                rcfg.cyclePeriodHours(), rcfg.cycleAmplitude());

        double rawPrice = sellBaseValue * cycleMultiplier * ctx.economyShopMultiplier();

        double minSellPrice = shopConfig.minSellPrice() != null ? shopConfig.minSellPrice() : sellBaseValue * shopConfig.minSellMultiplier();
        double maxSellPrice = shopConfig.maxSellPrice() != null ? shopConfig.maxSellPrice() : sellBaseValue * shopConfig.maxSellMultiplier();
        double finalPrice = roundPrice(clamp(rawPrice, minSellPrice, maxSellPrice));

        return new PriceResult(finalPrice, finalPrice, finalPrice, sellBaseValue,
                cycleMultiplier, 0.0, ctx.economyShopMultiplier(), 1.0, false);
    }

    /**
     * fixed + limited 模式：在 fixed+unlimited 基础上加入库存稀缺倍率。
     */
    private PriceResult calculateFixedLimitedSellPrice(ShopItem shopItem, ShopPricingConfig shopConfig,
                                                        double sellBaseValue, PriceContext ctx) {
        boolean dynamicEnabled = shopConfig.dynamicEnabled() && shopConfig.fixedDynamicEnabled();

        if (!dynamicEnabled) {
            double price = roundPrice(sellBaseValue);
            return new PriceResult(price, price, price, sellBaseValue, 1.0, 0.0, 1.0, 1.0, false);
        }

        double cycleMultiplier = 1.0;
        double stockMultiplier = 1.0;

        RecyclePricingConfig rcfg = configResolver.resolveForRecycleItem(shopItem.getId());
        cycleMultiplier = computeCycleMultiplier(shopItem.getId(), ctx.nowMillis(),
                rcfg.cyclePeriodHours(), rcfg.cycleAmplitude());

        // 库存稀缺度
        int refStock = shopConfig.pricingReferenceStock() != null
                ? shopConfig.pricingReferenceStock()
                : (ctx.referenceStock() > 0 ? ctx.referenceStock() : 1);
        if (refStock <= 0) refStock = 1;

        double stockRatio = (double) ctx.currentStock() / refStock;
        double scarcityRatio = 1.0 - clamp(stockRatio, 0.0, 1.0);
        stockMultiplier = shopConfig.minStockMultiplier()
                + (shopConfig.maxStockMultiplier() - shopConfig.minStockMultiplier()) * scarcityRatio;

        double rawPrice = sellBaseValue * cycleMultiplier * ctx.economyShopMultiplier() * stockMultiplier;

        double minSellPrice = shopConfig.minSellPrice() != null ? shopConfig.minSellPrice() : sellBaseValue * shopConfig.minSellMultiplier();
        double maxSellPrice = shopConfig.maxSellPrice() != null ? shopConfig.maxSellPrice() : sellBaseValue * shopConfig.maxSellMultiplier();
        double finalPrice = roundPrice(clamp(rawPrice, minSellPrice, maxSellPrice));

        return new PriceResult(finalPrice, finalPrice, finalPrice, sellBaseValue,
                cycleMultiplier, 0.0, dynamicEnabled ? ctx.economyShopMultiplier() : 1.0, stockMultiplier, false);
    }

    /**
     * recycled 模式：回流库存倍率 + 防套利。
     */
    private PriceResult calculateRecycledSellPrice(ShopItem shopItem, RecycleItem recycleSource,
                                                    RecyclePricingConfig recycleConfig, ShopPricingConfig shopConfig,
                                                    double sellBaseValue, PriceContext ctx) {
        boolean dynamicEnabled = shopConfig.dynamicEnabled() && shopConfig.recycledDynamicEnabled();

        double cycleMultiplier = 1.0;
        double stockMultiplier = 1.0;
        boolean antiArbitrageApplied = false;

        if (dynamicEnabled) {
            // 周期波动（与回收共用同一周期参数）
            RecyclePricingConfig rcfg = recycleConfig != null
                    ? recycleConfig
                    : configResolver.resolveForRecycleItem(recycleSource != null ? recycleSource.getId() : shopItem.getId());
            cycleMultiplier = computeCycleMultiplier(shopItem.getId(), ctx.nowMillis(),
                    rcfg.cyclePeriodHours(), rcfg.cycleAmplitude());

            // 回流库存倍率
            int targetStock = shopConfig.targetRecycledStock();
            if (targetStock <= 0) targetStock = 1;
            double stockRatio = (double) ctx.recycledStock() / targetStock;
            double overflowRatio = clamp(stockRatio, 0.0, 1.0);
            stockMultiplier = shopConfig.maxRecycledStockMultiplier()
                    - (shopConfig.maxRecycledStockMultiplier() - shopConfig.minRecycledStockMultiplier()) * overflowRatio;
        }

        double rawPrice;
        if (dynamicEnabled) {
            rawPrice = sellBaseValue * cycleMultiplier * ctx.economyShopMultiplier() * stockMultiplier;
        } else {
            rawPrice = sellBaseValue;
        }

        double minSellPrice = shopConfig.minSellPrice() != null ? shopConfig.minSellPrice() : sellBaseValue * shopConfig.minSellMultiplier();
        double maxSellPrice = shopConfig.maxSellPrice() != null ? shopConfig.maxSellPrice() : sellBaseValue * shopConfig.maxSellMultiplier();
        double clampedPrice = clamp(rawPrice, minSellPrice, maxSellPrice);

        // 防套利：售价 >= 当前回收价 × anti-arbitrage-multiplier
        if (dynamicEnabled && recycleSource != null && recycleConfig != null) {
            PriceResult currentRecycle = calculateRecyclePrice(recycleSource, recycleConfig, ctx);
            double antiArbitrageFloor = currentRecycle.unitPrice() * shopConfig.antiArbitrageMultiplier();
            if (clampedPrice < antiArbitrageFloor) {
                clampedPrice = antiArbitrageFloor;
                antiArbitrageApplied = true;
            }
        }

        double finalPrice = roundPrice(clampedPrice);

        return new PriceResult(finalPrice, finalPrice, finalPrice, sellBaseValue,
                cycleMultiplier, 0.0, dynamicEnabled ? ctx.economyShopMultiplier() : 1.0,
                stockMultiplier, antiArbitrageApplied);
    }

    // ========== 辅助方法 ==========

    /**
     * 根据物品 ID 派生稳定相位，使不同物品周期波动不同步。
     * 使用 hashCode × 质数 mod 2π，保证跨服/跨重启确定性。
     */
    static double computeItemPhase(String itemId) {
        return (itemId.hashCode() * PHASE_PRIME) % (2 * Math.PI);
    }

    /**
     * 计算周期波动倍率。
     * {@code cycleMultiplier = 1 + amplitude × sin(2π × nowMs / (periodHours × 3600000) + itemPhase)}
     */
    static double computeCycleMultiplier(String itemId, long nowMillis, double periodHours, double amplitude) {
        if (periodHours <= 0 || amplitude <= 0) return 1.0;
        double phase = computeItemPhase(itemId);
        double periodMillis = periodHours * 3600000.0;
        return 1.0 + amplitude * Math.sin(2 * Math.PI * nowMillis / periodMillis + phase);
    }

    /**
     * smoothstep 压力曲线。
     * {@code x = clamp(activeVolume / minPriceVolume, 0, 1); pressureRatio = x² × (3 - 2x)}
     */
    static double computePressureRatio(int activeVolume, int minPriceVolume) {
        if (minPriceVolume <= 0) return activeVolume > 0 ? 1.0 : 0.0;
        double x = clamp((double) activeVolume / minPriceVolume, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    /**
     * smoothstep（接受 double 参数的重载，用于出售端等场景）。
     */
    static double smoothstep(double x) {
        x = clamp(x, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    /**
     * 数值钳制。
     */
    static double clamp(double value, double min, double max) {
        if (min > max) {
            double temp = min;
            min = max;
            max = temp;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 价格保留两位小数。
     */
    static double roundPrice(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
