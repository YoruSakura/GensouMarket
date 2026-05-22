package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.Sign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 个人商店牌子持久化与查询服务（task-02）。
 * 独立使用 personal-shop-signs.yml 文件，不写入共享 DB。
 * 支撑方块在 register 时根据当前 BlockData 推断，反向索引一份用于保护查询。
 */
public class PersonalShopSignManager {

    private final GensouMarket plugin;
    private final File file;
    private YamlConfiguration config;

    // signKey -> binding
    private final Map<String, SignBinding> bindings = new ConcurrentHashMap<>();
    // supportKey -> signKey（保护支撑方块用）
    private final Map<String, String> supportIndex = new ConcurrentHashMap<>();

    public PersonalShopSignManager(GensouMarket plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "personal-shop-signs.yml");
    }

    public void load() {
        if (!file.exists()) {
            try {
                if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                    plugin.getLogger().warning("[PersonalShopSign] 无法创建插件数据目录");
                }
                if (!file.createNewFile()) {
                    // 已存在则继续
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "[PersonalShopSign] 创建 personal-shop-signs.yml 失败", e);
            }
        }
        config = YamlConfiguration.loadConfiguration(file);

        bindings.clear();
        supportIndex.clear();

        ConfigurationSection section = config.getConfigurationSection("signs");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null) continue;
            String uuidStr = sec.getString("owner-uuid");
            String name = sec.getString("owner-name");
            long createdAt = sec.getLong("created-at", System.currentTimeMillis());
            if (uuidStr == null || name == null) continue;
            UUID owner;
            try {
                owner = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            SignBinding binding = new SignBinding(owner, name, createdAt, key);
            bindings.put(key, binding);
            // 支撑方块索引：启动时需要实际世界读取 BlockData，惰性推断
            reindexSupport(binding);
        }
    }

    private void saveAll() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[PersonalShopSign] 保存 personal-shop-signs.yml 失败", e);
        }
    }

    public static String keyOf(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public static String keyOf(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    public boolean isSignRegistered(Block block) {
        return bindings.containsKey(keyOf(block));
    }

    public Optional<SignBinding> getBindingForSign(Block block) {
        return Optional.ofNullable(bindings.get(keyOf(block)));
    }

    /**
     * 返回受保护块对应的牌子绑定。block 本身是牌子或是其支撑方块都算受保护。
     */
    public Optional<SignBinding> getProtectingBinding(Block block) {
        String key = keyOf(block);
        SignBinding direct = bindings.get(key);
        if (direct != null) return Optional.of(direct);
        String signKey = supportIndex.get(key);
        if (signKey != null) {
            SignBinding viaSupport = bindings.get(signKey);
            if (viaSupport != null) return Optional.of(viaSupport);
            // 反向索引与绑定不一致，惰性清理
            supportIndex.remove(key);
        }
        return Optional.empty();
    }

    /**
     * 注册一个牌子。若已存在则拒绝（避免误覆盖）。
     */
    public synchronized boolean register(Block signBlock, UUID ownerUuid, String ownerName) {
        String key = keyOf(signBlock);
        if (bindings.containsKey(key)) return false;
        long now = System.currentTimeMillis();
        SignBinding binding = new SignBinding(ownerUuid, ownerName, now, key);
        bindings.put(key, binding);
        // 写入持久化
        String path = "signs." + key;
        config.set(path + ".owner-uuid", ownerUuid.toString());
        config.set(path + ".owner-name", ownerName);
        config.set(path + ".created-at", now);
        saveAll();
        // 支撑方块索引
        Location support = computeSupportLocation(signBlock);
        if (support != null) {
            supportIndex.put(keyOf(support), key);
        }
        return true;
    }

    public synchronized boolean unregister(Block signBlock) {
        return unregisterKey(keyOf(signBlock));
    }

    public synchronized boolean unregisterKey(String key) {
        SignBinding removed = bindings.remove(key);
        if (removed == null) return false;
        config.set("signs." + key, null);
        saveAll();
        // 反向索引清理
        supportIndex.entrySet().removeIf(e -> e.getValue().equals(key));
        return true;
    }

    /**
     * 重建支撑方块反向索引。启动时每条绑定调用一次；若世界未加载则跳过（惰性）。
     */
    private void reindexSupport(SignBinding binding) {
        String[] parts = binding.signKey().split(":");
        if (parts.length != 4) return;
        World world = plugin.getServer().getWorld(parts[0]);
        if (world == null) return;
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            Block block = world.getBlockAt(x, y, z);
            Location support = computeSupportLocation(block);
            if (support != null) {
                supportIndex.put(keyOf(support), binding.signKey());
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /**
     * 推断给定牌子方块的支撑方块位置。分四类处理：
     * - WallSign:         支撑在附着面的反方向
     * - Sign (standing):  支撑在下方
     * - WallHangingSign:  支撑在附着面的反方向
     * - HangingSign:      支撑在上方
     * 其他 BlockData 或非牌子方块返回 null。
     */
    public static Location computeSupportLocation(Block signBlock) {
        if (signBlock == null) return null;
        Material mat = signBlock.getType();
        if (!mat.name().endsWith("_SIGN")) {
            return null;
        }
        BlockData data = signBlock.getBlockData();
        BlockFace face;
        if (data instanceof WallHangingSign wallHanging) {
            face = wallHanging.getFacing().getOppositeFace();
        } else if (data instanceof HangingSign) {
            face = BlockFace.UP;
        } else if (data instanceof WallSign wallSign) {
            face = wallSign.getFacing().getOppositeFace();
        } else if (data instanceof Sign) {
            face = BlockFace.DOWN;
        } else {
            return null;
        }
        return signBlock.getRelative(face).getLocation();
    }

    /**
     * 牌子被放置后立即确认支撑方块索引。供 Listener 在 register 之后调用。
     */
    public void refreshSupportFor(Block signBlock) {
        String key = keyOf(signBlock);
        if (!bindings.containsKey(key)) return;
        Location support = computeSupportLocation(signBlock);
        if (support != null) {
            supportIndex.put(keyOf(support), key);
        }
    }

    public record SignBinding(UUID ownerUuid, String ownerName, long createdAt, String signKey) {}
}
