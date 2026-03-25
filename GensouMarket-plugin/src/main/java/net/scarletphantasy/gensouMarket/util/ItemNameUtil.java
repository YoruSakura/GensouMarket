package net.scarletphantasy.gensouMarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 物品名称本地化工具。
 * 使用 Adventure Component.translatable()，客户端自动根据语言设置显示对应名称。
 * 如果物品有自定义名称（NBT/其他插件改名），优先使用自定义名称。
 */
public final class ItemNameUtil {

    private ItemNameUtil() {}

    /**
     * 获取物品的本地化显示名。
     * 自定义名称 > 原版翻译名。
     */
    public static Component getLocalizedName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Component.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.displayName();
        }
        return getLocalizedName(item.getType());
    }

    /**
     * 根据 Material 返回可翻译组件，客户端自动显示对应语言的名称。
     */
    public static Component getLocalizedName(Material material) {
        return Component.translatable(material.translationKey())
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
    }
}
