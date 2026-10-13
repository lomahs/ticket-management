package tracker.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tracker.adapters.jira.JiraCloudReader;
import tracker.adapters.jira.JiraConfig;
import tracker.application.dto.IssueSnapshot;

/**
 * Read-only checks against a real Jira site. Excluded by default; run with
 * {@code ./mvnw test -Dgroups=jira_live -DexcludedGroups=} and TRACKER_JIRA_URL, _EMAIL, _TOKEN, _EPIC set.
 */
@Tag("jira_live")
class JiraLiveTest {
    static final List<String> ENV = List.of("TRACKER_JIRA_URL", "TRACKER_JIRA_EMAIL", "TRACKER_JIRA_TOKEN", "TRACKER_JIRA_EPIC");
    JiraCloudReader reader;

    @BeforeEach
    void reader() {
        assumeTrue(ENV.stream().allMatch(k -> System.getenv(k) != null && !System.getenv(k).isEmpty()), "set " + String.join(", ", ENV));
        String fields = Stream.of(System.getenv("TRACKER_JIRA_FIELDS")).filter(f -> f != null && !f.isEmpty()).findFirst()
                .orElse("customfield_10020,customfield_10016,timeoriginalestimate");
        JiraConfig cfg = new JiraConfig(System.getenv("TRACKER_JIRA_URL"), System.getenv("TRACKER_JIRA_EMAIL"),
                System.getenv("TRACKER_JIRA_TOKEN"), Arrays.asList(fields.split(",")));
        reader = new JiraCloudReader(() -> cfg);
    }

    @Test
    void connection() {
        assertThat(reader.testConnection()).isTrue();
    }

    @Test
    void epicAndItsIssuesCanBeRead() {
        String epic = System.getenv("TRACKER_JIRA_EPIC");
        assertThat(reader.findEpic(epic)).isPresent();
        List<IssueSnapshot> snaps = new ArrayList<>();
        reader.search(List.of(epic), null).forEach(snaps::add);
        assertThat(snaps).as("the epic has no child issues").isNotEmpty();
        assertThat(snaps).allMatch(s -> !s.statusName().isEmpty());
    }
}
