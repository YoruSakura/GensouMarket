package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.util.PerlinNoise;

import java.util.List;

/**
 * 市场波动计算器。
 * 纯函数、无状态、线程安全。
 * 使用三层正弦波 + 1D Perlin Noise 为每个物品生成独立的波动曲线。
 * 所有服务器使用 System.currentTimeMillis() + 确定性函数算出相同结果，天然同步。
 */
public class MarketFluctuation {

    // 用于生成每层正弦波的不同相位的质数
    private static final int[] PHASE_PRIMES = {31, 97, 173};

    private final ConfigManager config;

    public MarketFluctuation(ConfigManager config) {
        this.config = config;
    }

    /**
     * 计算指定物品在指定时间的市场波动系数。
     *
     * @param itemId    物品ID
     * @param timeMillis 时间戳（毫秒）
     * @return 波动系数，范围 [minFluctuation, maxFluctuation]
     */
    public double calculate(String itemId, long timeMillis) {
        if (!config.isMarketFluctuationEnabled()) {
            return 1.0;
        }

        int seed = itemId.hashCode();
        double t = timeMillis / 3600000.0; // 转换为小时

        // 正弦波叠加
        double sineSum = 0.0;
        List<ConfigManager.SineWaveConfig> waves = config.getFluctuationSineWaves();
        for (int i = 0; i < waves.size(); i++) {
            ConfigManager.SineWaveConfig wave = waves.get(i);
            int primeIndex = i < PHASE_PRIMES.length ? i : i % PHASE_PRIMES.length;
            double phase = seed * PHASE_PRIMES[primeIndex];
            sineSum += wave.amplitude() * Math.sin(2 * Math.PI * t / wave.periodHours() + phase);
        }

        // Perlin Noise
        double noiseAmp = config.getFluctuationNoiseAmplitude();
        double noiseScale = config.getFluctuationNoiseScale();
        PerlinNoise perlin = new PerlinNoise(seed);
        double noiseValue = noiseAmp * perlin.noise(t / noiseScale);

        double fluctuation = 1.0 + sineSum + noiseValue;
        return clamp(fluctuation, config.getMinFluctuation(), config.getMaxFluctuation());
    }

    /**
     * 计算指定物品当前相对上一个周期的涨跌百分比。
     *
     * @param itemId    物品ID
     * @param timeMillis 当前时间戳（毫秒）
     * @return 涨跌百分比（正值=涨，负值=跌）
     */
    public double calculateChange(String itemId, long timeMillis) {
        if (!config.isMarketFluctuationEnabled()) {
            return 0.0;
        }

        long cycleMillis = config.getFluctuationCycleMinutes() * 60L * 1000L;
        double current = calculate(itemId, timeMillis);
        double previous = calculate(itemId, timeMillis - cycleMillis);

        if (previous == 0.0) return 0.0;
        return (current - previous) / previous * 100.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
