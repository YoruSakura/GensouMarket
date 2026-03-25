package net.scarletphantasy.gensouMarket.storage;

import net.scarletphantasy.gensouMarket.model.*;
import net.scarletphantasy.gensouMarket.util.ItemSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class YamlStorage implements StorageProvider {

    private final File dataFolder;
    private File listingsFile;
    private File auctionsFile;
    private File shopFile;
    private File recycleFile;
    private File mailFile;
    private YamlConfiguration listingsConfig;
    private YamlConfiguration auctionsConfig;
    private YamlConfiguration shopDataConfig;
    private YamlConfiguration recycleDataConfig;
    private YamlConfiguration mailConfig;
    private final AtomicInteger listingIdCounter = new AtomicInteger(0);
    private final AtomicInteger auctionIdCounter = new AtomicInteger(0);
    private final AtomicInteger mailIdCounter = new AtomicInteger(0);

    public YamlStorage(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    @Override
    public void initialize() throws Exception {
        File storageDir = new File(dataFolder, "data");
        storageDir.mkdirs();

        listingsFile = new File(storageDir, "listings.yml");
        auctionsFile = new File(storageDir, "auctions.yml");
        shopFile = new File(storageDir, "shop_data.yml");
        recycleFile = new File(storageDir, "recycle_data.yml");
        mailFile = new File(storageDir, "mail.yml");

        listingsConfig = YamlConfiguration.loadConfiguration(listingsFile);
        auctionsConfig = YamlConfiguration.loadConfiguration(auctionsFile);
        shopDataConfig = YamlConfiguration.loadConfiguration(shopFile);
        recycleDataConfig = YamlConfiguration.loadConfiguration(recycleFile);
        mailConfig = YamlConfiguration.loadConfiguration(mailFile);

        migrateShopToRecycle();

        listingIdCounter.set(listingsConfig.getInt("next-id", 1));
        auctionIdCounter.set(auctionsConfig.getInt("next-id", 1));
        mailIdCounter.set(mailConfig.getInt("next-id", 1));
    }

    @Override
    public void shutdown() {
        saveAllFiles();
    }

    private void saveAllFiles() {
        try {
            listingsConfig.save(listingsFile);
            auctionsConfig.save(auctionsFile);
            shopDataConfig.save(shopFile);
            recycleDataConfig.save(recycleFile);
            mailConfig.save(mailFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---- Market Listings ----

    @Override
    public int saveListing(MarketListing listing) {
        int id = listingIdCounter.getAndIncrement();
        listing.setId(id);
        String path = "listings." + id;
        listingsConfig.set(path + ".seller-uuid", listing.getSellerUuid().toString());
        listingsConfig.set(path + ".seller-name", listing.getSellerName());
        listingsConfig.set(path + ".item-data", listing.getItemData());
        listingsConfig.set(path + ".price", listing.getPrice());
        listingsConfig.set(path + ".list-time", listing.getListTime());
        listingsConfig.set(path + ".expire-time", listing.getExpireTime());
        listingsConfig.set(path + ".status", listing.getStatus().name());
        listingsConfig.set("next-id", listingIdCounter.get());
        saveFile(listingsConfig, listingsFile);
        return id;
    }

    @Override
    public void updateListing(MarketListing listing) {
        String path = "listings." + listing.getId();
        listingsConfig.set(path + ".status", listing.getStatus().name());
        if (listing.getBuyerUuid() != null) {
            listingsConfig.set(path + ".buyer-uuid", listing.getBuyerUuid().toString());
            listingsConfig.set(path + ".buyer-name", listing.getBuyerName());
        }
        saveFile(listingsConfig, listingsFile);
    }

    @Override
    public MarketListing getListing(int id) {
        String path = "listings." + id;
        if (!listingsConfig.contains(path)) return null;
        return mapListingFromYaml(id, listingsConfig.getConfigurationSection(path));
    }

    @Override
    public List<MarketListing> getActiveListings() {
        List<MarketListing> list = new ArrayList<>();
        ConfigurationSection section = listingsConfig.getConfigurationSection("listings");
        if (section == null) return list;
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec != null && "ACTIVE".equals(sec.getString("status"))) {
                list.add(mapListingFromYaml(Integer.parseInt(key), sec));
            }
        }
        list.sort((a, b) -> Long.compare(b.getListTime(), a.getListTime()));
        return list;
    }

    @Override
    public List<MarketListing> getPlayerListings(UUID playerUuid) {
        List<MarketListing> list = new ArrayList<>();
        ConfigurationSection section = listingsConfig.getConfigurationSection("listings");
        if (section == null) return list;
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec != null && playerUuid.toString().equals(sec.getString("seller-uuid"))
                    && "ACTIVE".equals(sec.getString("status"))) {
                list.add(mapListingFromYaml(Integer.parseInt(key), sec));
            }
        }
        return list;
    }

    private MarketListing mapListingFromYaml(int id, ConfigurationSection sec) {
        MarketListing l = new MarketListing();
        l.setId(id);
        l.setSellerUuid(UUID.fromString(sec.getString("seller-uuid")));
        l.setSellerName(sec.getString("seller-name"));
        l.setItemData(sec.getString("item-data"));
        l.setItemStack(ItemSerializer.deserialize(sec.getString("item-data")));
        l.setPrice(sec.getDouble("price"));
        l.setListTime(sec.getLong("list-time"));
        l.setExpireTime(sec.getLong("expire-time"));
        l.setStatus(MarketListing.Status.valueOf(sec.getString("status")));
        if (sec.contains("buyer-uuid")) {
            l.setBuyerUuid(UUID.fromString(sec.getString("buyer-uuid")));
            l.setBuyerName(sec.getString("buyer-name"));
        }
        return l;
    }

    // ---- Auctions ----

    @Override
    public int saveAuction(Auction auction) {
        int id = auctionIdCounter.getAndIncrement();
        auction.setId(id);
        String path = "auctions." + id;
        auctionsConfig.set(path + ".seller-uuid", auction.getSellerUuid().toString());
        auctionsConfig.set(path + ".seller-name", auction.getSellerName());
        auctionsConfig.set(path + ".item-data", auction.getItemData());
        auctionsConfig.set(path + ".starting-price", auction.getStartingPrice());
        auctionsConfig.set(path + ".current-price", auction.getCurrentPrice());
        auctionsConfig.set(path + ".start-time", auction.getStartTime());
        auctionsConfig.set(path + ".end-time", auction.getEndTime());
        auctionsConfig.set(path + ".status", auction.getStatus().name());
        auctionsConfig.set("next-id", auctionIdCounter.get());
        saveFile(auctionsConfig, auctionsFile);
        return id;
    }

    @Override
    public void updateAuction(Auction auction) {
        String path = "auctions." + auction.getId();
        auctionsConfig.set(path + ".current-price", auction.getCurrentPrice());
        auctionsConfig.set(path + ".status", auction.getStatus().name());
        if (auction.getHighestBidderUuid() != null) {
            auctionsConfig.set(path + ".highest-bidder-uuid", auction.getHighestBidderUuid().toString());
            auctionsConfig.set(path + ".highest-bidder-name", auction.getHighestBidderName());
        }
        saveFile(auctionsConfig, auctionsFile);
    }

    @Override
    public Auction getAuction(int id) {
        String path = "auctions." + id;
        if (!auctionsConfig.contains(path)) return null;
        return mapAuctionFromYaml(id, auctionsConfig.getConfigurationSection(path));
    }

    @Override
    public List<Auction> getActiveAuctions() {
        List<Auction> list = new ArrayList<>();
        ConfigurationSection section = auctionsConfig.getConfigurationSection("auctions");
        if (section == null) return list;
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec != null && "ACTIVE".equals(sec.getString("status"))) {
                list.add(mapAuctionFromYaml(Integer.parseInt(key), sec));
            }
        }
        list.sort((a, b) -> Long.compare(a.getEndTime(), b.getEndTime()));
        return list;
    }

    private Auction mapAuctionFromYaml(int id, ConfigurationSection sec) {
        Auction a = new Auction();
        a.setId(id);
        a.setSellerUuid(UUID.fromString(sec.getString("seller-uuid")));
        a.setSellerName(sec.getString("seller-name"));
        a.setItemData(sec.getString("item-data"));
        a.setItemStack(ItemSerializer.deserialize(sec.getString("item-data")));
        a.setStartingPrice(sec.getDouble("starting-price"));
        a.setCurrentPrice(sec.getDouble("current-price"));
        if (sec.contains("highest-bidder-uuid")) {
            a.setHighestBidderUuid(UUID.fromString(sec.getString("highest-bidder-uuid")));
            a.setHighestBidderName(sec.getString("highest-bidder-name"));
        }
        a.setStartTime(sec.getLong("start-time"));
        a.setEndTime(sec.getLong("end-time"));
        a.setStatus(Auction.Status.valueOf(sec.getString("status")));
        return a;
    }

    private void migrateShopToRecycle() {
        // 如果 recycle_data 已有数据则跳过
        ConfigurationSection recycleSection = recycleDataConfig.getConfigurationSection("items");
        if (recycleSection != null && !recycleSection.getKeys(false).isEmpty()) return;

        ConfigurationSection shopSection = shopDataConfig.getConfigurationSection("items");
        if (shopSection == null) return;

        int migrated = 0;
        for (String key : shopSection.getKeys(false)) {
            ConfigurationSection sec = shopSection.getConfigurationSection(key);
            if (sec == null) continue;
            double baseSellPrice = sec.getDouble("base-sell-price", 0);
            if (baseSellPrice <= 0) continue;

            String path = "items." + key;
            recycleDataConfig.set(path + ".material", sec.getString("material"));
            recycleDataConfig.set(path + ".base-recycle-price", baseSellPrice);
            recycleDataConfig.set(path + ".total-recycled", sec.getInt("total-sold", 0));
            recycleDataConfig.set(path + ".recycle-multiplier", sec.getDouble("sell-multiplier", 1.0));
            recycleDataConfig.set(path + ".last-update", sec.getLong("last-update", System.currentTimeMillis()));
            migrated++;
        }
        if (migrated > 0) {
            saveFile(recycleDataConfig, recycleFile);
            System.out.println("[GensouMarket] 已从 shop_data 迁移 " + migrated + " 个物品到 recycle_data");
        }
    }

    // ---- Shop Data ----

    @Override
    public void saveShopData(ShopItem item) {
        String path = "items." + item.getId();
        shopDataConfig.set(path + ".material", item.getMaterial().name());
        shopDataConfig.set(path + ".base-buy-price", item.getBaseBuyPrice());
        shopDataConfig.set(path + ".total-bought", item.getTotalBought());
        shopDataConfig.set(path + ".last-update", item.getLastUpdate());
        saveFile(shopDataConfig, shopFile);
    }

    @Override
    public void saveAllShopData(Map<String, ShopItem> items) {
        for (ShopItem item : items.values()) {
            String path = "items." + item.getId();
            shopDataConfig.set(path + ".material", item.getMaterial().name());
            shopDataConfig.set(path + ".base-buy-price", item.getBaseBuyPrice());
            shopDataConfig.set(path + ".total-bought", item.getTotalBought());
            shopDataConfig.set(path + ".last-update", item.getLastUpdate());
        }
        saveFile(shopDataConfig, shopFile);
    }

    @Override
    public Map<String, ShopItem> loadShopData() {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        ConfigurationSection section = shopDataConfig.getConfigurationSection("items");
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null) continue;
            Material mat = Material.matchMaterial(sec.getString("material"));
            if (mat == null) continue;
            ShopItem item = new ShopItem(key, mat, sec.getDouble("base-buy-price"));
            item.setTotalBought(sec.getInt("total-bought"));
            item.setLastUpdate(sec.getLong("last-update"));
            map.put(key, item);
        }
        return map;
    }

    // ---- Recycle Data ----

    @Override
    public void saveRecycleData(RecycleItem item) {
        String path = "items." + item.getId();
        recycleDataConfig.set(path + ".material", item.getMaterial().name());
        recycleDataConfig.set(path + ".base-recycle-price", item.getBaseRecyclePrice());
        recycleDataConfig.set(path + ".total-recycled", item.getTotalRecycled());
        recycleDataConfig.set(path + ".recycle-multiplier", item.getRecycleMultiplier());
        recycleDataConfig.set(path + ".last-update", item.getLastUpdate());
        saveFile(recycleDataConfig, recycleFile);
    }

    @Override
    public void saveAllRecycleData(Map<String, RecycleItem> items) {
        for (RecycleItem item : items.values()) {
            String path = "items." + item.getId();
            recycleDataConfig.set(path + ".material", item.getMaterial().name());
            recycleDataConfig.set(path + ".base-recycle-price", item.getBaseRecyclePrice());
            recycleDataConfig.set(path + ".total-recycled", item.getTotalRecycled());
            recycleDataConfig.set(path + ".recycle-multiplier", item.getRecycleMultiplier());
            recycleDataConfig.set(path + ".last-update", item.getLastUpdate());
        }
        saveFile(recycleDataConfig, recycleFile);
    }

    @Override
    public Map<String, RecycleItem> loadRecycleData() {
        Map<String, RecycleItem> map = new LinkedHashMap<>();
        ConfigurationSection section = recycleDataConfig.getConfigurationSection("items");
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null) continue;
            Material mat = Material.matchMaterial(sec.getString("material"));
            if (mat == null) continue;
            RecycleItem item = new RecycleItem(key, mat, sec.getDouble("base-recycle-price"));
            item.setTotalRecycled(sec.getInt("total-recycled"));
            item.setRecycleMultiplier(sec.getDouble("recycle-multiplier", 1.0));
            item.setLastUpdate(sec.getLong("last-update"));
            map.put(key, item);
        }
        return map;
    }

    // ---- Mail ----

    @Override
    public int saveMail(MailEntry entry) {
        int id = mailIdCounter.getAndIncrement();
        entry.setId(id);
        String path = "mail." + id;
        mailConfig.set(path + ".player-uuid", entry.getPlayerUuid().toString());
        mailConfig.set(path + ".item-data", entry.getItemData());
        mailConfig.set(path + ".money", entry.getMoney());
        mailConfig.set(path + ".message", entry.getMessage());
        mailConfig.set(path + ".timestamp", entry.getTimestamp());
        mailConfig.set(path + ".claimed", false);
        mailConfig.set("next-id", mailIdCounter.get());
        saveFile(mailConfig, mailFile);
        return id;
    }

    @Override
    public List<MailEntry> getPlayerMail(UUID playerUuid) {
        List<MailEntry> list = new ArrayList<>();
        ConfigurationSection section = mailConfig.getConfigurationSection("mail");
        if (section == null) return list;
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec != null && playerUuid.toString().equals(sec.getString("player-uuid"))
                    && !sec.getBoolean("claimed", false)) {
                MailEntry m = new MailEntry();
                m.setId(Integer.parseInt(key));
                m.setPlayerUuid(playerUuid);
                String itemData = sec.getString("item-data");
                m.setItemData(itemData);
                if (itemData != null) m.setItemStack(ItemSerializer.deserialize(itemData));
                m.setMoney(sec.getDouble("money"));
                m.setMessage(sec.getString("message"));
                m.setTimestamp(sec.getLong("timestamp"));
                m.setClaimed(false);
                list.add(m);
            }
        }
        list.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return list;
    }

    @Override
    public void deleteMail(int id) {
        mailConfig.set("mail." + id + ".claimed", true);
        saveFile(mailConfig, mailFile);
    }

    private void saveFile(YamlConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
