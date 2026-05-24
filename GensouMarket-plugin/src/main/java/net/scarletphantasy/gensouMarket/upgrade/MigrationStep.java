package net.scarletphantasy.gensouMarket.upgrade;

/**
 * 迁移步骤接口。每一步对应一个数据版本跃迁。
 * <p>
 * 约束：
 * <ul>
 *   <li>可重复执行（幂等）。</li>
 *   <li>能检测自己是否已完成。</li>
 *   <li>不依赖业务 Manager 内存状态。</li>
 *   <li>失败抛出异常，不得只打印日志继续启动。</li>
 * </ul>
 */
public interface MigrationStep {

    /** 唯一标识。 */
    String id();

    /** 源数据版本（执行前的版本）。 */
    int fromDataVersion();

    /** 目标数据版本（执行后的版本）。 */
    int toDataVersion();

    /** 执行迁移。如果已完成则安全跳过。失败必须抛出异常。 */
    void migrate() throws Exception;
}
