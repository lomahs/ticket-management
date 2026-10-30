package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.MEMBERS;
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

    static IssueFlags flags(Issue i, boolean current, String owner) {
        return Fixtures.flags(i, current, owner);
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

    static Function fn(Issue... cells) {
        return Flags.buildFunctions(List.of(cells)).get(0);
    }

    @Test
    void baIsCreatesAssigneeWithTheBaRole() {
        Function f = fn(issue().key("A1").fid("F1").step(StepKind.CREATE).assignee("an").build());
        assertThat(f.baId(MEMBERS)).isEqualTo("an");
        assertThat(f.handoverDay(MEMBERS)).isNull();
    }

    @Test
    void baSurvivesCreateGoingBackToPim() {
        Function f = fn(issue().key("A1").fid("F1").step(StepKind.CREATE).assignee("pim").status(Status.RESOLVED)
                .events(assigned("2026-10-06", "an", "pim")).build());
        assertThat(f.baId(MEMBERS)).isEqualTo("an");
        assertThat(f.handoverDay(MEMBERS)).isNull();
    }

    @Test
    void baIsTheLatestBaAfterAHandover() {
        Function f = fn(issue().key("A1").fid("F1").step(StepKind.CREATE).assignee("binh")
                .events(assigned("2026-10-05T09:00", "an", "binh")).build());
        assertThat(f.baId(MEMBERS)).isEqualTo("binh");
        assertThat(f.handoverDay(MEMBERS)).isEqualTo("2026-10-05");
        // lent to the BAL and back: no handover
        Function lent = fn(issue().key("B1").fid("F2").step(StepKind.CREATE).assignee("an")
                .events(assigned("2026-10-02", "an", "minh"), assigned("2026-10-03", "minh", "an")).build());
        assertThat(lent.baId(MEMBERS)).isEqualTo("an");
        assertThat(lent.handoverDay(MEMBERS)).isNull();
        // no BA ever held it
        assertThat(fn(issue().key("C1").fid("F3").step(StepKind.CREATE).assignee("pim").build()).baId(MEMBERS)).isNull();
    }

    @Test
    void balIsCreatesReporterWithTheBalRole() {
        Issue review = issue().key("A2").fid("F1").step(StepKind.BAL_REVIEW).assignee("an").build();
        assertThat(fn(issue().key("A1").fid("F1").step(StepKind.CREATE).reporter("minh").build(), review).balId(MEMBERS))
                .isEqualTo("minh");
        Issue reviewToMinh = issue().key("B2").fid("F2").step(StepKind.BAL_REVIEW).assignee("an")
                .events(assigned("2026-10-02", "pim", "minh"), assigned("2026-10-03", "minh", "an")).build();
        assertThat(fn(issue().key("B1").fid("F2").step(StepKind.CREATE).reporter("pim").build(), reviewToMinh).balId(MEMBERS))
                .isEqualTo("minh");
        assertThat(fn(issue().key("C1").fid("F3").step(StepKind.CREATE).reporter("pim").build(),
                issue().key("C2").fid("F3").step(StepKind.BAL_REVIEW).assignee("an").build()).balId(MEMBERS)).isNull();
    }

    @Test
    void aFunctionWithoutJmStepsHasThreeSteps() {
        List<Issue> three = five("F1", "CCCNN").subList(0, 3);
        Function f = Flags.buildFunctions(three).get(0);
        assertThat(f.stepCount()).isEqualTo(3);
        assertThat(f.steps()).hasSize(3);
        assertThat(f.current()).isEqualTo(3);
        assertThat(f.done()).isTrue();
        assertThat(Flags.buildFunctions(five("F2", "CCWNN").subList(0, 3)).get(0).done()).isFalse();
    }

    @Test
    void aFunctionMissingOnlyFixJmStillHasFiveSteps() {
        Function f = Flags.buildFunctions(five("F1", "CCCCN").subList(0, 4)).get(0);
        assertThat(f.stepCount()).isEqualTo(5);
        assertThat(f.steps()).hasSize(5);
        assertThat(f.current()).isEqualTo(4);
        assertThat(f.done()).isFalse();
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
                i.plannedStart(), i.plannedEnd(), i.estimateH(), i.remainingH(), i.created(), i.jiraStatus(), i.functionId(), i.functionName(),
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
        // (1 + 1 + 0.8 + 0) / 4 team steps; JM Review (W) is left out
        assertThat(Flags.progressPct(Flags.buildFunctions(five("F1", "CCRWN")).get(0))).isEqualTo(70);
    }

    @Test
    void functionProgressEarlyWhenMoreDoneThanDue() {
        Function f = Flags.buildFunctions(five("F1", "CCNNN", "an", "2026-10-08", "2026-10-09")).get(0);
        assertThat(Flags.functionProgress(f, TODAY, Set.of())).isEqualTo("early");
        assertThat(Flags.functionProgress(f, TODAY, Set.of(f.cells().get(2).key()))).isEqualTo("late");
    }

    @Test
    void lateWhenOpenAndPastPlannedEnd() {
        assertThat(flags(issue().pe("2026-10-02").build()).late()).isTrue();
        assertThat(flags(issue().pe("2026-10-02").build()).lateDays()).isEqualTo(3);
        assertThat(flags(issue().pe("2026-10-02").status(Status.RESOLVED).build()).late()).isFalse();
        assertThat(flags(issue().pe("2026-10-02").status(Status.CLOSED).build()).late()).isFalse();
        assertThat(flags(issue().pe("2026-10-02").status(Status.NYS).build(), true, "an").late()).isTrue();
        assertThat(flags(issue().pe(TODAY).build()).late()).isFalse(); // due today: not yet overdue
        assertThat(flags(issue().pe(TODAY).build()).lateDays()).isZero();
    }

    @Test
    void lateIsOverdue() {
        // a step that is not the function's current one is judged on its own dates too
        IssueFlags f = flags(issue().ps("2026-09-30").pe("2026-10-02").status(Status.NYS).build(), false, "an");
        assertThat(f.late()).isTrue();
        assertThat(f.lateDays()).isEqualTo(3);
        assertThat(f.soon()).isFalse();
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
    void toneFollowsTheLabels() {
        // status R / C first, even past the planned end
        assertThat(flags(issue().status(Status.RESOLVED).pe("2026-10-02").build()).tone()).isEqualTo("res");
        assertThat(flags(issue().status(Status.CLOSED).pe("2026-10-02").build()).tone()).isEqualTo("clo");
        // finish overdue, also for a not-started issue, before a late start
        assertThat(flags(issue().ps("2026-09-30").pe("2026-10-02").logged(1).logDate("2026-10-01").build()).tone()).isEqualTo("delay");
        assertThat(flags(issue().status(Status.NYS).ps("2026-09-30").pe("2026-10-02").build(), false, "an").tone()).isEqualTo("delay");
        // start late_start: started after the planned start, or not started and past it
        assertThat(flags(issue().logged(1).logDate("2026-10-06").build()).tone()).isEqualTo("latestart");
        assertThat(flags(issue().status(Status.NYS).build()).tone()).isEqualTo("latestart");
        // start early_start
        assertThat(flags(issue().logged(1).logDate("2026-10-02").build()).tone()).isEqualTo("early");
        // status NYS: not due yet, or no plan
        assertThat(flags(issue().status(Status.NYS).ps("2026-10-08").pe("2026-10-09").build()).tone()).isEqualTo("nys");
        assertThat(flags(issue().status(Status.NYS).ps(TODAY).pe("2026-10-09").build()).tone()).isEqualTo("nys");
        assertThat(flags(issue().status(Status.NYS).ps(null).pe(null).build()).tone()).isEqualTo("nys");
        // otherwise
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
    void roundsCountBalReviewRuns() {
        Issue i = issue().step(StepKind.FIX_BAL).fid("F03").assignee("binh")
                .log("2026-09-25", "binh", 2).log("2026-09-28", "binh", 1).log("2026-09-29", "minh", 0.5)
                .log("2026-09-30", "binh", 1).log("2026-10-01", "minh", 0.5).log("2026-10-02", "binh", 1).build();
        assertThat(flags(i, true, "binh").rounds()).isEqualTo(3);
        Issue baOnly = issue().step(StepKind.FIX_BAL).fid("F03").assignee("binh").log("2026-10-01", "binh", 2).build();
        assertThat(flags(baOnly, true, "binh").rounds()).isEqualTo(1);
        // same day: the sync order decides
        Issue sameDay = issue().step(StepKind.FIX_JM).fid("F03").assignee("binh")
                .log("2026-10-01", "binh", 1).log("2026-10-01", "minh", 0.5).log("2026-10-02", "binh", 1).build();
        assertThat(flags(sameDay, true, "binh").rounds()).isEqualTo(2);
    }

    @Test
    void roundsDefaultToOneOnceStarted() {
        assertThat(flags(issue().step(StepKind.FIX_BAL).fid("F1").status(Status.WIP).build(), true, "an").rounds()).isEqualTo(1);
        assertThat(flags(issue().step(StepKind.FIX_BAL).fid("F1").status(Status.NYS).build(), true, "an").rounds()).isZero();
        assertThat(flags(issue().step(StepKind.CREATE).fid("F1").log("2026-10-01", "minh", 1).build(), true, "an").rounds()).isZero();
    }

    @Test
    void progressLeavesJmReviewOut() {
        assertThat(Flags.progressPct(Flags.buildFunctions(five("F1", "CCCWN")).get(0))).isEqualTo(75);
        assertThat(Flags.progressPct(Flags.buildFunctions(five("F2", "CRNNN").subList(0, 3)).get(0))).isEqualTo(60);
    }

    @Test
    void workerIsReviewerOnReviewStepsAndOwnerOtherwise() {
        assertThat(Flags.workerOf(issue().step(StepKind.BAL_REVIEW).fid("F1").assignee("minh").build(), "an")).isEqualTo("minh");
        assertThat(Flags.workerOf(issue().step(StepKind.FIX_JM).fid("F1").assignee("tanaka").build(), "an")).isEqualTo("an");
        assertThat(Flags.workerOf(issue().assignee("chi").build(), null)).isEqualTo("chi");
    }

    @Test
    void actualSpanFromWorklogsHasNoEndWhileInProgress() {
        Issue base = issue().logged(2).logDate("2026-10-02").build();
        List<Worklog> logs = new ArrayList<>(base.worklogs());
        logs.add(new Worklog("2026-10-05", "an", 1));
        assertThat(Flags.actualSpan(base.withWorklogs(logs))).containsExactly("2026-10-02", null);
        Issue done = issue().status(Status.CLOSED).logged(2).logDate("2026-10-02").build();
        assertThat(Flags.actualSpan(done.withWorklogs(List.of(new Worklog("2026-10-02", "an", 1), new Worklog("2026-10-04", "an", 1)))))
                .containsExactly("2026-10-02", "2026-10-04");
        assertThat(Flags.actualSpan(issue().build())).isNull();
    }

    @Test
    void actualStartIsTheMoveToInProgressAndEndTheMoveToResolved() {
        // moved to In Progress in the morning, first worklog only at the end of the next day
        Issue wip = issue().logged(2).logDate("2026-10-06").events(new IssueEvent("2026-10-05", EventField.STATUS, "N", "W")).build();
        assertThat(Flags.actualSpan(wip)).containsExactly("2026-10-05", null);
        Issue started = issue().logged(0).events(new IssueEvent("2026-10-07", EventField.STATUS, "N", "W")).build();
        assertThat(Flags.actualSpan(started)).containsExactly("2026-10-07", null);
        // a worklog before the move counts as the start
        Issue early = issue().logged(1).logDate("2026-10-01").events(new IssueEvent("2026-10-02", EventField.STATUS, "N", "W")).build();
        assertThat(Flags.actualStart(early)).isEqualTo("2026-10-01");
        // resolved, reopened and resolved again: the last resolve ends it, even with a later worklog
        Issue resolved = issue().status(Status.CLOSED).logged(3).logDate("2026-10-06").events(
                new IssueEvent("2026-09-28", EventField.STATUS, "N", "W"), statusTo("2026-09-30", "R"),
                new IssueEvent("2026-10-01", EventField.STATUS, "R", "W"), statusTo("2026-10-02", "R"),
                new IssueEvent("2026-10-03", EventField.STATUS, "R", "C")).build();
        assertThat(Flags.actualSpan(resolved)).containsExactly("2026-09-28", "2026-10-02");
        Issue closedOnly = issue().status(Status.CLOSED).logged(0).events(new IssueEvent("2026-10-03", EventField.STATUS, "N", "C")).build();
        assertThat(Flags.actualSpan(closedOnly)).containsExactly("2026-10-03", "2026-10-03");
    }

    @Test
    void lateStartToneUsesTheMoveToInProgress() {
        Issue i = issue().ps("2026-10-05").pe("2026-10-09").logged(1).logDate("2026-10-06")
                .events(new IssueEvent("2026-10-05", EventField.STATUS, "N", "W")).build();
        assertThat(flags(i).tone()).isEqualTo("ontime");
    }
}
