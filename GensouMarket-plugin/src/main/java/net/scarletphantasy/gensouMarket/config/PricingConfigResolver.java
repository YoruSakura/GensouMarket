package net.scarletphantasy.gensouMarket.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PricingConfigResolver {

    private final ConfigManager configManager;
    private final Logger logger;
    private final Set<String> warnedGroups = ConcurrentHashMap.newKeySet();

    public PricingConfigResolver(ConfigManager configManager, Logger logger) {
        this.configManager = configManager;
        this.logger = logger;
    }

    public RecyclePricingConfig resolveForRecycleItem(String itemId) {
        RecyclePricingConfig defaults = configManager.getRecyclePricingDefaults();
        ConfigurationSection group = findGroup(configManager.getRecycleItemPricingGroup(itemId), "回收", itemId);
        ConfigurationSection section = mergePricing(child(group, "recycle"),
                configManager.getRecycleItemPricingSection(itemId));
        if (section == null) return defaults;

        boolean dynamicEnabled = section.contains("dynamic-enabled")
            ? section.getBoolean("dynamic-enabled")
            : defaults.dynamicEnabled();

        int minPriceVolume = section.contains("min-price-volume")
            ? section.getInt("min-price-volume")
            : defaults.minPriceVolume();

        double minMultiplier = section.contains("min-multiplier")
            ? section.getDouble("min-multiplier")
            : defaults.minMultiplier();

        double maxMultiplier = section.contains("max-multiplier")
            ? section.getDouble("max-multiplier")
            : defaults.maxMultiplier();

        Double minPrice = optionalDouble(section, "min-price", defaults.minPrice());

        Double maxPrice = optionalDouble(section, "max-price", defaults.maxPrice());

        int pressureWindowMinutes = section.contains("pressure-window-minutes")
            ? section.getInt("pressure-window-minutes")
            : defaults.pressureWindowMinutes();

        int bucketSeconds = section.contains("bucket-seconds")
            ? section.getInt("bucket-seconds")
            : defaults.bucketSeconds();

        double cyclePeriodHours = section.contains("cycle-period-hours")
            ? section.getDouble("cycle-period-hours")
            : defaults.cyclePeriodHours();

        double cycleAmplitude = section.contains("cycle-amplitude")
            ? section.getDouble("cycle-amplitude")
            : defaults.cycleAmplitude();

        // 校验
        if (minMultiplier < 0) {
            logger.warning("回收定价配置: " + itemId + " min-multiplier 不能小于 0，已修正为 0");
            minMultiplier = 0;
        }
        if (minMultiplier > maxMultiplier) {
            logger.warning("回收定价配置: " + itemId + " min-multiplier(" + minMultiplier + ") > max-multiplier(" + maxMultiplier + ")，已交换");
            double tmp = minMultiplier;
            minMultiplier = maxMultiplier;
            maxMultiplier = tmp;
        }
        if (minPriceVolume < 0) {
            logger.warning("回收定价配置: " + itemId + " min-price-volume 不能小于 0，已修正为 0");
            minPriceVolume = 0;
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            logger.warning("回收定价配置: " + itemId + " min-price(" + minPrice + ") > max-price(" + maxPrice + ")，已交换");
            Double tmp = minPrice;
            minPrice = maxPrice;
            maxPrice = tmp;
        }

        return new RecyclePricingConfig(
            dynamicEnabled,
            pressureWindowMinutes,
            bucketSeconds,
            minPriceVolume,
            minMultiplier,
            maxMultiplier,
            cyclePeriodHours,
            cycleAmplitude,
            minPrice,
            maxPrice
        );
    }

    public ShopPricingConfig resolveForShopItem(String itemId) {
        ShopPricingConfig defaults = configManager.getShopPricingDefaults();
        ConfigurationSection group = findGroup(configManager.getShopItemPricingGroup(itemId), "商店", itemId);
        // 未单独指定商店周期时，保留从回收来源（或同 ID 回收项）继承周期的行为。
        String sourceId = configManager.getShopRecycleSourceId(itemId);
        RecyclePricingConfig cycle = resolveForRecycleItem(sourceId != null ? sourceId : itemId);
        ConfigurationSection groupRecycle = child(group, "recycle");
        defaults = defaults.withCycle(
                groupRecycle != null ? groupRecycle.getDouble("cycle-period-hours", cycle.cyclePeriodHours())
                        : cycle.cyclePeriodHours(),
                groupRecycle != null ? groupRecycle.getDouble("cycle-amplitude", cycle.cycleAmplitude())
                        : cycle.cycleAmplitude());
        ConfigurationSection section = mergePricing(child(group, "shop"),
                configManager.getShopItemPricingSection(itemId));
        if (section == null) return defaults;

        boolean dynamicEnabled = section.contains("dynamic-enabled")
            ? section.getBoolean("dynamic-enabled")
            : defaults.dynamicEnabled();

        double minSellMultiplier = section.contains("min-sell-multiplier")
            ? section.getDouble("min-sell-multiplier")
            : defaults.minSellMultiplier();

        double maxSellMultiplier = section.contains("max-sell-multiplier")
            ? section.getDouble("max-sell-multiplier")
            : defaults.maxSellMultiplier();

        Integer pricingReferenceStock = optionalInteger(section, "pricing-reference-stock",
            defaults.pricingReferenceStock());

        Double minSellPrice = optionalDouble(section, "min-sell-price", defaults.minSellPrice());

        Double maxSellPrice = optionalDouble(section, "max-sell-price", defaults.maxSellPrice());

        boolean fixedDynamicEnabled = section.contains("fixed-dynamic-enabled")
            ? section.getBoolean("fixed-dynamic-enabled")
            : defaults.fixedDynamicEnabled();

        boolean recycledDynamicEnabled = section.contains("recycled-dynamic-enabled")
            ? section.getBoolean("recycled-dynamic-enabled")
            : defaults.recycledDynamicEnabled();

        double minStockMultiplier = section.contains("min-stock-multiplier")
            ? section.getDouble("min-stock-multiplier")
            : defaults.minStockMultiplier();

        double maxStockMultiplier = section.contains("max-stock-multiplier")
            ? section.getDouble("max-stock-multiplier")
            : defaults.maxStockMultiplier();

        int targetRecycledStock = section.contains("target-recycled-stock")
            ? section.getInt("target-recycled-stock")
            : defaults.targetRecycledStock();

        double minRecycledStockMultiplier = section.contains("min-recycled-stock-multiplier")
            ? section.getDouble("min-recycled-stock-multiplier")
            : defaults.minRecycledStockMultiplier();

        double maxRecycledStockMultiplier = section.contains("max-recycled-stock-multiplier")
            ? section.getDouble("max-recycled-stock-multiplier")
            : defaults.maxRecycledStockMultiplier();

        double antiArbitrageMultiplier = section.contains("anti-arbitrage-multiplier")
            ? section.getDouble("anti-arbitrage-multiplier")
            : defaults.antiArbitrageMultiplier();

        // 校验
        if (minSellMultiplier > maxSellMultiplier) {
            logger.warning("商店定价配置: " + itemId + " min-sell-multiplier(" + minSellMultiplier + ") > max-sell-multiplier(" + maxSellMultiplier + ")，已交换");
            double tmp = minSellMultiplier;
            minSellMultiplier = maxSellMultiplier;
            maxSellMultiplier = tmp;
        }
        if (minSellPrice != null && maxSellPrice != null && minSellPrice > maxSellPrice) {
            logger.warning("商店定价配置: " + itemId + " min-sell-price(" + minSellPrice + ") > max-sell-price(" + maxSellPrice + ")，已交换");
            Double tmp = minSellPrice;
            minSellPrice = maxSellPrice;
            maxSellPrice = tmp;
        }

        return new ShopPricingConfig(
            dynamicEnabled,
            minSellMultiplier,
            maxSellMultiplier,
            fixedDynamicEnabled,
            recycledDynamicEnabled,
            minStockMultiplier,
            maxStockMultiplier,
            targetRecycledStock,
            minRecycledStockMultiplier,
            maxRecycledStockMultiplier,
            antiArbitrageMultiplier,
            pricingReferenceStock,
            minSellPrice,
            maxSellPrice,
            section.getDouble("cycle-period-hours", defaults.cyclePeriodHours()),
            section.getDouble("cycle-amplitude", defaults.cycleAmplitude())
        );
    }

    private ConfigurationSection findGroup(String groupId, String kind, String itemId) {
        if (groupId == null || groupId.isBlank()) return null;
        String name = groupId.trim();
        ConfigurationSection group = configManager.getPricingGroupSection(name);
        if (group == null && warnedGroups.add(kind + ":" + itemId + ":" + name)) {
            logger.warning(kind + "定价配置: " + itemId + " 引用了不存在的分组 " + name
                    + "，将使用全局默认值和物品单独配置");
        }
        return group;
    }

    private static ConfigurationSection child(ConfigurationSection section, String key) {
        return section != null ? section.getConfigurationSection(key) : null;
    }

    private static ConfigurationSection mergePricing(ConfigurationSection group, ConfigurationSection item) {
        if (group == null) return item;
        MemoryConfiguration merged = new MemoryConfiguration();
        group.getValues(false).forEach(merged::set);
        if (item != null) item.getValues(false).forEach(merged::set);
        return merged;
    }

    private Double optionalDouble(ConfigurationSection section, String key, Double fallback) {
        if (!section.contains(key)) {
            return fallback;
        }
        return Double.valueOf(section.getDouble(key));
    }

    private Integer optionalInteger(ConfigurationSection section, String key, Integer fallback) {
        if (!section.contains(key)) {
            return fallback;
        }
        return Integer.valueOf(section.getInt(key));
    }
}
