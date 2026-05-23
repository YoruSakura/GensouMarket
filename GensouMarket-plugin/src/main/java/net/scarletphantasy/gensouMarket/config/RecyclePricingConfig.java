package net.scarletphantasy.gensouMarket.config;

public record RecyclePricingConfig(
    boolean dynamicEnabled,
    int pressureWindowMinutes,
    int bucketSeconds,
    int minPriceVolume,
    double minMultiplier,
    double maxMultiplier,
    double cyclePeriodHours,
    double cycleAmplitude,
    Double minPrice,
    Double maxPrice
) {
    public double effectiveMinPrice(double basePrice) {
        return minPrice != null ? minPrice : basePrice * minMultiplier;
    }

    public double effectiveMaxPrice(double basePrice) {
        return maxPrice != null ? maxPrice : basePrice * maxMultiplier;
    }
}
