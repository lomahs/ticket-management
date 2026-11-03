package tracker.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rows of a Phases &amp; tasks import (Phase, Task name, Prefix, Type, Workflow), each checked on its own. The Task name becomes
 * a template match by the Task name rule ({@link TaskNames#match}); the Workflow column is not read: the type decides it.
 */
public final class StructureImport {
    private StructureImport() {
    }

    /** Type cell, lower case without spaces, "_" or "-" → template type. */
    private static final Map<String, String> TYPES = Map.of("task", "", "qna", TaskTemplate.QNA, "workrequest", TaskTemplate.WORK_REQUEST);

    /**
     * @param line     the line in the file (the header is line 1)
     * @param match    the template's Task name: the rule with the Task name filled in
     * @param workflow on for Task, off for QnA and WorkRequest
     * @param errors   empty when the row can be imported
     */
    public record StructureImportRow(int line, String phase, String task, String match, String prefix, String type, boolean workflow,
                            List<String> errors) {
    }

    public static List<StructureImportRow> rows(List<? extends List<String>> cells, String taskRule) {
        List<StructureImportRow> out = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            List<String> r = cells.get(i);
            String phase = at(r, 0), task = at(r, 1), prefix = at(r, 2);
            String type = TYPES.get(at(r, 3).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", ""));
            List<String> errors = new ArrayList<>();
            if (phase.isEmpty()) {
                errors.add("Phase is required");
            }
            if (task.isEmpty()) {
                errors.add("Task name is required");
            }
            if (type == null) {
                errors.add("Type must be Task, QnA or WorkRequest");
            }
            out.add(new StructureImportRow(i + 2, phase, task, task.isEmpty() ? "" : TaskNames.match(taskRule, phase, task), prefix,
                    type == null ? "" : type, "".equals(type), errors));
        }
        return out;
    }

    private static String at(List<String> r, int i) {
        return i < r.size() && r.get(i) != null ? r.get(i).strip() : "";
    }
}
