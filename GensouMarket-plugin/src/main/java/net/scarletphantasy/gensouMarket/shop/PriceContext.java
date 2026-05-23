package net.scarletphantasy.gensouMarket.shop;

/**
 * 价格计算上下文，封装运行时状态参数。
 * 所有字段均为快照值，计算过程中不修改。
 *
 * @param nowMillis                当前时间戳（毫秒）；可传入固定值以确保跨服一致性和单元测试可控性
 * @param economyRecycleMultiplier 全服经济阶段-回收倍率（模块 15 提供，未接入前默认 1.0）
 * @param economyShopMultiplier    全服经济阶段-商店倍率（模块 15 提供，未接入前默认 1.0）
 * @param activeVolume             压力窗口内活跃回收量（模块 13 提供，未接入前默认 0）
 * @param recycledStock            当前回流库存（recycled 出售模式用）
 * @param currentStock             当前有限库存（fixed+limited 出售模式用）
 * @param referenceStock           参考库存量（fixed+limited 出售模式用，用于计算稀缺度）
 */
public record PriceContext(
    long nowMillis,
    double economyRecycleMultiplier,
    double economyShopMultiplier,
    int activeVolume,
    int recycledStock,
    int currentStock,
    int referenceStock
) {
    /**
     * 使用当前系统时间、默认经济倍率 1.0、零活跃量和零库存构造。
     */
    public static PriceContext ofNow() {
        return new PriceContext(System.currentTimeMillis(), 1.0, 1.0, 0, 0, 0, 0);
    }

    /**
     * 使用指定时间、默认经济倍率 1.0、零活跃量和零库存构造。
     */
    public static PriceContext ofTime(long millis) {
        return new PriceContext(millis, 1.0, 1.0, 0, 0, 0, 0);
    }
}
