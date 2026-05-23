package net.scarletphantasy.gensouMarket.config;

import java.util.List;

public record EconomyBalanceConfig(
    boolean enabled,
    int snapshotIntervalMinutes,
    List<Stage> stages
) {
    public record Stage(
        long maxTotal,
        double recycleMultiplier,
        double shopMultiplier
    ) {}

    public Stage stageFor(long totalEconomyValue) {
        for (Stage stage : stages) {
            if (stage.maxTotal < 0 || totalEconomyValue <= stage.maxTotal) {
                return stage;
            }
        }
        return stages.isEmpty() ? new Stage(-1, 1.0, 1.0) : stages.getLast();
    }
}
