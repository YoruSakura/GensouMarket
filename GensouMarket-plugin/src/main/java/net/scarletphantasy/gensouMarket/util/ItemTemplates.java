package net.scarletphantasy.gensouMarket.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** 保存完整服务端物品数据；数量不参与身份匹配。 */
public final class ItemTemplates {
    private ItemTemplates() {}

    public static ItemStack normalize(ItemStack stack) {
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        return copy;
    }

    public static String encode(ItemStack stack) {
        return Base64.getEncoder().encodeToString(normalize(stack).serializeAsBytes());
    }

    public static ItemStack decode(String data) {
        return normalize(ItemStack.deserializeBytes(Base64.getDecoder().decode(data)));
    }

    public static boolean matches(Material material, ItemStack template, ItemStack candidate) {
        if (candidate == null || candidate.getAmount() <= 0 || candidate.getType() != material) return false;
        return (template != null ? template : new ItemStack(material)).isSimilar(candidate);
    }

    public static ItemStack create(Material material, ItemStack template, int amount) {
        ItemStack result = template != null ? template.clone() : new ItemStack(material);
        result.setAmount(amount);
        return result;
    }
}
