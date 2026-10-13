package tracker.adapters.web;

/** Shared request formats for Bean Validation. */
final class Formats {
    private Formats() {
    }

    static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String DATE_MESSAGE = "must be a date (YYYY-MM-DD)";
    static final String MONTH = "^\\d{4}-\\d{2}$";
    static final String MONTH_MESSAGE = "must be a month (YYYY-MM)";
}
