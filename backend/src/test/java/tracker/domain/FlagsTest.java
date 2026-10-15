package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.TODAY;
import static tracker.domain.Fixtures.assigned;
import static tracker.domain.Fixtures.issue;
import static tracker.domain.Fixtures.statusTo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FlagsTest {
    static final Thresholds T = Thresholds.defaults();

    static IssueFlags flags(Issue i, boolean current, String owner) {
        return Flags.issueFlags(i, current, owner, TODAY, T);
    }

    static IssueFlags flags(Issue i) {
        return flags(i, true, "an");
    }

    static List<Issue> five(String fid, String statuses, String owner, String ps, String pe) {
        StepKind[] kinds = StepKind.values();
        String[] who = {owner, "minh", owner, "tanaka", owner};
        List<Issue> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            out.add(issue().key("PAY-" + fid + i).fid(fid).step(kinds[i]).status(Status.of(String.valueOf(statuses.charAt(i))))
                    .assignee(who[i]).ps(ps).pe(pe).build());
        }
        return out;
    }

    static List<Issue> five(String fid, String statuses) {
        return five(fid, statuses, "an", "2026-10-05", "2026-10-09");
    }

    @Test
    void functionsGroupByEpicAndIdInStepOrder() {
        List<Issue> issues = new ArrayList<>(five("F01", "CCWNN"));
        Collections.reverse(issues);
        issues.addAll(five("F02", "CCWNN"));
        issues.add(issue().key("PAY-9").build());
        List<Function> fns = Flags.buildFunctions(issues);
        assertThat(fns).extracting(Function::id).containsExactly("F01", "F02");
        assertThat(fns.get(0).cells()).extracting(Issue::step).containsExactly(StepKind.values());
        assertThat(fns.get(0).ownerId()).isEqualTo("an");
    }

    @Test
    void sameFunctionUnderTwoTasksIsTwoFunctions() {
        List<Issue> issues = new ArrayList<>();
        five("Login", "CNNNN").forEach(i -> issues.add(withParent(i, "PAY-10")));
        five("Login", "CCCCC").forEach(i -> issues.add(withParent(i, "PAY-20")));
        assertThat(Flags.buildFunctions(issues)).extracting(f -> f.parentKey() + "/" + f.id() + "/" + f.current())
                .containsExactly("PAY-10/Login/1", "PAY-20/Login/5");
    }

    static Issue withParent(Issue i, String parent) {
        return new Issue(i.key(), i.project(), i.epicKey(), parent, i.kind(), i.summary(), i.phase(), i.status(), i.assigneeId(),
                i.plannedStart(), i.plannedEnd(), i.estimateH(), i.created(), i.jiraStatus(), i.functionId(), i.functionName(),
                i.step(), i.worklogs(), i.events());
    }

    @Test
    void missingStepLeavesAGapAndCountsAsNotDone() {
        List<Issue> cells = new ArrayList<>(five("F01", "CCCCC"));
        cells.remove(2);
        Function f = Flags.buildFunctions(cells).get(0);
        assertThat(f.cells().get(2)).isNull();
        assertThat(f.current()).isEqualTo(2);
    }

    @Test
    void currentStepIsFirstNotClosed() {
        assertThat(Flags.buildFunctions(five("F01", "CCWNN")).get(0).current()).isEqualTo(2);
        assertThat(Flags.buildFunctions(five("F01", "CCCCC")).get(0).current()).isEqualTo(5);
    }

    @Test
    void functionStateAndGroupStatus() {
        Function done = Flags.buildFunctions(five("F1", "CCCCC")).get(0);
        Function nys = Flags.buildFunctions(five("F2", "NNNNN")).get(0);
        Function resolved = Flags.buildFunctions(five("F3", "CRNNN")).get(0);
        assertThat(Flags.functionState(done, Set.of())).isEqualTo("done");
        assertThat(Flags.groupStatus(done)).isEqualTo("D");
        assertThat(Flags.functionState(nys, Set.of())).isEqualTo("nys");
        assertThat(Flags.groupStatus(nys)).isEqualTo("N");
        assertThat(Flags.groupStatus(resolved)).isEqualTo("D");
        assertThat(Flags.functionState(resolved, Set.of(resolved.cells().get(1).key()))).isEqualTo("delay");
    }

    @Test
    void progressPctWeightsStatuses() {
        assertThat(Flags.progressPct(Flags.buildFunctions(five("F1", "CCRWN")).get(0))).isEqualTo(64);
    }

    @Test
    void functionProgressEarlyWhenMoreDoneThanDue() {
        Function f = Flags.buildFunctions(five("F1", "CCNNN", "an", "2026-10-08", "2026-10-09")).get(0);
        assertThat(Flags.functionProgress(f, TODAY, Set.of())).isEqualTo("early");
        assertThat(Flags.functionProgress(f, TODAY, Set.of(f.cells().get(2).key()))).isEqualTo("late");
    }

    @Test
    void lateOnlyWhenActiveAndPastPlannedEnd() {
        assertThat(flags(issue().pe("2026-10-02").build()).late()).isTrue();
        assertThat(flags(issue().pe("2026-10-02").build()).lateDays()).isEqualTo(3);
        assertThat(flags(issue().pe("2026-10-02").status(Status.RESOLVED).build()).late()).isFalse();
        assertThat(flags(issue().pe("2026-10-02").status(Status.NYS).build(), true, "an").late()).isTrue();
        assertThat(flags(issue().pe("2026-10-02").status(Status.NYS).build(), false, "an").late()).isFalse();
    }

    @Test
    void soonWithinThresholdWorkingDays() {
        assertThat(flags(issue().pe("2026-10-09").build()).soon()).isTrue();
        assertThat(flags(issue().pe("2026-10-13").build()).soon()).isFalse();
    }

    @Test
    void overEstimate() {
        assertThat(flags(issue().est(4).logged(5).build()).over()).isTrue();
        assertThat(flags(issue().est(4).logged(4).build()).over()).isFalse();
    }

    @Test
    void toneFromStatusPlanAndFirstWorklog() {
        assertThat(flags(issue().status(Status.NYS).build()).tone()).isEqualTo("nys");
        assertThat(flags(issue().status(Status.RESOLVED).build()).tone()).isEqualTo("res");
        assertThat(flags(issue().status(Status.CLOSED).build()).tone()).isEqualTo("clo");
        assertThat(flags(issue().pe("2026-10-02").build()).tone()).isEqualTo("delay");
        assertThat(flags(issue().logged(1).logDate("2026-10-06").build()).tone()).isEqualTo("latestart");
        assertThat(flags(issue().logged(1).logDate("2026-10-02").build()).tone()).isEqualTo("early");
        assertThat(flags(issue().logged(1).logDate("2026-10-05").build()).tone()).isEqualTo("ontime");
    }

    @Test
    void issueWithoutPlanDatesIsNeverLate() {
        IssueFlags f = flags(issue().ps(null).pe(null).build());
        assertThat(f.late()).isFalse();
        assertThat(f.tone()).isEqualTo("ontime");
    }

    @Test
    void heldSinceResolved() {
        assertThat(flags(issue().status(Status.RESOLVED).events(statusTo("2026-09-30", "R")).build()).heldDays()).isEqualTo(5);
    }

    @Test
    void heldOnFixStepWhileWithReviewer() {
        Issue i = issue().step(StepKind.FIX_JM).fid("F07").assignee("tanaka").events(assigned("2026-10-01", "dung", "tanaka")).build();
        assertThat(flags(i, true, "dung").heldDays()).isEqualTo(4);
    }

    @Test
    void heldCountsDaysWithTheCurrentAssignee() {
        Issue i = issue().step(StepKind.FIX_BAL).fid("F03").assignee("binh").events(assigned("2026-10-05", "minh", "binh")).build();
        assertThat(flags(i, true, "binh").heldDays()).isEqualTo(2);
        Issue j = issue().step(StepKind.BAL_REVIEW).fid("F05").assignee("minh").events(assigned("2026-10-02", "chi", "minh")).build();
        assertThat(flags(j, false, "chi").heldDays()).isEqualTo(3);
    }

    @Test
    void notHeldWithoutHandoverOrWhenClosed() {
        assertThat(flags(issue().build()).heldDays()).isZero();
        assertThat(flags(issue().status(Status.CLOSED).events(assigned("2026-10-01", "an", "minh")).build()).heldDays()).isZero();
    }

    @Test
    void roundsCountReturnsToOwner() {
        Issue i = issue().step(StepKind.FIX_BAL).fid("F03").assignee("binh").events(
                assigned("2026-09-25", "minh", "binh"), assigned("2026-09-28", "binh", "minh"),
                assigned("2026-09-29", "minh", "binh"), assigned("2026-09-30", "binh", "minh"),
                assigned("2026-10-01", "minh", "binh")).build();
        assertThat(flags(i, true, "binh").rounds()).isEqualTo(3);
    }

    @Test
    void roundsDefaultToOneOnceStarted() {
        assertThat(flags(issue().step(StepKind.FIX_BAL).fid("F1").status(Status.WIP).build(), true, "an").rounds()).isEqualTo(1);
        assertThat(flags(issue().step(StepKind.FIX_BAL).fid("F1").status(Status.NYS).build(), true, "an").rounds()).isZero();
        assertThat(flags(issue().step(StepKind.CREATE).fid("F1").build(), true, "an").rounds()).isZero();
    }

    @Test
    void workerIsReviewerOnReviewStepsAndOwnerOtherwise() {
        assertThat(Flags.workerOf(issue().step(StepKind.BAL_REVIEW).fid("F1").assignee("minh").build(), "an")).isEqualTo("minh");
        assertThat(Flags.workerOf(issue().step(StepKind.FIX_JM).fid("F1").assignee("tanaka").build(), "an")).isEqualTo("an");
        assertThat(Flags.workerOf(issue().assignee("chi").build(), null)).isEqualTo("chi");
    }

    @Test
    void actualSpanFromWorklogsExtendsToTodayWhileInProgress() {
        Issue base = issue().logged(2).logDate("2026-10-02").build();
        List<Worklog> logs = new ArrayList<>(base.worklogs());
        logs.add(new Worklog("2026-10-05", "an", 1));
        assertThat(Flags.actualSpan(base.withWorklogs(logs), TODAY)).containsExactly("2026-10-02", TODAY);
        Issue done = issue().status(Status.CLOSED).logged(2).logDate("2026-10-02").build();
        assertThat(Flags.actualSpan(done, TODAY)).containsExactly("2026-10-02", "2026-10-02");
        assertThat(Flags.actualSpan(issue().build(), TODAY)).isNull();
    }
}
