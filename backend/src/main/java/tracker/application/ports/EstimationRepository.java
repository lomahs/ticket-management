package tracker.application.ports;

import java.util.List;

import tracker.domain.AutoPicks;
import tracker.domain.DetailScheduler;
import tracker.domain.EstimationRow;
import tracker.domain.SavedPlan;
import tracker.domain.StepEdit;

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

    /** The step edits of the epic's rows. */
    List<StepEdit> stepEdits(String epic);

    /** Replaces the edit of that row and step; an empty edit is deleted. */
    void saveStepEdit(StepEdit edit);

    /** Deletes the step edits of the epic's unpushed rows. */
    void deleteUnpushedStepEdits(String epic);

    /** Who Auto allocate put in the slots of the epic's rows. */
    List<AutoPicks> autoPicks(String epic);

    /** Replaces the row's auto picks; empty ones are deleted. */
    void saveAutoPicks(AutoPicks picks);

    /** The plan Auto allocate saved for the epic's unpushed rows. */
    SavedPlan savedPlan(String epic);

    /** The hours per person and day Auto allocate saved for the epic's pushed rows. */
    List<DetailScheduler.Part> pushedParts(String epic);

    /** Replaces the saved plan of the epic's unpushed rows. */
    void savePlan(String epic, SavedPlan plan);

    /** Deletes the saved plan of the epic's unpushed rows. */
    void deleteSavedPlan(String epic);

    /** Whether the epic was planned before plans were saved, and its plan is still to be computed once. */
    boolean initialPlanPending(String epic);

    void setInitialPlanPending(String epic, boolean pending);
}
