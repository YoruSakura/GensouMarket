package net.scarletphantasy.gensouMarket.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.gui.MarketGui;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.shop.PersonalShopSignManager;
import net.scarletphantasy.gensouMarket.shop.PersonalShopSignManager.SignBinding;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 个人商店牌子事件处理（task-02）。
 * - SignChangeEvent: 识别创建请求、鉴权、注册、重写牌面
 * - PlayerInteractEvent: 右键注册牌子打开店主个人商店
 * - BlockBreakEvent: 保护牌子与支撑方块
 * - BlockExplode/EntityExplode: 从受影响列表中剔除牌子与支撑方块
 * - BlockPistonExtend/Retract: 若涉及牌子或支撑方块则取消
 */
public class PersonalShopSignListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final GensouMarket plugin;
    private final PersonalShopSignManager signManager;

    public PersonalShopSignListener(GensouMarket plugin, PersonalShopSignManager signManager) {
        this.plugin = plugin;
        this.signManager = signManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Component firstLineComponent = event.line(0);
        if (firstLineComponent == null) return;
        String first = PLAIN.serialize(firstLineComponent).trim();
        if (!first.equalsIgnoreCase("[个人商店]") && !first.equalsIgnoreCase("[gmarketshop]")) {
            return;
        }

        // 若其他插件（区域保护等）已取消本次放牌，则不应落绑定数据
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();

        if (!plugin.getConfigManager().isPersonalShopEnabled()) {
            MessageUtil.send(player, "&c个人商店功能未启用，无法创建牌子");
            event.setCancelled(true);
            return;
        }

        boolean isAdmin = player.hasPermission("gensoumarket.admin");
        if (!isAdmin && !player.hasPermission("gensoumarket.personal.sign.create")) {
            MessageUtil.send(player, "&c你没有权限创建个人商店牌子");
            event.setCancelled(true);
            return;
        }

        Block block = event.getBlock();
        // 创建：本期管理员也默认绑定自己
        boolean registered = signManager.register(block, player.getUniqueId(), player.getName());
        if (!registered) {
            MessageUtil.send(player, "&c该位置已有个人商店牌子，创建失败");
            event.setCancelled(true);
            return;
        }
        signManager.refreshSupportFor(block);

        // 统一重写牌面
        event.line(0, LEGACY.deserialize("&6[个人商店]"));
        event.line(1, Component.text(player.getName(), NamedTextColor.WHITE));
        event.line(2, LEGACY.deserialize("&7右键打开"));
        event.line(3, Component.empty());

        MessageUtil.send(player, "&a已创建个人商店牌子！右键可打开你的商店");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 只处理主手右键方块
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Optional<SignBinding> opt = signManager.getBindingForSign(block);
        if (opt.isEmpty()) return;

        // 抢先于普通牌子编辑行为
        event.setCancelled(true);

        Player viewer = event.getPlayer();
        if (!plugin.getConfigManager().isPersonalShopEnabled()) {
            MessageUtil.send(viewer, "&c个人商店功能未启用");
            return;
        }

        SignBinding binding = opt.get();
        openForOwner(viewer, binding.ownerUuid(), binding.ownerName());
    }

    private void openForOwner(Player viewer, UUID ownerUuid, String ownerName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketListing> all = plugin.getStorage().getPlayerListings(ownerUuid);
            List<MarketListing> active = new ArrayList<>();
            for (MarketListing l : all) {
                if (l.getStatus() == MarketListing.Status.ACTIVE) active.add(l);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                if (active.isEmpty()) {
                    if (viewer.getUniqueId().equals(ownerUuid)) {
                        MessageUtil.send(viewer, "&e你当前没有在售物品！");
                    } else {
                        MessageUtil.send(viewer, "&e" + ownerName + " 当前没有在售物品！");
                    }
                    return;
                }
                MarketGui.openPersonalShop(plugin, viewer, ownerUuid, ownerName, active, 0);
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        Optional<SignBinding> opt = signManager.getProtectingBinding(broken);
        if (opt.isEmpty()) return;

        Player player = event.getPlayer();
        SignBinding binding = opt.get();
        boolean isOwner = binding.ownerUuid().equals(player.getUniqueId());
        boolean isAdmin = player.hasPermission("gensoumarket.admin");
        if (!isOwner && !isAdmin) {
            event.setCancelled(true);
            MessageUtil.send(player, "&c无权破坏该个人商店牌子或其支撑方块");
            return;
        }

        // 先解绑，再放行破坏
        signManager.unregisterKey(binding.signKey());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    private void filterExplosion(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (signManager.getProtectingBinding(b).isPresent()) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (affectsProtected(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (affectsProtected(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean affectsProtected(List<Block> blocks) {
        for (Block b : blocks) {
            if (signManager.getProtectingBinding(b).isPresent()) return true;
        }
        return false;
    }
}
