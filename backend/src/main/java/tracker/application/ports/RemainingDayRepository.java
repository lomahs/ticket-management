package tracker.application.ports;

import java.util.Collection;
import java.util.Map;

/** The remaining estimate of each sub-task as the Daily Overview capture saw it, day by day. */
public interface RemainingDayRepository {
    /** Key → date → remaining hours, for the keys that have any. */
    Map<String, Map<String, Double>> find(Collection<String> issueKeys);

    /** Replaces the remaining hours on {@code date} of the given sub-tasks. */
    void save(String date, Map<String, Double> remainingByKey);
}
