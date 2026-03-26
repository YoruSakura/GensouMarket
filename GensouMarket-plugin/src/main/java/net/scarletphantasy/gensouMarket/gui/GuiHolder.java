package net.scarletphantasy.gensouMarket.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class GuiHolder implements InventoryHolder {

    public enum GuiType {
        MAIN_MENU, MARKET_BROWSE, SHOP, RECYCLE, AUCTION_LIST, AUCTION_DETAIL, TRADE
    }

    private final GuiType type;
    private final Map<String, Object> data = new HashMap<>();
    private Inventory inventory;

    public GuiHolder(GuiType type) {
        this.type = type;
    }

    public GuiType getType() { return type; }

    public void setData(String key, Object value) { data.put(key, value); }
    public Object getData(String key) { return data.get(key); }
    public int getIntData(String key, int def) {
        Object v = data.get(key);
        return v instanceof Integer ? (int) v : def;
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
}
