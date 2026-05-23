package net.scarletphantasy.gensouMarket.model;

/**
 * 压力桶数据（v1.1.1 价格算法）。
 * <p>
 * 每个桶记录一个时间段内某物品的累计回收数量，
 * 用于计算 {@link net.scarletphantasy.gensouMarket.shop.PriceEngine} 的 {@code activeVolume}。
 *
 * @param itemId      回收物品 ID
 * @param bucketStart 桶起始时间戳（毫秒）
 * @param amount      该桶内累计回收数量
 * @param updatedAt   最后更新时间戳（毫秒）
 */
public record PressureBucket(
    String itemId,
    long bucketStart,
    int amount,
    long updatedAt
) {
    /**
     * 创建一个带有新数量的副本。
     */
    public PressureBucket withAmount(int newAmount) {
        return new PressureBucket(itemId, bucketStart, newAmount, System.currentTimeMillis());
    }

    /**
     * 追加数量后返回新桶。
     */
    public PressureBucket addAmount(int delta) {
        return new PressureBucket(itemId, bucketStart, amount + delta, System.currentTimeMillis());
    }
}
