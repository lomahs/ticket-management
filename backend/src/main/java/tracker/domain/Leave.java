package tracker.domain;

public record Leave(int id, String memberId, String date, LeaveType type, String note) {

    public Leave withId(int newId) {
        return new Leave(newId, memberId, date, type, note);
    }
}
