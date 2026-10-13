package tracker.domain;

public record Worklog(String date, String memberId, double hours, String note) {

    public Worklog(String date, String memberId, double hours) {
        this(date, memberId, hours, "");
    }
}
