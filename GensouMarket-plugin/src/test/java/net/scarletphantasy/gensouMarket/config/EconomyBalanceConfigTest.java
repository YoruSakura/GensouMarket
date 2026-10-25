package net.scarletphantasy.gensouMarket.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EconomyBalanceConfigTest {

    private final EconomyBalanceConfig config = new EconomyBalanceConfig(true, 10, List.of(
            new EconomyBalanceConfig.Stage(1_000_000, 1.15, 0.90),
            new EconomyBalanceConfig.Stage(5_000_000, 1.0, 1.0),
            new EconomyBalanceConfig.Stage(10_000_000, 0.85, 1.15),
            new EconomyBalanceConfig.Stage(-1, 0.70, 1.35)));

    @Test void configuredMultipliersAreReachedContinuouslyAtEachBoundary() {
        assertMultipliers(0, 1.15, 0.90);
        assertMultipliers(1_000_000, 1.0, 1.0);
        assertMultipliers(5_000_000, 0.85, 1.15);
        assertMultipliers(10_000_000, 0.70, 1.35);
        assertMultipliers(Long.MAX_VALUE, 0.70, 1.35);
    }

    @Test void intermediateBalancesUseIntermediateMultipliers() {
        assertMultipliers(500_000, 1.075, 0.95);
        assertMultipliers(3_000_000, 0.925, 1.075);
        assertMultipliers(7_500_000, 0.775, 1.25);
    }

    @Test void crossingAThresholdByOneCoinDoesNotJumpPrices() {
        for (long boundary : new long[]{1_000_000, 5_000_000, 10_000_000}) {
            var before = config.stageFor(boundary - 1);
            var after = config.stageFor(boundary + 1);
            assertEquals(before.recycleMultiplier(), after.recycleMultiplier(), 0.000000001);
            assertEquals(before.shopMultiplier(), after.shopMultiplier(), 0.000000001);
        }
    }

    @Test void increasingTheBalanceKeepsTheIntendedDirectionAndBounds() {
        double previousRecycle = 1.15;
        double previousShop = 0.90;
        for (long total = 0; total <= 15_000_000; total += 100_000) {
            var stage = config.stageFor(total);
            assertTrue(stage.recycleMultiplier() <= previousRecycle + 0.000000001);
            assertTrue(stage.shopMultiplier() >= previousShop - 0.000000001);
            assertTrue(stage.recycleMultiplier() >= 0.70);
            assertTrue(stage.shopMultiplier() <= 1.35);
            previousRecycle = stage.recycleMultiplier();
            previousShop = stage.shopMultiplier();
        }
    }

    @Test void emptyAndSingleStageConfigurationsRemainUsable() {
        var empty = new EconomyBalanceConfig(true, 10, List.of());
        assertEquals(1, empty.stageFor(100).recycleMultiplier());
        assertEquals(1, empty.stageFor(100).shopMultiplier());
        var only = new EconomyBalanceConfig.Stage(-1, 0.9, 1.1);
        var single = new EconomyBalanceConfig(true, 10, List.of(only));
        assertEquals(only, single.stageFor(100));
        assertMultipliers(-1, 1.15, 0.90);
    }

    private void assertMultipliers(long total, double recycle, double shop) {
        var stage = config.stageFor(total);
        assertEquals(recycle, stage.recycleMultiplier(), 0.000000001);
        assertEquals(shop, stage.shopMultiplier(), 0.000000001);
    }
}
