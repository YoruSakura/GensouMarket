package net.scarletphantasy.gensouMarket.util;

/**
 * 1D Perlin Noise 实现。
 * 基于种子的确定性实现，同一输入永远返回同一输出。
 * 输出范围 [-1, 1]。
 */
public final class PerlinNoise {

    private static final int TABLE_SIZE = 256;
    private final int[] perm;

    public PerlinNoise(int seed) {
        perm = new int[TABLE_SIZE * 2];
        int[] base = new int[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            base[i] = i;
        }
        // Fisher-Yates shuffle with seed
        java.util.Random rng = new java.util.Random(seed);
        for (int i = TABLE_SIZE - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = base[i];
            base[i] = base[j];
            base[j] = tmp;
        }
        for (int i = 0; i < TABLE_SIZE; i++) {
            perm[i] = base[i];
            perm[i + TABLE_SIZE] = base[i];
        }
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private double grad(int hash, double x) {
        return (hash & 1) == 0 ? x : -x;
    }

    /**
     * 1D Perlin Noise。
     * @param x 输入坐标
     * @return 范围 [-1, 1] 的噪声值
     */
    public double noise(double x) {
        int xi = (int) Math.floor(x) & (TABLE_SIZE - 1);
        double xf = x - Math.floor(x);
        double u = fade(xf);
        int a = perm[xi];
        int b = perm[xi + 1];
        return lerp(u, grad(a, xf), grad(b, xf - 1));
    }
}
