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
        long lowerBound = 0;
        long total = Math.max(totalEconomyValue, 0);
        for (int i = 0; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            if (stage.maxTotal < 0 || i == stages.size() - 1) return stage;
            if (total <= stage.maxTotal) {
                if (stage.maxTotal <= lowerBound) return stage;
                Stage next = stages.get(i + 1);
                double progress = (double) (total - lowerBound) / (stage.maxTotal - lowerBound);
                // 本档下界取本档倍率，上界取下一档倍率，跨档时数值和斜率连续。
                double weight = progress * progress * (3.0 - 2.0 * progress);
                return new Stage(stage.maxTotal,
                        stage.recycleMultiplier + (next.recycleMultiplier - stage.recycleMultiplier) * weight,
                        stage.shopMultiplier + (next.shopMultiplier - stage.shopMultiplier) * weight);
            }
            lowerBound = Math.max(lowerBound, stage.maxTotal);
        }
        return stages.isEmpty() ? new Stage(-1, 1.0, 1.0) : stages.getLast();
    }
}
