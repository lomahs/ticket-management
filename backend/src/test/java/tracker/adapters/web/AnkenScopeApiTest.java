package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.MemberRepository;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.Member;
import tracker.domain.Role;
import tracker.domain.StepKind;
import tracker.domain.Worklog;

/** A viewer only ever receives the anken they may see; `an` is a member of PAY-1100 only, `zoe` of PAY-1300 only. */
@DemoTest
@AutoConfigureMockMvc
@DirtiesContext
class AnkenScopeApiTest extends ApiTestSupport {
    static final String AN = "an.nguyen@acme.vn";
    /** A sub-task of PAY-1300. */
    static final String PAY_1300_SUBTASK = "PAY-1342";
    /** Every demo member works on PAY-1100, so the test adds one who does not. */
    static final String OUTSIDER = "zoe";

    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    IssueRepository issues;
    @Autowired
    MemberRepository members;

    @BeforeEach
    void anInPay1100OnlyAndZoeInPay1300Only() {
        members.save(new Member(OUTSIDER, "Zoe", "Zoe Outsider", Role.BA, "VN", "#123456"));
        ankenMembers.add("PAY-1300", OUTSIDER, "test", Instant.now().toString());
        for (String epic : ankenMembers.findEpicsOf("an")) {
            if (!epic.equals("PAY-1100")) {
                ankenMembers.remove(epic, "an");
            }
        }
        ankenMembers.add("PAY-1100", "an", "test", Instant.now().toString());
    }

    private static List<String> keys(JsonNode cards) {
        List<String> out = new ArrayList<>();
        cards.forEach(c -> out.add(c.get("key").asText()));
        return out;
    }

    private static List<String> ids(JsonNode members) {
        List<String> out = new ArrayList<>();
        members.forEach(m -> out.add(m.get("id").asText()));
        return out;
    }

    @Test
    void baOverviewShowsOnlyTheirAnken() throws Exception {
        Res r = call(get("/api/v1/anken").with(as(AN)));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(keys(r.json())).containsExactly("PAY-1100");
    }

    @Test
    void baCannotOpenAnotherAnken() throws Exception {
        problem(call(get("/api/v1/anken/PAY-1300").with(as(AN))), 404, "not_found");
        problem(call(get("/api/v1/anken/PAY-1300/matrix").with(as(AN))), 404, "not_found");
        assertThat(call(get("/api/v1/anken/PAY-1100").with(as(AN))).status()).isEqualTo(200);
        problem(call(get("/api/v1/anken").param("anken", "PAY-1300").with(as(AN))), 404, "not_found");
    }

    @Test
    void ticketOfAnotherAnkenIs404() throws Exception {
        JsonNode e = problem(call(get("/api/v1/tickets/" + PAY_1300_SUBTASK).with(as(AN))), 404, "not_found");
        assertThat(e.get("detail").asText()).isEqualTo("Ticket " + PAY_1300_SUBTASK + " was not found");
        problem(call(get("/api/v1/tickets/" + PAY_1300_SUBTASK + "/function").with(as(AN))), 404, "not_found");
        assertThat(call(get("/api/v1/tickets/" + PAY_1300_SUBTASK)).status()).isEqualTo(200); // the admin sees it
    }

    @Test
    void membersAreScoped() throws Exception {
        Set<String> allowed = new HashSet<>(ankenMembers.findAll().get("PAY-1100"));
        List<Issue> pay1100 = issues.findByEpics(List.of("PAY-1100"));
        for (Issue i : pay1100) {
            allowed.add(i.assigneeId());
            i.worklogs().stream().map(Worklog::memberId).forEach(allowed::add);
            i.events().stream().filter(e -> e.field() == EventField.ASSIGNEE).forEach(e -> {
                allowed.add(e.fromValue());
                allowed.add(e.toValue());
            });
        }
        for (Function f : Flags.buildFunctions(pay1100)) {
            allowed.add(f.ownerId());
            allowed.add(f.reviewerId());
        }
        List<String> mine = ids(call(get("/api/v1/members").with(as(AN))).json());
        assertThat(mine).contains("an").doesNotContain(OUTSIDER).isSorted().allMatch(allowed::contains);
        assertThat(ids(call(get("/api/v1/members")).json())).contains(OUTSIDER); // the admin sees every anken
    }

