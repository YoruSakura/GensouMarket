package net.scarletphantasy.gensouMarket.shop;

/**
 * 价格计算结果，承载最终价格和关键中间值，供 GUI 展示、日志记录和调试使用。
 *
 * @param unitPrice              最终单价（已保留两位小数）
 * @param totalPrice             批量总价（单个时 = unitPrice，已保留两位小数）
 * @param averagePrice           批量平均价（单个时 = unitPrice，已保留两位小数）
 * @param baseValue              基准价值（回收取 recycle-price，出售取 sell-value 优先级链结果）
 * @param cycleMultiplier        周期波动倍率
 * @param pressureRatio          回收压力比率 [0, 1]（出售计算时为 0）
 * @param economyMultiplier      全服经济阶段倍率
 * @param stockMultiplier        库存影响倍率（出售计算用，回收时为 1.0）
 * @param antiArbitrageApplied   是否触发了防套利地板价（仅 recycled 出售模式可能为 true）
 */
public record PriceResult(
    double unitPrice,
    double totalPrice,
    double averagePrice,
    double baseValue,
    double cycleMultiplier,
    double pressureRatio,
    double economyMultiplier,
    double stockMultiplier,
    boolean antiArbitrageApplied
) {}
