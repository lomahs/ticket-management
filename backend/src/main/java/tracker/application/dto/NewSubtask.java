package tracker.application.dto;

public record NewSubtask(String summary, String assigneeAccountId, String plannedStart, String plannedEnd, double estimateH) {
}
