package tracker.domain;

import java.util.List;

/**
 * One Task of the fixed set every epic carries, as an admin configures it.
 *
 * @param match    the Task Summary to recognise; case- and spacing-insensitive, {@code *} matches any text
 * @param prefix   the value of {prefix} in the Summary of this Task's sub-tasks
 * @param workflow whether the sub-tasks follow the 5-step review workflow (one function per name)
 * @param type     what the Task's sub-tasks are for the Daily Overview: one of {@link #TYPES}; "" for none
 */
public record TaskTemplate(String match, String phase, String prefix, boolean workflow, String type) {
    public static final String QNA = "qna";
    public static final String BUG = "bug";
    public static final String WORK_REQUEST = "work_request";
    /** The types in the order the Daily Overview lists them; "" is no type. */
    public static final List<String> TYPES = List.of("", QNA, BUG, WORK_REQUEST);

    public TaskTemplate {
        match = match == null ? "" : match;
        phase = phase == null ? "" : phase;
        prefix = prefix == null ? "" : prefix;
        type = type == null ? "" : type;
    }

    public TaskTemplate(String match, String phase, String prefix, boolean workflow) {
        this(match, phase, prefix, workflow, "");
    }

    public TaskTemplate(String match, String phase, String prefix) {
        this(match, phase, prefix, true);
    }
}
