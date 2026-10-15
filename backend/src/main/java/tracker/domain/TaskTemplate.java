package tracker.domain;

/**
 * One Task of the fixed set every epic carries, as an admin configures it.
 *
 * @param match    the Task Summary to recognise; case- and spacing-insensitive, {@code *} matches any text
 * @param prefix   the value of {prefix} in the Summary of this Task's sub-tasks
 * @param workflow whether the sub-tasks follow the 5-step review workflow (one function per name)
 */
public record TaskTemplate(String match, String phase, String prefix, boolean workflow) {

    public TaskTemplate {
        match = match == null ? "" : match;
        phase = phase == null ? "" : phase;
        prefix = prefix == null ? "" : prefix;
    }

    public TaskTemplate(String match, String phase, String prefix) {
        this(match, phase, prefix, true);
    }
}
