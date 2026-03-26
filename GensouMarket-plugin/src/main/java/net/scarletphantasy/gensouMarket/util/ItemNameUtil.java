package net.scarletphantasy.gensouMarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

/**
 * 物品名称本地化工具。
 * 使用 Adventure Component.translatable()，客户端自动根据语言设置显示对应名称。
 */
public final class ItemNameUtil {

    private ItemNameUtil() {}

    /**
     * 根据 Material 返回可翻译组件，客户端自动显示对应语言的名称。
     */
    public static Component getLocalizedName(Material material) {
        return Component.translatable(material.translationKey())
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
    }
}
