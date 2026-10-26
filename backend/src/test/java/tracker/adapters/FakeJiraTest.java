package tracker.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tracker.adapters.fake.Dataset;
import tracker.adapters.fake.FakeJira;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.JiraRequest;
import tracker.application.ports.JiraRejected;

class FakeJiraTest {
    static FakeJira jira() {
        return new FakeJira(Clock.fixed(Instant.parse(Dataset.DEMO_TODAY + "T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
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
        assertThat(snaps).hasSize(311);
        assertThat(snaps.stream().map(IssueSnapshot::key).distinct().count()).isEqualTo(snaps.size());
    }

    @Test
    void unknownEpicAndNonEpicKeys() {
        assertThat(jira().findEpic("PAY-1500").orElseThrow().summary()).isEqualTo("Refund automation");
        assertThat(jira().findEpic("PAY-1201")).isEmpty();
    }

    @Test
    void writesUpdateIssueAndMarkItUpdated() {
        FakeJira j = jira();
        j.send(new JiraRequest("PUT", "/rest/api/3/issue/PAY-1214",
                "{\"fields\":{\"customfield_10020\":\"2026-10-12\",\"customfield_10016\":\"2026-10-13\",\"assignee\":{\"accountId\":\"acc-an\"}}}"));
        j.send(new JiraRequest("POST", "/rest/api/3/issue/PAY-1214/transitions", "{\"transition\":{\"id\":\"21\"}}"));
        IssueSnapshot s = j.findIssue("PAY-1214").orElseThrow();
        assertThat(List.of(s.fields().get("customfield_10020"), s.fields().get("customfield_10016"), s.assignee().accountId(), s.statusName()))
                .containsExactly("2026-10-12", "2026-10-13", "acc-an", "In Progress");
        assertThat(s.changes().subList(s.changes().size() - 2, s.changes().size())).extracting(c -> c.field())
                .containsExactly("assignee", "status");
        assertThat(s.updated()).isGreaterThan("2026-10-06T10:00:00+00:00"); // the fixed clock: 2026-10-07T02:00Z
    }

    @Test
    void createAllocatesNewKeys() {
        FakeJira j = jira();
        String body = j.send(new JiraRequest("POST", "/rest/api/3/issue", "{\"fields\":{\"project\":{\"key\":\"PAY\"},"
                + "\"issuetype\":{\"id\":\"10003\"},\"parent\":{\"key\":\"PAY-1102\"},\"summary\":\"[Implement_Coding][F13] X – Create\","
                + "\"assignee\":{\"accountId\":\"acc-an\"},\"customfield_10020\":\"2026-10-08\",\"timetracking\":{\"originalEstimate\":\"480m\"}}}")).body();
        assertThat(body).contains("\"key\":\"PAY-1501\"");
        assertThat(((Number) j.findIssue("PAY-1501").orElseThrow().fields().get("timeoriginalestimate")).longValue()).isEqualTo(8 * 3600);
    }

    /** Like Jira, the demo site refuses fields it does not know and transitions it does not offer. */
    @Test
    void unknownFieldsAndTransitionsAreRefused() {
        FakeJira j = jira();
        assertThatThrownBy(() -> j.send(new JiraRequest("PUT", "/rest/api/3/issue/PAY-1214", "{\"fields\":{\"customfield_99999\":1}}")))
                .isInstanceOf(JiraRejected.class).hasMessageContaining("customfield_99999");
        assertThatThrownBy(() -> j.send(new JiraRequest("POST", "/rest/api/3/issue/PAY-1214/transitions", "{\"transition\":{\"id\":\"99\"}}")))
                .isInstanceOf(JiraRejected.class);
        assertThat(j.transitions("PAY-1214")).extracting(t -> t.toName()).doesNotContain(j.findIssue("PAY-1214").orElseThrow().statusName());
    }

    @Test
    void fakeFieldCatalogHasTheMappedFields() {
        Map<String, String> ids = new java.util.HashMap<>();
        jira().listFields().forEach((JiraField f) -> ids.put(f.id(), f.type()));
        assertThat(ids).containsEntry("customfield_10020", "date").containsEntry("timeoriginalestimate", "number");
    }
}
