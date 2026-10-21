package tracker.application.ports;

/** Folds one member into another across every table that refers to a member. */
public interface MemberMergeRepository {
    /** Moves everything of {@code removeId} to {@code keepId} in one transaction, then deletes {@code removeId}. */
    void merge(String keepId, String removeId);
}
