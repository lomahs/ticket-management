package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.TODAY;
import static tracker.domain.Fixtures.issue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QaLeaveImportTest {
    static final Map<String, Member> MEMBERS = members();

    private static Map<String, Member> members() {
        Map<String, Member> m = new LinkedHashMap<>();
        m.put("an", Fixtures.member("an", "An", Role.BA, "VN"));
        m.put("binh", Fixtures.member("binh", "Bình", Role.BA, "VN"));
        m.put("minh", Fixtures.member("minh", "Minh", Role.BAL, "VN"));
        m.put("tanaka", Fixtures.member("tanaka", "Tanaka", Role.JM, "JP"));
        return m;
    }

    static Qa.QaContext ctx() {
        List<Issue> cells = List.of(issue().key("PAY-1").fid("F01").step(StepKind.CREATE).pe("2026-10-02").assignee("an").build(),
                issue().key("PAY-2").fid("F02").step(StepKind.BAL_REVIEW).assignee("minh").created("2026-10-01").build(),
                issue().key("PAY-3").fid("F03").step(StepKind.CREATE).assignee("binh").logged(3).logDate("2026-10-06").pe("2026-10-09").build());
        List<Function> fns = Flags.buildFunctions(cells);
        Map<String, IssueFlags> flags = new HashMap<>();
        cells.forEach(c -> flags.put(c.key(), Fixtures.flags(c, true, c.assigneeId())));
        List<Worklog> logs = List.of(new Worklog("2026-10-05", "binh", 8), new Worklog("2026-10-07", "binh", 3),
                new Worklog("2026-09-30", "binh", 8));
        return new Qa.QaContext(fns, flags, MEMBERS, logs, TODAY, 8, List.of(1, 2, 3, 4, 5));
    }

    @Test
    void lateQuestionListsLateSubtasks() {
        Qa.Answer a = Qa.answer(ctx(), "Which functions are late?");
        assertThat(a.text()).isEqualTo("1 sub-tasks are past their plan, across 1 functions. Longest delay: F01 (3 working days).");
        assertThat(a.chips()).containsExactly("PAY-1");
    }

    @Test
    void balQuestionUsesTheMemberWithBalRole() {
        Qa.Answer a = Qa.answer(ctx(), "Which review tickets is Minh holding?");
        assertThat(a.chips()).containsExactly("PAY-2");
        assertThat(a.text()).startsWith("Minh is holding 1 tickets");
    }

    @Test
    void hoursQuestionSumsThisWeeksWorklogsForTheNamedMember() {
        Qa.Answer a = Qa.answer(ctx(), "How many hours has Bình logged this week?");
        assertThat(a.text()).startsWith("Bình has logged 11h / 40h this week (05/10–09/10), 3h today.");
        assertThat(a.chips()).containsExactly("PAY-3");
    }

    @Test
    void unknownQuestion() {
        assertThat(Qa.answer(ctx(), "weather?").text()).contains("did not understand");
    }

    @Test
    void leaveImportValidationMessages() {
        List<Leave> existing = List.of(new Leave(1, "an", "2026-10-16", LeaveType.FULL, ""));
        List<List<String>> rows = List.of(List.of("An", "2026-11-05", "2026-11-06", "All day", "Trip"),
                List.of("Duc", "2026-11-13", "2026-11-13", "Half day", ""), List.of("Chi?", "x", "", "", ""),
                List.of("Bình", "2026/11/25", "2026/11/25", "All day", ""), List.of("Bình", "2026-11-12", "2026-11-11", "All day", ""),
                List.of("Bình", "2026-11-26", "2026-11-27", "Remote", ""), List.of("an", "2026-10-16", "", "half day (tentative)", ""));
        List<LeaveImport.ImportRow> r = LeaveImport.validateRows(rows, MEMBERS, existing);
        assertThat(r).extracting(LeaveImport.ImportRow::error).containsExactly("", "Unknown member \"Duc\"", "Unknown member \"Chi?\"",
                "Invalid date format, use YYYY-MM-DD", "To date is before From date", "Unknown leave type \"Remote\"",
                "Overlaps existing leave on 16/10");
        assertThat(r.get(0).row()).isEqualTo(2);
        assertThat(r.get(0).memberId()).isEqualTo("an");
        assertThat(r.get(0).type()).isEqualTo(LeaveType.FULL);
        assertThat(r.get(0).dateTo()).isEqualTo("2026-11-06");
        assertThat(r.get(6).type()).isEqualTo(LeaveType.HALF_TENTATIVE);
        assertThat(r.get(6).dateTo()).isEqualTo("2026-10-16");
    }
}
