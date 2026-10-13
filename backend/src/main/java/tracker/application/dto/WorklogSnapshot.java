package tracker.application.dto;

/** @param date YYYY-MM-DD the work was done */
public record WorklogSnapshot(UserSnapshot author, String date, double hours, String comment) {
}
