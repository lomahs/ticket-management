package tracker.application.ports;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** The hours Detail Planning put on each day of a pushed sub-task. */
public interface PlanDayRepository {
    /** Key → date → hours, for the keys that have any. */
    Map<String, Map<String, Double>> find(Collection<String> issueKeys);

    /** The keys among {@code issueKeys} that have hours per day. */
    Set<String> linked(Collection<String> issueKeys);

    /** Replaces the hours per day of the sub-task. */
    void save(String issueKey, Map<String, Double> hoursByDay);
}
