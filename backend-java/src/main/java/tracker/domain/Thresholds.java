package tracker.domain;

public record Thresholds(double soonDays, double effortWarnPct, double heldDays, double reviewRounds, double capacityPct) {

    public static Thresholds defaults() {
        return new Thresholds(2, 80, 3, 3, 100);
    }
}
