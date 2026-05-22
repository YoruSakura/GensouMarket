package net.scarletphantasy.gensouMarket.model;

import org.bukkit.Material;

public class RecycleItem {

    private final String id;
    private final Material material;
    private double baseRecyclePrice;
    private int totalRecycled;
    private double recycleMultiplier;
    private long lastUpdate;
    // 回流库存（task-05）：回收成功后 +=，购买 recycled 模式商品后 -=
    private int recycledStock;
    // 运行时状态（不持久化），用于涨跌百分比计算
    private transient double snapshotPrice;
    private transient long snapshotTime;

    public RecycleItem(String id, Material material, double baseRecyclePrice) {
        this.id = id;
        this.material = material;
        this.baseRecyclePrice = baseRecyclePrice;
        this.totalRecycled = 0;
        this.recycleMultiplier = 1.0;
        this.recycledStock = 0;
        this.lastUpdate = System.currentTimeMillis();
    }

    public String getId() { return id; }

    public Material getMaterial() { return material; }

    public double getBaseRecyclePrice() { return baseRecyclePrice; }
    public void setBaseRecyclePrice(double price) { this.baseRecyclePrice = price; }

    public double getCurrentRecyclePrice() {
        return Math.round(baseRecyclePrice * recycleMultiplier * 100.0) / 100.0;
    }

    public double getCurrentRecyclePrice(double marketFluctuation) {
        return Math.round(baseRecyclePrice * recycleMultiplier * marketFluctuation * 100.0) / 100.0;
    }

    public int getTotalRecycled() { return totalRecycled; }
    public void setTotalRecycled(int totalRecycled) { this.totalRecycled = totalRecycled; }

    public double getRecycleMultiplier() { return recycleMultiplier; }
    public void setRecycleMultiplier(double m) { this.recycleMultiplier = m; }

    public long getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(long lastUpdate) { this.lastUpdate = lastUpdate; }

    public void addRecycled(int amount) { this.totalRecycled += amount; }

    public int getRecycledStock() { return recycledStock; }
    public void setRecycledStock(int recycledStock) { this.recycledStock = recycledStock; }

    public void addRecycledStock(int amount) { this.recycledStock += amount; }

    public double getSnapshotPrice() { return snapshotPrice; }
    public void setSnapshotPrice(double snapshotPrice) { this.snapshotPrice = snapshotPrice; }

    public long getSnapshotTime() { return snapshotTime; }
    public void setSnapshotTime(long snapshotTime) { this.snapshotTime = snapshotTime; }
}
