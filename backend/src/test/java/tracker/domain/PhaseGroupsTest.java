package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.issue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PhaseGroupsTest {
    static List<Issue> tasks(String... summaries) {
        List<Issue> out = new ArrayList<>();
        for (int n = 0; n < summaries.length; n++) {
            out.add(issue().key("BARO-" + (11 + n)).summary(summaries[n]).build());
        }
        return out;
    }

    @Test
    void taskNameStripsUnbalancedBrackets() {
        assertThat(PhaseGroups.taskName("[BookUI][LocalTask] ITA Prepare")).isEqualTo("ITA Prepare");
        assertThat(PhaseGroups.taskName("[BookUI[LocalTask] ITA Prepare")).isEqualTo("ITA Prepare");
        assertThat(PhaseGroups.taskName("[A][B] x")).isEqualTo("x");
        assertThat(PhaseGroups.taskName("[A][B]")).isEmpty();
        assertThat(PhaseGroups.taskName("Plain title")).isEqualTo("Plain title");
    }

    @Test
    void onlyDeclaredTasksHaveAGroup() {
        WorkStructure st = new WorkStructure(List.of("Test"), List.of(new TaskTemplate("* ita prepare", "Test", ""),
                new TaskTemplate("* ita execute", "Gone", "")));
        Map<String, String> g = PhaseGroups.of(tasks("[A] ITA Prepare", "[A] ITA Execute", "[Test] ITA Bug fix", "[A] ITA Prepare"), st);
        assertThat(g).containsExactly(Map.entry("BARO-11", "Test"), Map.entry("BARO-14", "Test"));
    }

    @Test
    void configuredPhasesComeFirstInTheirOrder() {
        Map<String, List<Issue>> subs = new LinkedHashMap<>();
        subs.put("ITA", List.of(issue().ps("2026-09-01").build()));
        subs.put("Implement_Coding", List.of(issue().ps("2026-10-01").build()));
        subs.put("Prepare", List.of(issue().ps("2026-10-05").build()));
        assertThat(PhaseGroups.ordered(subs, List.of("Prepare", "Implement_Coding")))
                .containsExactly("Prepare", "Implement_Coding", "ITA");
    }

    @Test
    void orderByEarliestStart() {
        Map<String, List<Issue>> subs = new LinkedHashMap<>();
        subs.put("C", List.of(issue().ps(null).build()));
        subs.put("A", List.of(issue().ps("2026-10-05").build()));
        subs.put("B", List.of(issue().ps("2026-10-01").build(), issue().ps("2026-10-20").build()));
        subs.put("D", List.of());
        assertThat(PhaseGroups.ordered(subs, List.of())).containsExactly("B", "A", "C", "D");
    }
}
