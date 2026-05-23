package net.scarletphantasy.gensouMarket.economy;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.EconomyBalanceConfig;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 全服账面货币总量快照服务（v1.1.1）。
 * <p>
 * 定时刷新全服余额快照并根据配置阶段缓存经济倍率，供 {@link net.scarletphantasy.gensouMarket.shop.PriceEngine} 只读消费。
 * <p>
 * 兜底顺序：
 * <ol>
 *   <li>当前 CMI 成功快照</li>
 *   <li>本插件通过 Vault 遍历离线玩家得到的自有快照</li>
 *   <li>上一次成功快照</li>
 *   <li>economyMultiplier = 1.0</li>
 * </ol>
 *
 * CMI 为软依赖，不存在或异常时不影响插件启动。
 */
public class EconomySnapshotService {

    private final GensouMarket plugin;
    private final Logger logger;

    // ---- 线程安全快照（volatile 保证可见性） ----
    private volatile double recycleMultiplier = 1.0;
    private volatile double shopMultiplier = 1.0;
    private volatile long lastTotalMoney = -1;
    private volatile long lastSnapshotTime = 0;

    // ---- CMI 可用状态 ----
    private volatile boolean cmiAvailable = false;

    // ---- 定时任务 ----
    private BukkitTask refreshTask;

    // ---- 异常日志限流：同一异常 30 秒内只打印一次 ----
    private static final long ERROR_LOG_COOLDOWN_MS = 30_000;
    private volatile long lastErrorLogTime = 0;

    public EconomySnapshotService(GensouMarket plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * 启动快照服务。在主类 onEnable 中调用。
     */
    public void start() {
        // 检测 CMI
        detectCmi();

        // 默认倍率 1.0，防止异步快照完成前被访问
        recycleMultiplier = 1.0;
        shopMultiplier = 1.0;

        // 定时刷新（异步，不阻塞主线程）
        EconomyBalanceConfig config = plugin.getConfigManager().getEconomyBalanceConfig();
        int intervalMinutes = Math.max(config.snapshotIntervalMinutes(), 1);
        long intervalTicks = intervalMinutes * 60L * 20L; // 分钟 → tick

        // 延迟 1 tick 执行首次快照，之后的按照间隔刷新
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshSnapshot,
                1L, intervalTicks);

        if (cmiAvailable) {
            logger.info("经济快照服务已启动 (CMI 可用, 间隔=" + intervalMinutes + "分钟)");
        } else {
            logger.info("经济快照服务已启动 (CMI 不可用, 将使用自有快照兜底, 间隔=" + intervalMinutes + "分钟)");
        }
    }

    /**
     * 停止快照服务。在主类 onDisable 中调用。
     */
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    // ========== 只读快照访问（线程安全） ==========

    /**
     * 获取当前回收经济倍率。
     */
    public double getRecycleMultiplier() {
        return recycleMultiplier;
    }

    /**
     * 获取当前商店经济倍率。
     */
    public double getShopMultiplier() {
        return shopMultiplier;
    }

    /**
     * 获取上次快照的全服总额（-1 表示从未成功读取）。
     */
    public long getLastTotalMoney() {
        return lastTotalMoney;
    }

    /**
     * 获取上次快照时间戳。
     */
    public long getLastSnapshotTime() {
        return lastSnapshotTime;
    }

    /**
     * CMI 是否可用。
     */
    public boolean isCmiAvailable() {
        return cmiAvailable;
    }

    // ========== 内部逻辑 ==========

    /**
     * 检测 CMI 插件是否存在且启用。
     */
    private void detectCmi() {
        try {
            var cmiPlugin = Bukkit.getPluginManager().getPlugin("CMI");
            cmiAvailable = cmiPlugin != null && cmiPlugin.isEnabled();
        } catch (Exception e) {
            cmiAvailable = false;
            logErrorThrottled("检测 CMI 时发生异常", e);
        }
    }

    /**
     * 刷新全服余额快照。可在异步线程调用。
     */
    private void refreshSnapshot() {
        EconomyBalanceConfig config = plugin.getConfigManager().getEconomyBalanceConfig();

        if (!config.enabled()) {
            // 经济平衡功能关闭，倍率固定为 1.0
            recycleMultiplier = 1.0;
            shopMultiplier = 1.0;
            return;
        }

        long totalMoney = tryReadTotalMoney();

        if (totalMoney >= 0) {
            // 成功读取
            lastTotalMoney = totalMoney;
            lastSnapshotTime = System.currentTimeMillis();

            EconomyBalanceConfig.Stage stage = config.stageFor(totalMoney);
            recycleMultiplier = stage.recycleMultiplier();
            shopMultiplier = stage.shopMultiplier();
        } else if (lastTotalMoney >= 0) {
            // 当前读取失败，但有上次快照，保持上次倍率不变
            // （recycleMultiplier / shopMultiplier 已经是上次值，无需修改）
        } else {
            // 从未成功读取过，使用默认 1.0
            recycleMultiplier = 1.0;
            shopMultiplier = 1.0;
        }
    }

    /**
     * 尝试读取全服总余额。兜底顺序：CMI -> 插件自有快照 (Vault 遍历)。
     *
     * @return 全服总额（>= 0），失败返回 -1
     */
    private long tryReadTotalMoney() {
        if (!cmiAvailable) {
            // 运行时重新检测一次（CMI 可能延迟加载）
            detectCmi();
        }

        if (cmiAvailable) {
            try {
                double total = readCmiTotalMoney();
                return Math.max((long) total, 0);
            } catch (Exception e) {
                logErrorThrottled("读取 CMI 全服余额时发生异常，将尝试使用插件自有快照兜底", e);
            }
        }

        // CMI 不可用或异常时，使用插件自有快照 (由于此方法在异步线程执行，遍历离线玩家是安全的)
        return computeOwnSnapshot();
    }

    /**
     * 尝试通过遍历 OfflinePlayer 读取全服总余额兜底。
     */
    private long computeOwnSnapshot() {
        try {
            double total = 0;
            for (org.bukkit.OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                total += plugin.getVaultHook().getBalance(p);
            }
            return Math.max((long) total, 0);
        } catch (Exception e) {
            logErrorThrottled("读取插件自有快照时发生异常", e);
            return -1;
        }
    }

    /**
     * 实际调用 CMI API 读取全服总额。
     * 隔离到独立方法，便于 CMI 不存在时类加载不触发 NoClassDefFoundError。
     */
    private double readCmiTotalMoney() {
        return com.Zrips.CMI.CMI.getInstance().getEconomyManager().getTotalServerMoney();
    }

    /**
     * 异常日志限流：同一异常在冷却时间内只打印一次，避免刷屏。
     */
    private void logErrorThrottled(String message, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogTime >= ERROR_LOG_COOLDOWN_MS) {
            lastErrorLogTime = now;
            logger.log(Level.WARNING, message, e);
        }
    }
}
