package tracker.adapters.web;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/** The fixed estimation template and reading a filled one (.xlsx or .csv); each row is checked on its own. */
final class EstimationFiles {
    private EstimationFiles() {
    }

    static final String TEMPLATE_CSV = "﻿Phase,Subphase,Function,Steps,Effort (h)\n"
            + "ITA,Create test case,Login,5,8\nITA,Execute,Execute test,,16\n";

    /**
     * @param line    the line in the file (the header is line 1)
     * @param steps   3, 5 or null (a row without steps)
     * @param effortH Create hours of a function; hours of a row without steps
     */
    public record EstimationImportRow(int line, String phase, String subphase, String function, @Nullable Integer steps, @Nullable Double effortH,
                     List<String> errors) {
    }

    static List<EstimationImportRow> parse(String fileName, byte[] raw) {
        List<List<String>> rows = LeaveFiles.readRows(fileName, raw);
        List<EstimationImportRow> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String phase = at(r, 0);
            String subphase = at(r, 1);
            String function = at(r, 2);
            String stepsText = at(r, 3);
            String effortText = at(r, 4).replace(',', '.');
            List<String> errors = new ArrayList<>();
            if (phase.isEmpty()) {
                errors.add("Phase is required");
            }
            if (subphase.isEmpty()) {
                errors.add("Subphase is required");
            }
            if (function.isEmpty()) {
                errors.add("Function is required");
            }
            Integer steps = null;
            if (!stepsText.isEmpty()) {
                if (stepsText.equals("3") || stepsText.equals("5")) {
                    steps = Integer.parseInt(stepsText);
                } else {
                    errors.add("Steps must be 3, 5 or empty");
                }
            }
            Double effort = null;
            try {
                effort = Double.parseDouble(effortText);
                if (!(effort > 0)) {
                    errors.add("Effort must be more than 0 hours");
                }
            } catch (NumberFormatException e) {
                errors.add("Effort must be a number of hours");
            }
            out.add(new EstimationImportRow(i + 2, phase, subphase, function, steps, effort, errors));
        }
        return out;
    }

    private static String at(List<String> r, int i) {
        return i < r.size() ? r.get(i).strip() : "";
    }
}
