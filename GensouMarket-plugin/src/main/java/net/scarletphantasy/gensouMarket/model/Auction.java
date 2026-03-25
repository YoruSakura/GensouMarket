package net.scarletphantasy.gensouMarket.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class Auction {

    public enum Status { ACTIVE, ENDED, CANCELLED }

    private int id;
    private UUID sellerUuid;
    private String sellerName;
    private ItemStack itemStack;
    private String itemData;
    private double startingPrice;
    private double currentPrice;
    private UUID highestBidderUuid;
    private String highestBidderName;
    private long startTime;
    private long endTime;
    private Status status;

    public Auction() {
        this.status = Status.ACTIVE;
        this.startTime = System.currentTimeMillis();
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

    public double getStartingPrice() { return startingPrice; }
    public void setStartingPrice(double startingPrice) { this.startingPrice = startingPrice; }

    public double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }

    public UUID getHighestBidderUuid() { return highestBidderUuid; }
    public void setHighestBidderUuid(UUID uuid) { this.highestBidderUuid = uuid; }

    public String getHighestBidderName() { return highestBidderName; }
    public void setHighestBidderName(String name) { this.highestBidderName = name; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public boolean hasEnded() {
        return status == Status.ACTIVE && System.currentTimeMillis() > endTime;
    }

    public boolean hasBidder() {
        return highestBidderUuid != null;
    }
}
