package net.scarletphantasy.gensouMarket.listener;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.trade.TradeManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TradeInteractListener implements Listener {

    private final GensouMarket plugin;
    private final Map<UUID, Long> lastInteractTime = new HashMap<>();
    private static final long INTERACT_COOLDOWN_MS = 500;

    public TradeInteractListener(GensouMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) return;

        Player sender = event.getPlayer();

        // 必须蹲下
        if (!sender.isSneaking()) return;

        // 交易功能是否启用
        if (plugin.getConfigManager().isTradeEnabled()) return;

        // 防抖
        long now = System.currentTimeMillis();
        Long lastTime = lastInteractTime.get(sender.getUniqueId());
        if (lastTime != null && now - lastTime < INTERACT_COOLDOWN_MS) return;
        lastInteractTime.put(sender.getUniqueId(), now);

        TradeManager tradeManager = plugin.getTradeManager();
        if (tradeManager == null) return;

        // 如果目标已经向发起者发起了请求，则视为接受
        TradeManager.TradeRequest existingRequest =
                tradeManager.findRequestBySenderAndTarget(target.getUniqueId(), sender.getUniqueId());
        if (existingRequest != null) {
            tradeManager.acceptRequest(sender);
            return;
        }

        // 否则发起新请求
        tradeManager.sendRequest(sender, target);
    }
}
