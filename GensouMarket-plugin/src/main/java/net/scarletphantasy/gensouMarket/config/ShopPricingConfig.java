package net.scarletphantasy.gensouMarket.config;

public record ShopPricingConfig(
    boolean dynamicEnabled,
    double minSellMultiplier,
    double maxSellMultiplier,
    boolean fixedDynamicEnabled,
    boolean recycledDynamicEnabled,
    double minStockMultiplier,
    double maxStockMultiplier,
    int targetRecycledStock,
    double minRecycledStockMultiplier,
    double maxRecycledStockMultiplier,
    double antiArbitrageMultiplier,
    Integer pricingReferenceStock,
    Double minSellPrice,
    Double maxSellPrice,
    double cyclePeriodHours,
    double cycleAmplitude
) {
    public ShopPricingConfig withCycle(double periodHours, double amplitude) {
        return new ShopPricingConfig(dynamicEnabled, minSellMultiplier, maxSellMultiplier,
                fixedDynamicEnabled, recycledDynamicEnabled, minStockMultiplier, maxStockMultiplier,
                targetRecycledStock, minRecycledStockMultiplier, maxRecycledStockMultiplier,
                antiArbitrageMultiplier, pricingReferenceStock, minSellPrice, maxSellPrice,
                periodHours, amplitude);
    }
}
