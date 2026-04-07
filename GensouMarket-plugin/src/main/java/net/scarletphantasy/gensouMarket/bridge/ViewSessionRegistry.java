package net.scarletphantasy.gensouMarket.bridge;

import net.scarletphantasy.gensouMarket.gui.GuiHolder;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪本服玩家正在查看的 GUI 类型和关联 ID。
 * 用于跨服事件到达时，判断哪些玩家需要刷新界面。
 */
public class ViewSessionRegistry {

    private record ViewSession(GuiHolder.GuiType guiType, int relatedId) {}

    // playerUuid -> ViewSession
    private final Map<UUID, ViewSession> sessions = new ConcurrentHashMap<>();

    /**
     * 记录玩家正在查看的 GUI。
     */
    public void register(UUID playerUuid, GuiHolder.GuiType guiType, int relatedId) {
        sessions.put(playerUuid, new ViewSession(guiType, relatedId));
    }

    /**
     * 玩家关闭 GUI 时移除。
     */
    public void unregister(UUID playerUuid) {
        sessions.remove(playerUuid);
    }

    /**
     * 查找正在查看市场列表的玩家。
     */
    public java.util.List<UUID> getMarketBrowseViewers() {
        return getViewersByType(GuiHolder.GuiType.MARKET_BROWSE);
    }

    /**
     * 查找正在查看拍卖列表的玩家。
     */
    public java.util.List<UUID> getAuctionListViewers() {
        return getViewersByType(GuiHolder.GuiType.AUCTION_LIST);
    }

    /**
     * 查找正在查看特定拍卖详情的玩家。
     */
    public java.util.List<UUID> getAuctionDetailViewers(int auctionId) {
        java.util.List<UUID> result = new java.util.ArrayList<>();
        for (Map.Entry<UUID, ViewSession> entry : sessions.entrySet()) {
            ViewSession session = entry.getValue();
            if (session.guiType == GuiHolder.GuiType.AUCTION_DETAIL && session.relatedId == auctionId) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private java.util.List<UUID> getViewersByType(GuiHolder.GuiType type) {
        java.util.List<UUID> result = new java.util.ArrayList<>();
        for (Map.Entry<UUID, ViewSession> entry : sessions.entrySet()) {
            if (entry.getValue().guiType == type) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
}
