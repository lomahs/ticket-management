package tracker.domain;

import java.util.List;

public record WorkingHours(double hoursPerDay, List<Integer> days, double timesheetToleranceH) {

    public WorkingHours {
        days = List.copyOf(days);
    }

    public static WorkingHours defaults() {
        return new WorkingHours(8, List.of(1, 2, 3, 4, 5), 1);
    }
}