    @Test
    void myWorkOfAMemberOutsideTheScopeIs404() throws Exception {
        JsonNode e = problem(call(get("/api/v1/my-work").param("member", OUTSIDER).with(as(AN))), 404, "not_found");
        assertThat(e.get("detail").asText()).isEqualTo("Member " + OUTSIDER + " was not found");
        assertThat(call(get("/api/v1/my-work").param("member", "an").with(as(AN))).status()).isEqualTo(200);
        assertThat(call(get("/api/v1/my-work").param("member", OUTSIDER)).status()).isEqualTo(200); // the admin sees PAY-1300
    }

    /** The epics of every task card on a My Work page. */
    private static Set<String> epicsOf(JsonNode myWork) {
        List<JsonNode> cards = new ArrayList<>();
        for (String list : List.of("delay", "due_today", "due_next", "related")) {
            myWork.get(list).forEach(cards::add);
        }
        myWork.get("backlog").forEach(g -> g.get("items").forEach(cards::add));
        Set<String> out = new HashSet<>();
        cards.forEach(c -> out.add(c.get("epic_key").asText()));
        return out;
    }

    @Test
    void myOwnWorkOpensWithoutAMembershipAndShowsOnlyTheScope() throws Exception {
        Res all = call(get("/api/v1/my-work").param("member", "an")); // the admin sees an's work on every anken
        assertThat(epicsOf(all.json())).contains("PAY-1100", "PAY-1300");
        assertThat(epicsOf(call(get("/api/v1/my-work").param("member", "an").with(as(AN))).json())).containsExactly("PAY-1100");

        ankenMembers.remove("PAY-1100", "an"); // an is now a member of no anken
        Res own = call(get("/api/v1/my-work").param("member", "an").with(as(AN)));
        assertThat(own.status()).as(own.text()).isEqualTo(200);
        assertThat(epicsOf(own.json())).isEmpty();
        problem(call(get("/api/v1/my-work").param("member", "binh").with(as(AN))), 404, "not_found");
    }

    @Test
    void earlierAssigneesOfAHandoverAreNamed() throws Exception {
        members.save(new Member("yuki", "Yuki", "Yuki Former", Role.BA, "JP", "#654321")); // in no anken, on no other issue
        Issue review = issues.findByEpics(List.of("PAY-1100")).stream().filter(i -> i.step() == StepKind.JM_REVIEW).findFirst()
                .orElseThrow();
        List<IssueEvent> events = new ArrayList<>(review.events());
        events.add(new IssueEvent("2026-09-01", EventField.ASSIGNEE, "yuki", review.assigneeId()));
        issues.save(new Issue(review.key(), review.project(), review.epicKey(), review.parentKey(), review.kind(), review.summary(),
                review.phase(), review.status(), review.assigneeId(), review.plannedStart(), review.plannedEnd(), review.estimateH(),
                review.remainingH(), review.created(), review.jiraStatus(), review.functionId(), review.functionName(), review.step(),
                review.worklogs(), events, review.reporterId()));

        assertThat(call(get("/api/v1/tickets/" + review.key()).with(as(AN))).json().get("history").toString())
                .contains("\"from_id\":\"yuki\"");
        assertThat(ids(call(get("/api/v1/members").with(as(AN))).json())).contains("yuki");
    }

    @Test
    void adminSeesAll() throws Exception {
        assertThat(keys(call(get("/api/v1/anken")).json())).hasSize(6);
    }

    @Test
    void ankenParamNarrows() throws Exception {
        assertThat(keys(call(get("/api/v1/anken").param("anken", "PAY-1300")).json())).containsExactly("PAY-1300");
    }
}
