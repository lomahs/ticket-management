package tracker.domain;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The Baseline plan of an anken (docs/glossary.md): each sub-task as the first saved version with estimates kept it. A
 * sub-task created later is not in it (its Baseline plan is 0); one deleted later stays.
 *
 * @param savedAt when that version was saved
 */
public record Baseline(String savedAt, List<Item> items) {
    /**
     * @param estimateH the Original Estimate
     * @param parentKey its Task, for the phase and sub-phase filters
     * @param ownerId   its plan owner, for the member filter; "" when nobody
     * @param days      the hours planned on each day
     */
    public record Item(String key, double estimateH, @Nullable String parentKey, String ownerId, Map<String, Double> days) {
    }
}
