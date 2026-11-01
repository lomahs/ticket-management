package tracker.application.ports;

import java.util.List;

import tracker.domain.EstimationRow;

/** The estimation rows of each anken. */
public interface EstimationRepository {
    /** Ordered by position. */
    List<EstimationRow> findByEpic(String epic);

    /**
     * Rows with an id update that row, rows with id 0 are added; the epic's unpushed rows not listed are deleted. Pushed rows
     * are never changed.
     */
    void save(String epic, List<EstimationRow> rows);

    void markPushed(List<Long> ids, String at);

    /** Deletes the epic's unpushed rows. */
    void deleteUnpushed(String epic);
}
