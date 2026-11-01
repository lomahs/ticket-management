package tracker.application.ports;

import java.util.List;

import tracker.domain.EstimationRow;
import tracker.domain.TaskTarget;

/** The estimation rows of each anken and where their sub-tasks go. */
public interface EstimationRepository {
    /** Ordered by position. */
    List<EstimationRow> findByEpic(String epic);

    /**
     * Rows with an id update that row, rows with id 0 are added; the epic's unpushed rows not listed are deleted. Pushed rows
     * are never changed.
     */
    void save(String epic, List<EstimationRow> rows);

    void markPushed(List<Long> ids, String at);

    List<TaskTarget> findTargets(String epic);

    /** Replaces every target of the epic. */
    void saveTargets(String epic, List<TaskTarget> targets);
}
