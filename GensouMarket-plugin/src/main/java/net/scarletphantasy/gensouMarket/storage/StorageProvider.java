package net.scarletphantasy.gensouMarket.storage;

import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.model.PressureBucket;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StorageProvider {

    void initialize() throws Exception;

    void shutdown();

    // ---- Market Listings ----
    int saveListing(MarketListing listing);
    void updateListing(MarketListing listing);
    MarketListing getListing(int id);
    List<MarketListing> getActiveListings();
    List<MarketListing> getPlayerListings(UUID playerUuid);

    /**
     * 原子操作：将 ACTIVE 状态的上架物品标记为 SOLD。
     * SQL: UPDATE ... SET status='SOLD', buyer_uuid=?, buyer_name=? WHERE id=? AND status='ACTIVE'
     * @return true 表示抢占成功（affectedRows == 1）
     */
    boolean markListingSoldIfActive(int listingId, UUID buyerUuid, String buyerName);

    /**
     * 原子操作：将 ACTIVE 状态的上架物品标记为 EXPIRED。
     * @return true 表示成功
     */
    boolean markListingExpiredIfActive(int listingId);

    // ---- Auctions ----
    int saveAuction(Auction auction);
    void updateAuction(Auction auction);
    Auction getAuction(int id);
    List<Auction> getActiveAuctions();

    /**
     * 原子操作：当拍卖处于 ACTIVE 状态且当前价格匹配时，更新竞拍出价。
     * SQL: UPDATE ... SET current_price=?, highest_bidder_uuid=?, highest_bidder_name=?
     *      WHERE id=? AND status='ACTIVE' AND current_price=?
     * @param expectedCurrentPrice 期望的当前价格（乐观锁条件）
     * @return true 表示出价成功
     */
    boolean updateAuctionBidIfMatch(int auctionId, double expectedCurrentPrice,
                                     double newPrice, UUID newBidderUuid, String newBidderName);

    /**
     * 原子操作：将 ACTIVE 状态的拍卖标记为 ENDED。
     * SQL: UPDATE ... SET status='ENDED' WHERE id=? AND status='ACTIVE'
     * @return true 表示抢占结算权成功
     */
    boolean markAuctionEndedIfActive(int auctionId);

    /**
     * 原子操作：将 ACTIVE 状态的拍卖标记为 CANCELLED。
     * @return true 表示成功
     */
    boolean markAuctionCancelledIfActive(int auctionId);

    // ---- Shop Data ----
    void saveShopData(ShopItem item);
    void saveAllShopData(Map<String, ShopItem> items);
    Map<String, ShopItem> loadShopData();

    // ---- Recycle Data ----
    void saveRecycleData(RecycleItem item);
    void saveAllRecycleData(Map<String, RecycleItem> items);
    Map<String, RecycleItem> loadRecycleData();

    // ---- Pressure Data (v1.1.1) ----

    /**
     * 保存或更新一个压力桶。如果 (item_id, bucket_start) 已存在则覆盖 amount 和 updated_at。
     */
    void savePressureBucket(PressureBucket bucket);

    /**
     * 批量保存压力桶（全量覆盖指定物品的所有桶）。
     */
    void saveAllPressureBuckets(String itemId, List<PressureBucket> buckets);

    /**
     * 原子增加压力桶的数量（增量更新，防止并发覆盖）。
     */
    void addPressureAmount(String itemId, long bucketStart, int amountDelta, long now);

    /**
     * 加载指定物品的所有压力桶。
     */
    List<PressureBucket> loadPressureBuckets(String itemId);

    /**
     * 加载所有物品的所有压力桶，按 item_id 分组。
     */
    Map<String, List<PressureBucket>> loadAllPressureBuckets();

    /**
     * 删除指定物品在 cutoffTime 之前的过期桶。
     */
    void deleteExpiredPressureBuckets(String itemId, long cutoffTime);

    // ---- Mail ----
    int saveMail(MailEntry entry);
    List<MailEntry> getPlayerMail(UUID playerUuid);
    boolean claimMail(int id);
    boolean unclaimMail(int id);
    void deleteMail(int id);
}
