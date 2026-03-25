package net.scarletphantasy.gensouMarket.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class MarketListing {

    public enum Status { ACTIVE, SOLD, EXPIRED, CANCELLED }

    private int id;
    private UUID sellerUuid;
    private String sellerName;
    private ItemStack itemStack;
    private String itemData;
    private double price;
    private long listTime;
    private long expireTime;
    private Status status;
    private UUID buyerUuid;
    private String buyerName;

    public MarketListing() {
        this.status = Status.ACTIVE;
        this.listTime = System.currentTimeMillis();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public UUID getSellerUuid() { return sellerUuid; }
    public void setSellerUuid(UUID sellerUuid) { this.sellerUuid = sellerUuid; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    public ItemStack getItemStack() { return itemStack; }
    public void setItemStack(ItemStack itemStack) { this.itemStack = itemStack; }

    public String getItemData() { return itemData; }
    public void setItemData(String itemData) { this.itemData = itemData; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public long getListTime() { return listTime; }
    public void setListTime(long listTime) { this.listTime = listTime; }

    public long getExpireTime() { return expireTime; }
    public void setExpireTime(long expireTime) { this.expireTime = expireTime; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public UUID getBuyerUuid() { return buyerUuid; }
    public void setBuyerUuid(UUID buyerUuid) { this.buyerUuid = buyerUuid; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public boolean isExpired() {
        return status == Status.ACTIVE && System.currentTimeMillis() > expireTime;
    }
}
