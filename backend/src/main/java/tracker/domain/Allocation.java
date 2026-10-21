package tracker.domain;

/** Hours per day a member gives an anken (epic), from an effective date (ISO) until a later row replaces it. 0 means off the anken. */
public record Allocation(String memberId, String epicKey, String effectiveFrom, double hoursPerDay) {
}
