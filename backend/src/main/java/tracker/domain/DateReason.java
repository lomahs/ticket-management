package tracker.domain;

/** A day and why it is off ("weekend", a holiday name or a leave); the API writes it as a [date, reason] pair. */
public record DateReason(String date, String reason) {
}
