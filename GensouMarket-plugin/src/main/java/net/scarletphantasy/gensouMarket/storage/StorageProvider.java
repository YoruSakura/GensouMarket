package net.scarletphantasy.gensouMarket.storage;

import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.model.MarketListing;
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

    // ---- Auctions ----
    int saveAuction(Auction auction);
    void updateAuction(Auction auction);
    Auction getAuction(int id);
    List<Auction> getActiveAuctions();

    // ---- Shop Data ----
    void saveShopData(ShopItem item);
    void saveAllShopData(Map<String, ShopItem> items);
    Map<String, ShopItem> loadShopData();

    // ---- Recycle Data ----
    void saveRecycleData(RecycleItem item);
    void saveAllRecycleData(Map<String, RecycleItem> items);
    Map<String, RecycleItem> loadRecycleData();

    // ---- Mail ----
    int saveMail(MailEntry entry);
    List<MailEntry> getPlayerMail(UUID playerUuid);
    void deleteMail(int id);
}
