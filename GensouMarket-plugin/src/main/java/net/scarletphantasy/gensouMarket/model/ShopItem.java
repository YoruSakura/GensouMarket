package net.scarletphantasy.gensouMarket.model;

import org.bukkit.Material;

public class ShopItem {

    public enum Mode { FIXED, RECYCLED }
    public enum StockMode { UNLIMITED, LIMITED }

    private final String id;
    private final Material material;
    private double baseBuyPrice;
    private int totalBought;
    private long lastUpdate;

    // 双模式相关（task-05）
    private Mode mode = Mode.FIXED;
    private StockMode stockMode = StockMode.UNLIMITED;
    // -1 表示尚未从配置初始化；>=0 表示持久化库存值（含 0 缺货）
    private int availableStock = -1;
    private String recycleSourceId;
    private double sellMultiplier = 1.0;

    public ShopItem(String id, Material material, double baseBuyPrice) {
        this.id = id;
        this.material = material;
        this.baseBuyPrice = baseBuyPrice;
        this.totalBought = 0;
        this.lastUpdate = System.currentTimeMillis();
    }

    public String getId() { return id; }

    public Material getMaterial() { return material; }

    public double getBaseBuyPrice() { return baseBuyPrice; }
    public void setBaseBuyPrice(double price) { this.baseBuyPrice = price; }

    public double getCurrentBuyPrice() {
        return Math.round(baseBuyPrice * 100.0) / 100.0;
    }

    public int getTotalBought() { return totalBought; }
    public void setTotalBought(int totalBought) { this.totalBought = totalBought; }

    public long getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(long lastUpdate) { this.lastUpdate = lastUpdate; }

    public void addBought(int amount) { this.totalBought += amount; }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public StockMode getStockMode() { return stockMode; }
    public void setStockMode(StockMode stockMode) { this.stockMode = stockMode; }

    public int getAvailableStock() { return availableStock; }
    public void setAvailableStock(int availableStock) { this.availableStock = availableStock; }

    public String getRecycleSourceId() { return recycleSourceId; }
    public void setRecycleSourceId(String recycleSourceId) { this.recycleSourceId = recycleSourceId; }

    public double getSellMultiplier() { return sellMultiplier; }
    public void setSellMultiplier(double sellMultiplier) { this.sellMultiplier = sellMultiplier; }

    public boolean isFixedUnlimited() {
        return mode == Mode.FIXED && stockMode == StockMode.UNLIMITED;
    }

    public boolean isFixedLimited() {
        return mode == Mode.FIXED && stockMode == StockMode.LIMITED;
    }

    public boolean isRecycled() {
        return mode == Mode.RECYCLED;
    }
}
