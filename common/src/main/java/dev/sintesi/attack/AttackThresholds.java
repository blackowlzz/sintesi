package dev.sintesi.attack;

public record AttackThresholds(int windowSeconds, int elevatedRate, int attackRate, long cooldownMillis) {

    public AttackThresholds {
        if (windowSeconds < 1 || windowSeconds > 16) {
            throw new IllegalArgumentException("windowSeconds");
        }
        if (elevatedRate < 1 || attackRate <= elevatedRate) {
            throw new IllegalArgumentException("rates");
        }
    }

    public static AttackThresholds defaults() {
        return new AttackThresholds(4, 40, 120, 30_000L);
    }
}
