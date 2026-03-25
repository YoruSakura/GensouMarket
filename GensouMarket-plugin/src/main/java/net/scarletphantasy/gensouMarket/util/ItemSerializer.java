package net.scarletphantasy.gensouMarket.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class ItemSerializer {

    private ItemSerializer() {}

    public static String serialize(ItemStack itemStack) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("item", itemStack);
        return config.saveToString();
    }

    public static ItemStack deserialize(String data) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(data);
            return config.getItemStack("item");
        } catch (Exception e) {
            return null;
        }
    }
}
