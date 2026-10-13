package tracker.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.ports.IssueRepository;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.IssueFlags;
import tracker.domain.Naming;
import tracker.domain.Thresholds;

/**
 * The demo data, synced through the real pipeline, must reproduce what ui-mock-v0 showed. golden.json is the file the
 * original backend's characterization test uses (captured from the ui-mock-v0 implementation).
 */
@DemoTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenTest {
    Map<String, Object> golden;
    List<Function> fns;
    Map<String, IssueFlags> flags = new LinkedHashMap<>();

    /** The demo profile seeded and synced the data at start-up. */
    @SuppressWarnings("unchecked")
    @BeforeAll
    void load(@Autowired IssueRepository repository) throws IOException {
        golden = new ObjectMapper().readValue(GoldenTest.class.getResourceAsStream("/characterization/golden.json"), Map.class);
        List<Issue> issues = repository.findByEpics(List.of("PAY-1100"));
        fns = Flags.buildFunctions(issues).stream().filter(f -> f.epicKey().equals("PAY-1100")).toList();
        for (Function f : fns) {
            for (int i = 0; i < f.cells().size(); i++) {
                Issue c = f.cells().get(i);
                if (c != null) {
                    flags.put(c.key(), Flags.issueFlags(c, i == f.current(), f.ownerId(), Dataset.DEMO_TODAY, Thresholds.defaults()));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String name) {
        return (Map<String, Object>) golden.get(name);
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    void allTwelveFunctionsWithFiveSteps() {
        assertThat(fns).extracting(Function::id).containsExactly("F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10",
                "F11", "F12");
        assertThat(fns).allMatch(f -> f.cells().stream().allMatch(java.util.Objects::nonNull));
    }

    @Test
    @SuppressWarnings("unchecked")
    void subtasksMatchGolden() {
        Map<String, Object> expected = section("subtasks");
        Map<String, Map<String, Object>> got = new TreeMap<>();
        for (Function f : fns) {
            for (Issue c : f.cells()) {
                Map<String, Object> row = new TreeMap<>();
                row.put("fid", f.id());
                row.put("step", c.step().value());
                row.put("status", c.status().value());
                row.put("assignee", c.assigneeId());
                row.put("worker", Flags.workerOf(c, f.ownerId()));
                row.put("ps", c.plannedStart());
                row.put("pe", c.plannedEnd());
                row.put("est", c.estimateH());
                row.put("logged", c.loggedH());
                row.put("rounds", (double) flags.get(c.key()).rounds());
                row.put("held", (double) flags.get(c.key()).heldDays());
                got.put(c.key(), row);
            }
        }
        assertThat(got.keySet()).isEqualTo(expected.keySet());
        expected.forEach((key, value) -> {
            Map<String, Object> want = new TreeMap<>((Map<String, Object>) value);
            want.replaceAll((k, v) -> v instanceof Number n ? (Object) n.doubleValue() : v);
            assertThat(got.get(key)).as(key).isEqualTo(want);
        });
    }

    @Test
    void currentStepLateOverAndToneMatchGolden() {
        Map<String, Object> current = new LinkedHashMap<>();
        fns.forEach(f -> current.put(f.id(), f.current()));
        assertThat(current).isEqualTo(section("current_step"));
        Map<String, Object> late = new LinkedHashMap<>();
        flags.forEach((k, v) -> {
            if (v.late()) {
                late.put(k, v.lateDays());
            }
        });
        assertThat(late).isEqualTo(section("late"));
        List<String> over = new ArrayList<>(flags.entrySet().stream().filter(e -> e.getValue().over()).map(Map.Entry::getKey).sorted().toList());
        assertThat(over).isEqualTo(golden.get("over"));
        Map<String, Object> tone = new LinkedHashMap<>();
        flags.forEach((k, v) -> tone.put(k, v.tone()));
        assertThat(tone).isEqualTo(section("tone"));
    }

    @Test
    void matrixStatsMatchGolden() {
        List<Issue> cells = fns.stream().flatMap(f -> f.cells().stream()).toList();
        java.util.Set<String> lateKeys = new java.util.HashSet<>(flags.entrySet().stream().filter(e -> e.getValue().late())
                .map(Map.Entry::getKey).toList());
        Map<String, Object> want = section("matrix_stats");
        assertThat(Math.round(fns.stream().mapToInt(Flags::progressPct).sum() / (double) fns.size())).isEqualTo(((Number) want.get("progress_pct")).longValue());
        assertThat(fns.stream().filter(f -> f.current() == 5).count()).isEqualTo(((Number) want.get("functions_done")).longValue());
        assertThat(fns.stream().filter(f -> f.cells().stream().anyMatch(c -> lateKeys.contains(c.key()))).count())
                .isEqualTo(((Number) want.get("late_functions")).longValue());
        assertThat(lateKeys).hasSize(((Number) want.get("late_subtasks")).intValue());
        assertThat(flags.values().stream().filter(IssueFlags::over).count()).isEqualTo(((Number) want.get("over_subtasks")).longValue());
        assertThat(cells.stream().mapToDouble(Issue::loggedH).sum()).isEqualTo(num(want.get("logged_h")));
        assertThat(cells.stream().mapToDouble(Issue::estimateH).sum()).isEqualTo(num(want.get("estimate_h")));
    }

    @Test
    void nameRuleNowCoversHyphenatedFunctionNames() {
        List<String> summaries = fns.stream().flatMap(f -> f.cells().stream()).map(Issue::summary).toList();
        // ui-mock-v0 matched 50/60 (F04, F08 split at their hyphen); {step} now only matches aliases.
        assertThat(((Number) section("name_rule_coverage").get("total")).intValue()).isEqualTo(summaries.size());
        assertThat(Naming.coverage(Naming.compileRule(Naming.DEFAULT_RULE, Naming.DEFAULT_ALIASES), summaries)).isEqualTo(60);
    }
}
