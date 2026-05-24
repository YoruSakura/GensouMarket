package net.scarletphantasy.gensouMarket.upgrade;

import java.util.Collections;
import java.util.List;

/**
 * 迁移执行结果。
 */
public record MigrationResult(
        boolean success,
        int dataVersionBefore,
        int dataVersionAfter,
        int configVersionBefore,
        int configVersionAfter,
        List<String> executedSteps,
        List<String> blockReasons,
        String errorMessage
) {
    public static MigrationResult success(int dataBefore, int dataAfter,
                                           int configBefore, int configAfter,
                                           List<String> steps) {
        return new MigrationResult(true, dataBefore, dataAfter,
                configBefore, configAfter, steps, Collections.emptyList(), null);
    }

    public static MigrationResult blocked(int dataBefore, int dataAfter,
                                           int configBefore, int configAfter,
                                           List<String> reasons) {
        return new MigrationResult(false, dataBefore, dataAfter,
                configBefore, configAfter, Collections.emptyList(), reasons, null);
    }

    public static MigrationResult failed(int dataBefore, int configBefore,
                                          List<String> steps, String error) {
        return new MigrationResult(false, dataBefore, dataBefore,
                configBefore, configBefore, steps, Collections.emptyList(), error);
    }
}
