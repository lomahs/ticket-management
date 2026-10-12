package tracker.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tracker.adapters.clock.FixedClock;
import tracker.adapters.fake.Dataset;
import tracker.adapters.fake.FakeJira;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.NewSubtask;
import tracker.domain.Status;

class FakeJiraTest {
    static FakeJira jira() {
        return new FakeJira(new FixedClock(Dataset.DEMO_TODAY));
    }

    @Test
    void searchServesParentsAndSubtasksOfTrackedEpicsOnly() {
        Set<String> keys = new HashSet<>();
        jira().search(List.of("PAY-1100"), null).forEach(s -> keys.add(s.key()));
        assertThat(keys).contains("PAY-1102", "PAY-1201", "PAY-1260", "PAY-1180").doesNotContain("PAY-1342", "CRM-412");
    }

    @Test
    void everyIssueBelongsToATrackedEpicAndKeysAreUnique() {
        List<IssueSnapshot> snaps = new ArrayList<>();
        jira().search(Dataset.TRACKED_EPICS, null).forEach(snaps::add);
        assertThat(snaps).hasSize(297);
        assertThat(snaps.stream().map(IssueSnapshot::key).distinct().count()).isEqualTo(snaps.size());
    }

    @Test
    void unknownEpicAndNonEpicKeys() {
        assertThat(jira().getEpic("PAY-1500").summary()).isEqualTo("Refund automation");
        assertThat(jira().getEpic("PAY-1201")).isNull();
    }

    @Test
    void writesUpdateIssueAndMarkItUpdated() {
        FakeJira j = jira();
        j.updateIssue("PAY-1214", "2026-10-12", "2026-10-13", "acc-an");
        j.transition("PAY-1214", Status.WIP);
        IssueSnapshot s = j.getIssue("PAY-1214");
        assertThat(List.of(s.fields().get("customfield_10020"), s.fields().get("customfield_10016"), s.assignee().accountId(), s.statusName()))
                .containsExactly("2026-10-12", "2026-10-13", "acc-an", "In Progress");
        assertThat(s.changes().subList(s.changes().size() - 2, s.changes().size())).extracting(c -> c.field())
                .containsExactly("assignee", "status");
        assertThat(s.updated()).isGreaterThan("2026-10-06T10:00:00+00:00");
    }

    @Test
    void createSubtasksAllocatesNewKeys() {
        FakeJira j = jira();
        List<String> keys = j.createSubtasks("PAY-1102", List.of(new NewSubtask("[Implement_Coding][F13] X – Create", "acc-an",
                "2026-10-08", "2026-10-09", 8)));
        assertThat(keys).containsExactly("PAY-1501");
        assertThat(((Number) j.getIssue("PAY-1501").fields().get("timeoriginalestimate")).longValue()).isEqualTo(8 * 3600);
    }

    @Test
    void fakeFieldCatalogHasTheMappedFields() {
        Map<String, String> ids = new java.util.HashMap<>();
        jira().listFields().forEach((JiraField f) -> ids.put(f.id(), f.type()));
        assertThat(ids).containsEntry("customfield_10020", "date").containsEntry("timeoriginalestimate", "number");
    }
}
