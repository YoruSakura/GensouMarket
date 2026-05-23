package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.config.RecyclePricingConfig;
import net.scarletphantasy.gensouMarket.model.PressureBucket;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 回收压力窗口管理器（v1.1.1）。
 * <p>
 * 每个 {@link net.scarletphantasy.gensouMarket.model.RecycleItem} 拥有独立的滚动时间桶列表。
 * 桶的宽度由 {@code bucket-seconds} 决定，窗口总长由 {@code pressure-window-minutes} 决定。
 * <p>
 * 在回收前读取 {@code activeVolume}，回收成功后才写入压力（先结算再加压力）。
 *
 * <p>线程安全说明：每个 item 的桶列表在 synchronized 块内操作。跨服同步由后续模块负责。
 */
public class PressureWindowManager {

    private final StorageProvider storage;
    private final Logger logger;
    private final Plugin plugin;

    /** per-item 压力状态：itemId → 桶列表（按 bucketStart 升序） */
    private final Map<String, List<PressureBucket>> pressureMap = new ConcurrentHashMap<>();

    public PressureWindowManager(Plugin plugin, StorageProvider storage, Logger logger) {
        this.plugin = plugin;
        this.storage = storage;
        this.logger = logger;
    }

    // ========== 生命周期 ==========

    /**
     * 冷启动：加载所有压力桶 → 清理过期 → 重建 activeVolume。
     */
    public void loadAll(Map<String, RecyclePricingConfig> configByItemId) {
        Map<String, List<PressureBucket>> loaded = storage.loadAllPressureBuckets();
        int totalBuckets = 0;
        int expiredBuckets = 0;

        for (var entry : loaded.entrySet()) {
            String itemId = entry.getKey();
            List<PressureBucket> buckets = new ArrayList<>(entry.getValue());

            RecyclePricingConfig config = configByItemId.getOrDefault(itemId, null);
            long windowMillis = config != null
                    ? config.pressureWindowMinutes() * 60_000L
                    : 120L * 60_000L;
            long cutoff = System.currentTimeMillis() - windowMillis;

            int before = buckets.size();
            buckets.removeIf(b -> b.bucketStart() < cutoff);
            expiredBuckets += (before - buckets.size());
            totalBuckets += buckets.size();

            pressureMap.put(itemId, buckets);

            // 清理过期桶的持久化
            if (before != buckets.size()) {
                storage.deleteExpiredPressureBuckets(itemId, cutoff);
            }
        }

        if (totalBuckets > 0 || expiredBuckets > 0) {
            logger.info("[PressureWindow] 冷启动加载完成: " + totalBuckets + " 个有效桶, " + expiredBuckets + " 个过期桶已清理");
        }
    }

    /**
     * 带默认配置的简化加载（所有物品使用相同默认窗口）。
     */
    public void loadAll(RecyclePricingConfig defaultConfig) {
        Map<String, List<PressureBucket>> loaded = storage.loadAllPressureBuckets();
        long windowMillis = defaultConfig.pressureWindowMinutes() * 60_000L;
        long cutoff = System.currentTimeMillis() - windowMillis;

        for (var entry : loaded.entrySet()) {
            String itemId = entry.getKey();
            List<PressureBucket> buckets = new ArrayList<>(entry.getValue());
            int before = buckets.size();
            buckets.removeIf(b -> b.bucketStart() < cutoff);
            pressureMap.put(itemId, buckets);

            if (before != buckets.size()) {
                storage.deleteExpiredPressureBuckets(itemId, cutoff);
            }
        }
    }

    // ========== 核心操作 ==========

    /**
     * 获取当前 activeVolume（清理过期桶后）。
     * <p>
     * 该方法在回收 <strong>前</strong> 调用，用于计算本次结算价格。
     *
     * @param itemId 物品 ID
     * @param config 该物品的定价配置
     * @param now    当前时间戳
     * @return 窗口内活跃回收量
     */
    public int getActiveVolume(String itemId, RecyclePricingConfig config, long now) {
        List<PressureBucket> buckets = pressureMap.get(itemId);
        if (buckets == null || buckets.isEmpty()) return 0;

        long windowMillis = config.pressureWindowMinutes() * 60_000L;
        long cutoff = now - windowMillis;

        synchronized (buckets) {
            // 清理过期桶
            buckets.removeIf(b -> b.bucketStart() < cutoff);

            // 求和
            int volume = 0;
            for (PressureBucket b : buckets) {
                volume += b.amount();
            }
            return volume;
        }
    }

    /**
     * 回收成功后，写入压力并返回更新后的 activeVolume。
     * <p>
     * 在 Vault 入账和物品移除 <strong>之后</strong> 调用。
     *
     * @param itemId 物品 ID
     * @param config 该物品的定价配置
     * @param amount 本次回收数量
     * @param now    当前时间戳
     * @return 更新后的 activeVolume
     */
    public int recordRecycle(String itemId, RecyclePricingConfig config, int amount, long now) {
        long bucketMillis = config.bucketSeconds() * 1000L;
        if (bucketMillis <= 0) bucketMillis = 60_000L;
        long bucketStart = (now / bucketMillis) * bucketMillis;

        List<PressureBucket> buckets = pressureMap.computeIfAbsent(itemId, k -> new ArrayList<>());

        synchronized (buckets) {
            // 清理过期桶
            long windowMillis = config.pressureWindowMinutes() * 60_000L;
            long cutoff = now - windowMillis;
            buckets.removeIf(b -> b.bucketStart() < cutoff);

            // 找到或创建当前桶
            boolean found = false;
            for (int i = 0; i < buckets.size(); i++) {
                PressureBucket b = buckets.get(i);
                if (b.bucketStart() == bucketStart) {
                    buckets.set(i, b.addAmount(amount));
                    found = true;
                    break;
                }
            }
            if (!found) {
                buckets.add(new PressureBucket(itemId, bucketStart, amount, now));
                // 保持升序
                buckets.sort(Comparator.comparingLong(PressureBucket::bucketStart));
            }

            // 异步保存 (仅增量更新)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                storage.addPressureAmount(itemId, bucketStart, amount, now);
            });

            // 求和
            int volume = 0;
            for (PressureBucket b : buckets) {
                volume += b.amount();
            }
            return volume;
        }
    }

    /**
     * 跨服同步：合并远程服务器发来的压力增量。
     * <p>
     * 仅在内存中更新桶数据，不执行持久化（MySQL 是权威数据源，由事件源服务器负责写入）。
     */
    public void mergeRemotePressure(String itemId, long bucketStart, int amountDelta) {
        if (amountDelta <= 0) return;
        
        List<PressureBucket> buckets = pressureMap.computeIfAbsent(itemId, k -> new ArrayList<>());

        synchronized (buckets) {
            boolean found = false;
            for (int i = 0; i < buckets.size(); i++) {
                PressureBucket b = buckets.get(i);
                if (b.bucketStart() == bucketStart) {
                    buckets.set(i, b.addAmount(amountDelta));
                    found = true;
                    break;
                }
            }
            if (!found) {
                buckets.add(new PressureBucket(itemId, bucketStart, amountDelta, System.currentTimeMillis()));
                buckets.sort(Comparator.comparingLong(PressureBucket::bucketStart));
            }
        }
    }

    /**
     * 获取指定物品的桶列表快照（用于调试/admin 命令）。
     */
    public List<PressureBucket> getBuckets(String itemId) {
        List<PressureBucket> buckets = pressureMap.get(itemId);
        if (buckets == null) return List.of();
        synchronized (buckets) {
            return List.copyOf(buckets);
        }
    }
}
