package net.scarletphantasy.gensouMarket.upgrade;

/**
 * 集中管理所有版本常量。禁止在其他类中散落魔法数字。
 */
public final class VersionRegistry {

    private VersionRegistry() {}

    // ---- Data versions (schema + runtime state) ----
    public static final int DATA_VERSION_1_1_1 = 4;
    public static final int DATA_VERSION_1_1_2 = 5;

    // ---- Config versions ----
    public static final int CONFIG_VERSION_1_1_1 = 3;
    public static final int CONFIG_VERSION_1_1_2 = 4;

    // ---- Current plugin supported versions ----
    public static final int CURRENT_DATA_VERSION = DATA_VERSION_1_1_2;
    public static final int CURRENT_CONFIG_VERSION = CONFIG_VERSION_1_1_2;

    /** 当前插件版本字符串（与 build.gradle 保持同步） */
    public static final String PLUGIN_VERSION = "1.1.2-dev";
}
