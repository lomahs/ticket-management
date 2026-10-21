package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.adapters.web.auth.AuthApi;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.MemberRepository;
import tracker.domain.AccessStatus;
import tracker.domain.Anken;
import tracker.domain.AnkenScopeSetting;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.Member;
import tracker.domain.Permission;
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
    /** A PIM whose anken scope is limited to their membership (PAY-1100); see {@link #pimOfPay1100Only()}. */
    static final String PIM = "pia@acme.vn";
    /** A sub-task of PAY-1100. */
    static final String PAY_1100_SUBTASK = "PAY-1213";

    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    AnkenRepository anken;
    @Autowired
    AccessRepository access;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;
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

    /** A PIM sees every anken by default; with the scope setting "members" only the anken they are a member of. */
    private void pimOfPay1100Only() {
        members.save(new Member("pia", "Pia", "Pia Planner", Role.PIM, "VN", "#abcdef", PIM, Permission.USER, false, "acc-pia"));
        access.saveStatus("pia", AccessStatus.ACTIVE);
        access.saveAnkenScope("pia", AnkenScopeSetting.MEMBERS);
        ankenMembers.add("PAY-1100", "pia", "test", Instant.now().toString());
    }

    private String titleOf(String epic) {
        return anken.findAll().stream().filter(a -> a.key().equals(epic)).map(Anken::title).findFirst().orElseThrow();
    }

    /**
     * Every scoped GET under /api/v1 (auth, admin and settings aside): its pattern, the probes of PAY-1300 that `an` sends, and
     * the same request without them.
     */
    static Stream<Arguments> scopedGets() {
        String other = "anken=PAY-1300";
        return Stream.of(
                Arguments.of("/api/v1/anken", List.of("/api/v1/anken?" + other), "/api/v1/anken"),
                Arguments.of("/api/v1/anken/{epic}", List.of("/api/v1/anken/PAY-1300"), "/api/v1/anken/PAY-1100"),
                Arguments.of("/api/v1/anken/{epic}/matrix", List.of("/api/v1/anken/PAY-1300/matrix"), "/api/v1/anken/PAY-1100/matrix"),
                Arguments.of("/api/v1/my-work", List.of("/api/v1/my-work?member=an&" + other, "/api/v1/my-work?member=" + OUTSIDER),
                        "/api/v1/my-work?member=an"),
                Arguments.of("/api/v1/members", List.of("/api/v1/members?" + other), "/api/v1/members"),
                Arguments.of("/api/v1/meta", List.of("/api/v1/meta"), "/api/v1/meta"),
                Arguments.of("/api/v1/sync/runs/latest", List.of("/api/v1/sync/runs/latest"), "/api/v1/sync/runs/latest"),
                Arguments.of("/api/v1/tickets/{key}", List.of("/api/v1/tickets/" + PAY_1300_SUBTASK), "/api/v1/tickets/" + PAY_1100_SUBTASK),
                Arguments.of("/api/v1/tickets/{key}/function", List.of("/api/v1/tickets/" + PAY_1300_SUBTASK + "/function"),
                        "/api/v1/tickets/" + PAY_1100_SUBTASK + "/function"),
                Arguments.of("/api/v1/planning", List.of("/api/v1/planning?start=2026-10-05&" + other), "/api/v1/planning?start=2026-10-05"),
                Arguments.of("/api/v1/effort/timesheet", List.of("/api/v1/effort/timesheet?from=2026-10-05&to=2026-10-11&" + other),
                        "/api/v1/effort/timesheet?from=2026-10-05&to=2026-10-11"),
                Arguments.of("/api/v1/effort/timesheet/{member}/{date}",
                        List.of("/api/v1/effort/timesheet/" + OUTSIDER + "/2026-10-06", "/api/v1/effort/timesheet/binh/2026-10-06?" + other),
                        "/api/v1/effort/timesheet/binh/2026-10-06"),
                Arguments.of("/api/v1/effort/analysis", List.of("/api/v1/effort/analysis?" + other), "/api/v1/effort/analysis"),
                Arguments.of("/api/v1/effort/burndown", List.of("/api/v1/effort/burndown?" + other, "/api/v1/effort/burndown?member=" + OUTSIDER),
                        "/api/v1/effort/burndown"),
                Arguments.of("/api/v1/calendar", List.of("/api/v1/calendar?month=2026-10&" + other), "/api/v1/calendar?month=2026-10"),
                Arguments.of("/api/v1/leaves/export", List.of("/api/v1/leaves/export?month=2026-10&" + other),
                        "/api/v1/leaves/export?month=2026-10"),
                Arguments.of("/api/v1/leaves/template", List.of("/api/v1/leaves/template"), "/api/v1/leaves/template"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedGets")
    void noEndpointLeaksAnotherAnken(String pattern, List<String> probes, String plain) throws Exception {
        for (String url : probes) {
            Res r = call(get(url).with(as(AN)));
            if (r.status() != 404) {
                assertThat(r.status()).as(url + " " + r.text()).isEqualTo(200);
                assertThat(r.text()).as(url).doesNotContain("PAY-13").doesNotContain("\"" + OUTSIDER + "\"").doesNotContain("Zoe Outsider");
            }
        }
        Res r = call(get(plain).with(as(AN)));
        assertThat(r.status()).as(plain + " " + r.text()).isEqualTo(200);
        for (String epic : Dataset.TRACKED_EPICS) {
            if (!epic.equals("PAY-1100")) {
                assertThat(r.text()).as(plain).doesNotContain(epic);
            }
        }
        assertThat(r.text()).as(plain).doesNotContain("\"" + OUTSIDER + "\"").doesNotContain("Zoe Outsider");
    }

    @Test
    void theLeakTestCoversEveryScopedGet() {
        Set<String> listed = new TreeSet<>();
        scopedGets().forEach(a -> listed.add((String) a.get()[0]));
        Set<String> found = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Class<?> api = e.getValue().getBeanType();
            if (api == AuthApi.class || api == AdminApi.class || api == SettingsApi.class
                    || !e.getKey().getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
                continue;
            }
            e.getKey().getPatternValues().stream().filter(p -> p.startsWith("/api/v1/")).forEach(found::add);
        }
        assertThat(listed).isEqualTo(found);
    }

    @Test
    void timesheetRowsArePeopleOfTheScope() throws Exception {
        JsonNode rows = call(get("/api/v1/effort/timesheet").param("from", "2026-10-05").param("to", "2026-10-11").with(as(AN)))
                .json().get("rows");
        List<String> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(r.get("member_id").asText()));
        assertThat(ids).isEqualTo(List.copyOf(new TreeSet<>(ankenMembers.findAll().get("PAY-1100"))));
        List<String> all = new ArrayList<>();
        call(get("/api/v1/effort/timesheet").param("from", "2026-10-05").param("to", "2026-10-11")).json().get("rows")
                .forEach(r -> all.add(r.get("member_id").asText()));
        assertThat(all).contains(OUTSIDER); // the admin sees PAY-1300's people too
    }

    @Test
    void dayDetailListsOnlyWorklogsOnIssuesInScope() throws Exception {
        Issue elsewhere = issues.findAll().stream().filter(i -> !i.epicKey().equals("PAY-1100") && !i.worklogs().isEmpty())
                .filter(i -> ankenMembers.exists("PAY-1100", i.worklogs().get(0).memberId())).findFirst().orElseThrow();
        Worklog w = elsewhere.worklogs().get(0);
        String url = "/api/v1/effort/timesheet/" + w.memberId() + "/" + w.date();
        assertThat(call(get(url)).json().get("entries").toString()).contains(elsewhere.key()); // the admin sees every anken
        Res mine = call(get(url).with(as(AN)));
        assertThat(mine.status()).as(mine.text()).isEqualTo(200);
        assertThat(mine.json().get("entries").toString()).doesNotContain(elsewhere.key());
    }

    @Test
    void cannotAssignANonMember() throws Exception {
        JsonNode e = problem(call(json(patch("/api/v1/tickets/" + PAY_1100_SUBTASK), Map.of("assignee_id", OUTSIDER))), 422, "invalid");
        assertThat(e.get("errors").get(0).get("field").asText()).isEqualTo("assignee_id");
        assertThat(e.get("detail").asText()).isEqualTo("Zoe is not a member of " + titleOf("PAY-1100"));
    }

    @Test
    void planningAndNewFunctionsNeedMembersOfTheAnken() throws Exception {
        JsonNode e = problem(call(json(post("/api/v1/planning/preview"), Map.of("start", "2026-10-05",
                "changes", List.of(Map.of("key", "PAY-1262", "start", "2026-10-11", "member_id", OUTSIDER))))), 422, "invalid");
        assertThat(e.get("errors").get(0).get("field").asText()).isEqualTo("changes.0.member_id");
        assertThat(e.get("detail").asText()).isEqualTo("Zoe is not a member of " + titleOf("PAY-1100"));
        e = problem(call(json(post("/api/v1/anken/PAY-1100/functions/preview"), Map.of("parent_key", "PAY-1102", "name", "Installment",
                "ba", "an", "bal", "minh", "jm", OUTSIDER, "create_hours", 8))), 422, "invalid");
        assertThat(e.get("errors").get(0).get("field").asText()).isEqualTo("jm");
        assertThat(e.get("detail").asText()).isEqualTo("Zoe is not a member of " + titleOf("PAY-1100"));
    }

    @Test
    void writesToAHiddenTicketAre404() throws Exception {
        pimOfPay1100Only();
        problem(call(json(patch("/api/v1/tickets/" + PAY_1300_SUBTASK), Map.of("status", "R")).with(as(PIM))), 404, "not_found");
        problem(call(json(post("/api/v1/planning/preview"), Map.of("start", "2026-10-05",
                "changes", List.of(Map.of("key", PAY_1300_SUBTASK, "start", "2026-10-11", "member_id", "chi")))).with(as(PIM))),
                404, "not_found");
        problem(call(json(post("/api/v1/anken/PAY-1300/functions/preview"), Map.of("parent_key", "PAY-1301", "name", "X",
                "ba", "an", "bal", "minh", "jm", "tanaka", "create_hours", 8)).with(as(PIM))), 404, "not_found");
    }

    @Test
    void pmCannotPushAPlanForAHiddenAnken() throws Exception {
        pimOfPay1100Only();
        JsonNode e = problem(call(json(post("/api/v1/planning/push"),
                Map.of("changes", List.of(Map.of("key", PAY_1300_SUBTASK, "start", "2026-10-12", "member_id", "chi")))).with(as(PIM))),
                404, "not_found");
        assertThat(e.get("detail").asText()).isEqualTo("Ticket " + PAY_1300_SUBTASK + " was not found");
    }

    @Test
    void leaveOfAMemberOutsideScopeIs404() throws Exception {
        pimOfPay1100Only();
        JsonNode e = problem(call(json(post("/api/v1/leaves"), Map.of("member_id", OUTSIDER, "type", "full", "date_from", "2026-11-02",
                "date_to", "2026-11-02", "note", "")).with(as(PIM))), 404, "not_found");
        assertThat(e.get("detail").asText()).isEqualTo("Member " + OUTSIDER + " was not found");
        assertThat(call(json(post("/api/v1/leaves"), Map.of("member_id", "an", "type", "full", "date_from", "2026-11-02",
                "date_to", "2026-11-02", "note", "")).with(as(PIM))).status()).isEqualTo(201);
        Res admin = call(json(post("/api/v1/leaves"), Map.of("member_id", OUTSIDER, "type", "full", "date_from", "2026-11-03",
                "date_to", "2026-11-03", "note", "")));
        assertThat(admin.status()).as(admin.text()).isEqualTo(201);
        int zoes = call(get("/api/v1/calendar").param("month", "2026-11")).json().get("rows").findValues("leave").stream()
                .filter(l -> !l.isNull() && l.get("member_id").asText().equals(OUTSIDER)).findFirst().orElseThrow().get("id").asInt();
        problem(call(json(put("/api/v1/leaves/" + zoes),
                Map.of("member_id", "an", "type", "full", "date_from", "2026-11-04", "date_to", "2026-11-04", "note", "")).with(as(PIM))),
                404, "not_found");
        problem(call(delete("/api/v1/leaves/" + zoes).with(as(PIM))),
                404, "not_found");
    }

    @Test
    void importAndExportCoverOnlyPeopleOfTheScope() throws Exception {
        pimOfPay1100Only();
        call(json(post("/api/v1/leaves"), Map.of("member_id", OUTSIDER, "type", "full", "date_from", "2026-10-26",
                "date_to", "2026-10-26", "note", ""))); // as the admin
        assertThat(call(get("/api/v1/leaves/export").param("month", "2026-10")).text()).contains("Zoe Outsider");
        assertThat(call(get("/api/v1/leaves/export").param("month", "2026-10").with(as(PIM))).text()).doesNotContain("Zoe Outsider");
        JsonNode rows = call(json(post("/api/v1/leaves/import"), Map.of("rows", List.of(
                List.of("Zoe", "2026-12-01", "2026-12-01", "All day", ""), List.of("Chi", "2026-12-01", "2026-12-01", "All day", ""))))
                .with(as(PIM))).json();
        assertThat(rows.get("imported").asInt()).isEqualTo(1);
    }
}
