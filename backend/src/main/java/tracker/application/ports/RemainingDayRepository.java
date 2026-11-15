package tracker.application.ports;

import java.util.Collection;
import java.util.Map;

/** The remaining estimate (and the Original Estimate) of each sub-task as the Daily Overview capture saw it, day by day. */
public interface RemainingDayRepository {
    /** Key → date → remaining hours, for the keys that have any. */
    Map<String, Map<String, Double>> find(Collection<String> issueKeys);

    /** Key → date → the Original Estimate the capture saw, for the captures that kept it. */
    Map<String, Map<String, Double>> findEstimates(Collection<String> issueKeys);

    /** Replaces the remaining hours and Original Estimates on {@code date} of the given sub-tasks. */
    void save(String date, Map<String, Double> remainingByKey, Map<String, Double> estimateByKey);
}
