package net.scarletphantasy.gensouMarket.config;

import net.scarletphantasy.gensouMarket.util.ConfigFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PricingGroupStore {
    private static final Map<String, Set<String>> BOOLEAN_KEYS = Map.of(
            "recycle", Set.of("dynamic-enabled"),
            "shop", Set.of("dynamic-enabled", "fixed-dynamic-enabled", "recycled-dynamic-enabled"));
    private static final Map<String, Set<String>> INTEGER_KEYS = Map.of(
            "recycle", Set.of("pressure-window-minutes", "bucket-seconds", "min-price-volume"),
            "shop", Set.of("pricing-reference-stock", "target-recycled-stock"));
    private static final Map<String, Set<String>> DOUBLE_KEYS = Map.of(
            "recycle", Set.of("min-multiplier", "max-multiplier", "cycle-period-hours", "cycle-amplitude",
                    "min-price", "max-price"),
            "shop", Set.of("min-sell-multiplier", "max-sell-multiplier", "min-stock-multiplier",
                    "max-stock-multiplier", "min-recycled-stock-multiplier", "max-recycled-stock-multiplier",
                    "anti-arbitrage-multiplier", "min-sell-price", "max-sell-price",
                    "cycle-period-hours", "cycle-amplitude"));

    private final Path directory;
    private final Logger logger;
    private final Map<String, YamlConfiguration> groups = new LinkedHashMap<>();

    public PricingGroupStore(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,63}");
    }

    public void load(boolean enabled) {
        groups.clear();
        if (!enabled) return;
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".yml") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    String id = name.substring(0, name.length() - 4);
                    if (!validId(id)) {
                        logger.warning("定价分组文件名无效，已跳过: " + name);
                        continue;
                    }
                    try {
                        var config = new YamlConfiguration();
                        config.load(file.toFile());
                        groups.put(id, config);
                    } catch (Exception ex) {
                        logger.log(Level.WARNING, "无法读取定价分组 " + name + "，已跳过", ex);
                    }
                }
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "无法读取定价分组目录 " + directory, ex);
        }
    }

    public List<String> ids() { return List.copyOf(groups.keySet()); }
    public ConfigurationSection get(String id) { return groups.get(id); }

    public void create(String id) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("组名只能使用小写字母、数字、下划线和短横线，最多 64 个字符");
        if (groups.containsKey(id) || Files.exists(directory.resolve(id + ".yml"))) {
            throw new IllegalArgumentException("该组已存在，或同名文件无法加载，请检查文件");
        }
        var config = new YamlConfiguration();
        config.createSection("recycle");
        config.createSection("shop");
        ConfigFiles.save(config, directory.resolve(id + ".yml"));
        groups.put(id, config);
    }

    public void delete(String id) throws IOException {
        requireGroup(id);
        Files.delete(directory.resolve(id + ".yml"));
        groups.remove(id);
    }

    public void set(String id, String module, String key, String value) throws IOException {
        var current = requireGroup(id);
        if (!parameters(module).contains(key)) throw new IllegalArgumentException("未知的定价参数，请使用 Tab 查看可用参数");
        Object parsed = value.equalsIgnoreCase("default") ? null : parseValue(module, key, value);
        var updated = new YamlConfiguration();
        try {
            updated.loadFromString(current.saveToString());
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IOException("无法复制定价分组", ex);
        }
        updated.set(module + "." + key, parsed);
        ConfigFiles.save(updated, directory.resolve(id + ".yml"));
        groups.put(id, updated);
    }

    public static List<String> parameters(String module) {
        return java.util.stream.Stream.of(BOOLEAN_KEYS.getOrDefault(module, Set.of()),
                        INTEGER_KEYS.getOrDefault(module, Set.of()), DOUBLE_KEYS.getOrDefault(module, Set.of()))
                .flatMap(Set::stream).sorted().toList();
    }

    public static List<String> valueSuggestions(String module, String key) {
        return BOOLEAN_KEYS.getOrDefault(module, Set.of()).contains(key)
                ? List.of("default", "true", "false") : List.of("default");
    }

    private YamlConfiguration requireGroup(String id) {
        if (!validId(id) || !groups.containsKey(id)) throw new IllegalArgumentException("未找到该组");
        return groups.get(id);
    }

    private static Object parseValue(String module, String key, String value) {
        if (BOOLEAN_KEYS.get(module).contains(key)) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("开关值必须为 true 或 false");
            }
            return Boolean.parseBoolean(value);
        }
        try {
            if (INTEGER_KEYS.get(module).contains(key)) {
                int number = Integer.parseInt(value);
                if (number < 0 || (number == 0 && !key.equals("min-price-volume"))) {
                    throw new IllegalArgumentException("该参数必须为正整数（min-price-volume 可为 0）");
                }
                return number;
            }
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number) || number < 0 || (key.equals("cycle-amplitude") && number > 1)
                    || (key.equals("cycle-period-hours") && number == 0)
                    || (key.equals("anti-arbitrage-multiplier") && number < 1)) {
                throw new IllegalArgumentException("参数超出范围：数值不得为负，振幅为 0–1，周期须大于 0，防套利倍率至少为 1");
            }
            return number;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("无效的数值", ex);
        }
    }
}
