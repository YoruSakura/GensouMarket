package net.scarletphantasy.gensouMarket.upgrade.meta;

/**
 * 抽象元数据存储。支持 SQL (gensoumarket_meta 表) 和 YAML (upgrade-state.yml) 两种实现。
 */
public interface MetaStore {

    /** 初始化表或文件结构。 */
    void initialize() throws Exception;

    /** 读取 meta key，不存在返回 defaultValue。 */
    String get(String key, String defaultValue);

    /** 写入 meta key。 */
    void set(String key, String value);

    /** 读取 int 类型 meta key。 */
    default int getInt(String key, int defaultValue) {
        String val = get(key, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 写入 int 类型 meta key。 */
    default void setInt(String key, int value) {
        set(key, String.valueOf(value));
    }
}
