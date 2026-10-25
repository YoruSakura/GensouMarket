package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.config.PricingConfigResolver;
import net.scarletphantasy.gensouMarket.config.RecyclePricingConfig;
import net.scarletphantasy.gensouMarket.config.ShopPricingConfig;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BatchPurchasePricingTest {

    @Test void buyingAllLimitedStockIncludesTheIncreasingPrice() {
        var item = limitedItem();
        PriceResult quote = engine(true).calculateBatchSellPrice(item, null, limitedContext(64), 64);
        assertEquals(100, quote.unitPrice());
        assertEquals(7975.04, quote.totalPrice());
        assertEquals(124.61, quote.averagePrice());
        assertEquals(64, item.getAvailableStock(), "报价不能修改真实库存");
    }

    @Test void splittingAnOrderDoesNotChangeItsInventoryCost() {
        var engine = engine(true);
        var item = limitedItem();
        double whole = engine.calculateBatchSellPrice(item, null, limitedContext(64), 64).totalPrice();
        double first = engine.calculateBatchSellPrice(item, null, limitedContext(64), 32).totalPrice();
        double second = engine.calculateBatchSellPrice(item, null, limitedContext(32), 32).totalPrice();
        assertEquals(whole, first + second, 0.000001);
    }

    @Test void disablingDynamicPricingKeepsTheConfiguredPrice() {
        var quote = engine(false).calculateBatchSellPrice(limitedItem(), null, limitedContext(64), 64);
        assertEquals(6400, quote.totalPrice());
        assertEquals(100, quote.averagePrice());
    }

    @Test void largeOrdersCombineConstantPriceRangesWithoutIteratingOverEveryItem() {
        var engine = engine(true);
        var item = limitedItem();
        var context = limitedContext(Integer.MAX_VALUE);
        var quote = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> engine.calculateBatchSellPrice(item, null, context, Integer.MAX_VALUE));
        assertEquals((Integer.MAX_VALUE - 64L) * 100.0 + 7975.04, quote.totalPrice(), 0.001);
    }

    @Test void unlimitedStockDoesNotIntroduceScarcityPricing() {
        var item = new ShopItem("diamond", Material.DIAMOND, 100);
        var context = new PriceContext(0, 1, 1.2, 0, 0, 0, 0);
        var quote = engine(true).calculateBatchSellPrice(item, null, context, 64);
        assertEquals(7680, quote.totalPrice());
    }

    @Test void recycledPurchasesPriceEveryRemainingStockLevel() {
        var item = recycledItem();
        var source = new RecycleItem("diamond", Material.DIAMOND, 100);
        var context = new PriceContext(0, 1, 1, 4096, 4, 0, 0);
        var quote = engine(true).calculateBatchSellPrice(item, source, context, 4);
        // 库存 4、3、2、1 的单价为 85、97.50、110、122.50。
        assertEquals(415, quote.totalPrice());
        assertEquals(103.75, quote.averagePrice());
    }

    @Test void recycledPurchasesKeepTheAntiArbitrageFloorForEveryUnit() {
        var item = recycledItem();
        var source = new RecycleItem("diamond", Material.DIAMOND, 100);
        var context = new PriceContext(0, 1, 1, 0, 4, 0, 0);
        var quote = engine(true).calculateBatchSellPrice(item, source, context, 4);
        // 当前回收价 100：前三件至少 115，最后一件为 122.50。
        assertEquals(467.50, quote.totalPrice());
        assertTrue(quote.antiArbitrageApplied());
    }

    @Test void singlePurchaseMatchesTheExistingSingleQuote() {
        var engine = engine(true);
        var item = limitedItem();
        var context = limitedContext(32);
        var single = engine.calculateSellPrice(item, null, context);
        var batch = engine.calculateBatchSellPrice(item, null, context, 1);
        assertEquals(single.unitPrice(), batch.totalPrice());
        assertThrows(IllegalArgumentException.class,
                () -> engine.calculateBatchSellPrice(item, null, context, 0));
    }

    private static ShopItem limitedItem() {
        var item = new ShopItem("diamond", Material.DIAMOND, 100);
        item.setStockMode(ShopItem.StockMode.LIMITED);
        item.setAvailableStock(64);
        return item;
    }

    private static ShopItem recycledItem() {
        var item = new ShopItem("diamond", Material.DIAMOND, 100);
        item.setMode(ShopItem.Mode.RECYCLED);
        item.setRecycleSourceId("diamond");
        return item;
    }

    private static PriceContext limitedContext(int stock) {
        return new PriceContext(0, 1, 1, 0, 0, stock, 64);
    }

    private static PriceEngine engine(boolean dynamic) {
        var resolver = mock(PricingConfigResolver.class);
        when(resolver.resolveForRecycleItem(anyString())).thenReturn(new RecyclePricingConfig(
                dynamic, 120, 60, 4096, 0.5, 1.5, 24, 0, null, null));
        when(resolver.resolveForShopItem(anyString())).thenReturn(new ShopPricingConfig(
                dynamic, 0.8, 1.5, true, true, 1, 1.5,
                4, 0.85, 1.35, 1.15, null, null, null, 24, 0));
        return new PriceEngine(resolver);
    }
}
