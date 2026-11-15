package tracker.application.ports;

/** What an anken onboarded without its epic holds, under its draft key ({@link tracker.domain.DraftAnken}). */
public interface DraftAnkenRepository {
    /** A draft key no anken uses. */
    String nextKey();

    /**
     * Moves everything of the draft to the epic: its onboarding (no longer a draft), estimation, master schedule, detail plan,
     * members and allocations. A member of both, or an allocation on the same day, keeps the epic's.
     */
    void moveTo(String draft, String epic);

    /** Deletes everything of the draft. */
    void delete(String draft);
}
