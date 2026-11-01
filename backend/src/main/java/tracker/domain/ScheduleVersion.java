package tracker.domain;

import java.util.List;

/**
 * One saved master schedule of an anken; the first one is the baseline. Each phase keeps what was computed when it was saved.
 *
 * @param savedBy the e-mail of the admin who saved it
 */
public record ScheduleVersion(long id, String epicKey, String savedAt, String savedBy, String reason, List<PhaseRow> phases) {

    public ScheduleVersion {
        phases = List.copyOf(phases);
    }

    /** @param totalEffortH the phase's effort from the estimation (weights applied, without JM Review) */
    public record PhaseRow(String phase, double totalEffortH, List<String> memberIds, String start, String end, int planMd) {
        public PhaseRow {
            memberIds = List.copyOf(memberIds);
        }
    }
}
