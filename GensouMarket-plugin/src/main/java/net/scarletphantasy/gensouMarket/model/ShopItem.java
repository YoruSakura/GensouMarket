package net.scarletphantasy.gensouMarket.model;

import org.bukkit.Material;

public class ShopItem {

    private final String id;
    private final Material material;
    private double baseBuyPrice;
    private int totalBought;
    private long lastUpdate;

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
}
