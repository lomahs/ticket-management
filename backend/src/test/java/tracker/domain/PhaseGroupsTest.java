package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.issue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PhaseGroupsTest {
    static final List<String> BARO = List.of("ITA Prepare", "ITA QA management", "ITA Create test case", "ITA Execute", "ITA Bug fix",
            "ITB Prepare", "ITB QA management", "ITB Create test case", "ITB Execute", "ITB Bug fix",
            "ST Prepare", "ST QA management", "ST Create test case", "ST Execute", "ST Bug fix",
            "UAT Prepare", "UAT QA management", "UAT Create test case", "UAT Execute", "UAT Bug fix",
            "Performance viewpoint", "Performance test", "WorkRequest", "Change Request");

    static List<Issue> tasks(String... summaries) {
        List<Issue> out = new ArrayList<>();
        for (int n = 0; n < summaries.length; n++) {
            out.add(issue().key("BARO-" + (11 + n)).summary(summaries[n]).build());
        }
        return out;
    }

    static Map<String, Integer> sizes(Map<String, String> groups) {
        Map<String, Integer> out = new LinkedHashMap<>();
        groups.values().forEach(g -> out.merge(g, 1, Integer::sum));
        return out;
    }

    @Test
    void barosTasksGroupByKeyWord() {
        Map<String, String> g = PhaseGroups.of(tasks(BARO.stream().map(s -> "[BookUI][LocalTask] " + s).toArray(String[]::new)),
                WorkStructure.empty());
        assertThat(sizes(g)).containsExactly(Map.entry("ITA", 5), Map.entry("ITB", 5), Map.entry("ST", 5), Map.entry("UAT", 5),
                Map.entry("Performance", 2), Map.entry("Other", 2));
        assertThat(g.get("BARO-33")).isEqualTo(PhaseGroups.OTHER);
        assertThat(g.get("BARO-34")).isEqualTo(PhaseGroups.OTHER);
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
    void keyWordIsCaseInsensitiveAndKeepsFirstSpelling() {
        Map<String, String> g = PhaseGroups.of(tasks("[A] ITA Prepare", "[A] ita Execute"), WorkStructure.empty());
        assertThat(g).containsExactly(Map.entry("BARO-11", "ITA"), Map.entry("BARO-12", "ITA"));
    }

    @Test
    void emptyNameGoesToOther() {
        Map<String, String> g = PhaseGroups.of(tasks("[A][B]", "[C][D]"), WorkStructure.empty());
        assertThat(g.values()).containsOnly(PhaseGroups.OTHER);
    }

    @Test
    void matchingTemplateWins() {
        WorkStructure st = new WorkStructure(List.of("Test"), List.of(new TaskTemplate("* ita prepare", "Test", "")));
        Map<String, String> g = PhaseGroups.of(tasks("[A] ITA Prepare", "[A] ITA Execute"), st);
        assertThat(g).containsExactly(Map.entry("BARO-11", "Test"), Map.entry("BARO-12", PhaseGroups.OTHER));
    }

    @Test
    void bracketPhaseConfiguredInStructureWins() {
        WorkStructure st = new WorkStructure(List.of("Prepare", "Implement_Coding"), List.of());
        Map<String, String> g = PhaseGroups.of(tasks("[Prepare] Payment flow", "[Implement_Coding] Payment flow", "[BookUI[LocalTask] ITA Prepare"),
                st);
        assertThat(g).containsExactly(Map.entry("BARO-11", "Prepare"), Map.entry("BARO-12", "Implement_Coding"),
                Map.entry("BARO-13", PhaseGroups.OTHER));
    }

    @Test
    void configuredPhasesComeFirstInTheirOrder() {
        Map<String, List<Issue>> subs = new LinkedHashMap<>();
        subs.put("Other", List.of());
        subs.put("ITA", List.of(issue().ps("2026-09-01").build()));
        subs.put("Implement_Coding", List.of(issue().ps("2026-10-01").build()));
        subs.put("Prepare", List.of(issue().ps("2026-10-05").build()));
        assertThat(PhaseGroups.ordered(subs, List.of("Prepare", "Implement_Coding")))
                .containsExactly("Prepare", "Implement_Coding", "ITA", "Other");
    }

    @Test
    void orderByEarliestStartOtherLast() {
        Map<String, List<Issue>> subs = new LinkedHashMap<>();
        subs.put("Other", List.of(issue().ps("2026-09-01").build()));
        subs.put("C", List.of(issue().ps(null).build()));
        subs.put("A", List.of(issue().ps("2026-10-05").build()));
        subs.put("B", List.of(issue().ps("2026-10-01").build(), issue().ps("2026-10-20").build()));
        subs.put("D", List.of());
        assertThat(PhaseGroups.ordered(subs, List.of())).containsExactly("B", "A", "C", "D", "Other");
    }
}
