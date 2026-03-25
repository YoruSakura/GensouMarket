package net.scarletphantasy.gensouMarket.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class MailEntry {

    private int id;
    private UUID playerUuid;
    private ItemStack itemStack;
    private String itemData;
    private double money;
    private String message;
    private long timestamp;
    private boolean claimed;

    public MailEntry() {
        this.timestamp = System.currentTimeMillis();
        this.claimed = false;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public UUID getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }

    public ItemStack getItemStack() { return itemStack; }
    public void setItemStack(ItemStack itemStack) { this.itemStack = itemStack; }

    public String getItemData() { return itemData; }
    public void setItemData(String itemData) { this.itemData = itemData; }

    public double getMoney() { return money; }
    public void setMoney(double money) { this.money = money; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public boolean isClaimed() { return claimed; }
    public void setClaimed(boolean claimed) { this.claimed = claimed; }

    public boolean hasItem() { return itemStack != null || itemData != null; }
    public boolean hasMoney() { return money > 0; }
}
