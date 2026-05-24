package net.scarletphantasy.gensouMarket.upgrade.meta;

/**
 * 抽象元数据存储。支持 SQL (gensoumarket_meta 表) 和 YAML (upgrade-state.yml) 两种实现。
 */
public interface MetaStore {

    /** 初始化表或文件结构。 */
    void initialize() throws Exception;

    /** 读取 meta key，不存在返回 defaultValue。 */
    String get(String key, String defaultValue);

    /** 写入 meta key（尽力而为，失败只打印 warning）。 */
    void set(String key, String value);

    /** 严格写入 meta key，失败抛出异常。用于关键版本字段。 */
    void setStrict(String key, String value) throws Exception;

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

    /** 写入 int 类型 meta key（尽力而为）。 */
    default void setInt(String key, int value) {
        set(key, String.valueOf(value));
    }

    /** 严格写入 int 类型 meta key，失败抛出异常。 */
    default void setIntStrict(String key, int value) throws Exception {
        setStrict(key, String.valueOf(value));
    }
}
