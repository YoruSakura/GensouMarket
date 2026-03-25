package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.model.RecycleItem;

public class PriceEngine {

    private final ConfigManager config;

    public PriceEngine(ConfigManager config) {
        this.config = config;
    }

    /**
     * 重新计算回收价格乘数。
     * 应用时间衰减使价格逐渐恢复到基准。
     */
    public void recalculate(RecycleItem item) {
        if (!config.isDynamicPricingEnabled()) {
            item.setRecycleMultiplier(1.0);
            return;
        }

        double recoveryRate = config.getRecoveryRate();
        long now = System.currentTimeMillis();
        double hoursSinceUpdate = (now - item.getLastUpdate()) / 3600000.0;

        double decayFactor = Math.exp(-recoveryRate * hoursSinceUpdate);
        double multiplier = item.getRecycleMultiplier();
        multiplier = 1.0 + (multiplier - 1.0) * decayFactor;
        multiplier = clamp(multiplier, config.getMinMultiplier(), config.getMaxMultiplier());

        item.setRecycleMultiplier(multiplier);
        item.setLastUpdate(now);
    }

    /**
     * 玩家向服务器回收物品时，回收价格下降。
     */
    public void onPlayerRecycle(RecycleItem item, int amount) {
        item.addRecycled(amount);

        if (!config.isDynamicPricingEnabled()) return;

        double adjustmentRate = config.getAdjustmentRate();
        double minMultiplier = config.getMinMultiplier();
        double recoveryRate = config.getRecoveryRate();

        long now = System.currentTimeMillis();
        double hoursSinceUpdate = (now - item.getLastUpdate()) / 3600000.0;

        // 先应用时间衰减恢复
        double decayFactor = Math.exp(-recoveryRate * hoursSinceUpdate);
        double multiplier = item.getRecycleMultiplier();
        multiplier = 1.0 + (multiplier - 1.0) * decayFactor;

        // 再扣减本次出售的影响
        multiplier -= adjustmentRate * amount;
        multiplier = Math.max(minMultiplier, multiplier);

        item.setRecycleMultiplier(multiplier);
        item.setLastUpdate(now);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
