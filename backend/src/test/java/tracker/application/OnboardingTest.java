package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.OnboardingService;
import tracker.application.service.OnboardingService.EpicLookup;
import tracker.application.service.OnboardingService.MemberHours;
import tracker.application.service.OnboardingService.MemberIn;
import tracker.application.service.OnboardingService.NewTaskIn;
import tracker.application.service.OnboardingService.NewTasksCommon;
import tracker.application.service.OnboardingService.OnboardingView;
import tracker.application.service.OnboardingService.TemplateTask;
import tracker.application.settings.SettingsStore;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Onboarding a new anken: PAY-1500 is an epic of the demo Jira that is not tracked. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OnboardingTest {
    static final String EPIC = "PAY-1500";
    static final AnkenScope SCOPE = new AnkenScope(Set.of(EPIC));
    static final Viewer PIM = new Viewer("minh.hoang@acme.vn", "Minh", "minh", Role.BAL, true, AccessStatus.ACTIVE,
            Permissions.defaults(Role.BAL));
    static final NewTasksCommon COMMON = new NewTasksCommon("minh", "2026-10-12", "2026-10-30", List.of("RFD", " phase "));

    @Autowired
    OnboardingService onboarding;
    @Autowired
    SettingsStore settings;
    @Autowired
    TrackerContextLoader loader;
    @Autowired
    EstimationRepository estimation;
    @Autowired
    MasterScheduleRepository versions;
    @Autowired
    MasterScheduleService schedule;
    @Autowired
    tracker.application.service.JiraSettingsService jiraSettings;

    @Test
    void taskPatternIsSavedWithTheNameRulesAndChecked() {
        var nr = jiraSettings.nameRules(null);
        assertThat(nr.taskRule()).isEqualTo(tracker.adapters.fake.Dataset.TASK_RULE);
        assertThatThrownBy(() -> jiraSettings.saveNameRules(null, nr.rule(), nr.aliases(), "[{anken_prefix}] {sub_phase} {who}"))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("task_rule");
        jiraSettings.saveNameRules(null, nr.rule(), nr.aliases(), "[{anken_prefix}][{os_task}] {sub_phase}");
        assertThat(jiraSettings.nameRules(null).taskRule()).isEqualTo(tracker.domain.TaskNames.DEFAULT_RULE);
        jiraSettings.saveNameRules(null, nr.rule(), nr.aliases(), null); // null keeps it
        assertThat(jiraSettings.nameRules(null).taskRule()).isEqualTo(tracker.domain.TaskNames.DEFAULT_RULE);
    }

    @Test
    void lookupFindsAnEpicInJira() {
        EpicLookup e = onboarding.lookup(" pay-1500 ");
        assertThat(e.key()).isEqualTo(EPIC);
        assertThat(e.title()).isEqualTo("Refund automation");
        assertThat(e.tracked()).isFalse();
        assertThat(onboarding.lookup("PAY-1100").tracked()).isTrue();
        assertThatThrownBy(() -> onboarding.lookup("PAY-9999")).isInstanceOf(NotFound.class);
    }

    @Test
    void startTracksTheEpicAndMakesThePimAMember() {
        OnboardingView v = onboarding.start(PIM, EPIC);
        assertThat(v.step()).isEqualTo(2);
        assertThat(v.title()).isEqualTo("Refund automation");
        assertThat(v.startedBy()).isEqualTo("minh.hoang@acme.vn");
        assertThat(settings.load().jira.epics).contains(EPIC);
        assertThat(loader.load(SCOPE).isMember(EPIC, "minh")).isTrue();
        assertThat(onboarding.list()).extracting(OnboardingView::epicKey).containsExactly(EPIC);
        assertThat(onboarding.start(PIM, EPIC).startedAt()).isEqualTo(v.startedAt()); // starting again resumes
    }

    @Test
    void progressIsTheFurthestStepReached() {
        onboarding.start(PIM, EPIC);
        assertThat(onboarding.save(PIM, EPIC, 4, "RFD").step()).isEqualTo(4);
        OnboardingView v = onboarding.save(PIM, EPIC, 3, null);
        assertThat(v.step()).isEqualTo(4);
        assertThat(v.ankenPrefix()).isEqualTo("RFD");
        assertThatThrownBy(() -> onboarding.save(PIM, EPIC, 7, null)).isInstanceOf(Invalid.class).extracting("field").isEqualTo("step");
        assertThatThrownBy(() -> onboarding.save(PIM, "PAY-1300", 3, null)).isInstanceOf(NotFound.class);
    }

    @Test
    void templateTasksProposeNamesAndCreateTheMissingOnes() {
        onboarding.start(PIM, EPIC);
        List<TemplateTask> before = onboarding.tasks(EPIC, "RFD").tasks();
        assertThat(before).extracting(TemplateTask::match).containsExactly("[*] QnA management", "[*] Work requests");
        assertThat(before).allMatch(t -> t.existingKey() == null && t.recognised());
        // the names follow the demo's Task pattern "[{anken_prefix}] {sub_phase}"
        assertThat(before).extracting(TemplateTask::name).containsExactly("[RFD] QnA management", "[RFD] Work requests");
        assertThatThrownBy(() -> onboarding.previewTasks(PIM, EPIC, "RFD", COMMON, List.of(new NewTaskIn("[*] QnA management", "QnA"))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("tasks.0.name");
        List<NewTaskIn> in = before.stream().map(t -> new NewTaskIn(t.match(), t.name())).toList();
        var creates = onboarding.previewTasks(PIM, EPIC, "RFD", COMMON, in).creates();
        assertThat(creates).hasSize(2);
        // under the epic, with the labels, Planned Start and End, and Planned End as the Due date
        assertThat(creates.get(0).request().body()).contains("\"parent\":{\"key\":\"PAY-1500\"}", "\"2026-10-12\"",
                "\"duedate\":\"2026-10-30\"", "\"labels\":[\"RFD\",\"phase\"]");
        assertThatThrownBy(() -> onboarding.previewTasks(PIM, EPIC, "RFD", new NewTasksCommon("minh", null, null, List.of("two words")), in))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("creates.0.labels");
        var created = onboarding.createTasks(PIM, EPIC, "RFD", COMMON, in).created();
        assertThat(created).hasSize(2);
        tracker.domain.Issue task = loader.load(SCOPE).issue(created.get("task:0")).orElseThrow();
        assertThat(List.of(task.plannedStart(), task.plannedEnd())).containsExactly("2026-10-12", "2026-10-30");
        assertThat(task.labels()).containsExactly("RFD", "phase");
        assertThat(onboarding.tasks(EPIC, "RFD").tasks()).allMatch(t -> t.existingKey() != null);
        assertThat(onboarding.get(EPIC).ankenPrefix()).isEqualTo("RFD");
        assertThatThrownBy(() -> onboarding.previewTasks(PIM, EPIC, "RFD", COMMON, in)).isInstanceOf(Invalid.class)
                .hasMessageContaining("already");
    }

    @Test
    void aNameItsTemplateWouldNotRecogniseIsStillTheTaskPatternsAndFlagged() {
        onboarding.start(PIM, EPIC);
        var nr = jiraSettings.nameRules(EPIC);
        jiraSettings.saveNameRules(EPIC, nr.rule(), nr.aliases(), "{sub_phase} ({anken_prefix})");
        List<TemplateTask> tasks = onboarding.tasks(EPIC, "RFD").tasks();
        assertThat(tasks).extracting(t -> t.name() + "/" + t.recognised())
                .containsExactly("QnA management (RFD)/false", "Work requests (RFD)/false");
        assertThatThrownBy(() -> onboarding.previewTasks(PIM, EPIC, "RFD", COMMON,
                List.of(new NewTaskIn(tasks.get(0).match(), tasks.get(0).name())))).isInstanceOf(Invalid.class).hasMessageContaining("recognised");
    }

    @Test
    void membersStepAddsPeopleAndTheirHours() {
        onboarding.start(PIM, EPIC);
        List<MemberHours> after = onboarding.saveMembers(PIM, EPIC, List.of(new MemberIn("tanaka", true, 1.0), new MemberIn("minh", false, null)));
        MemberHours tanaka = after.stream().filter(m -> m.memberId().equals("tanaka")).findFirst().orElseThrow();
        assertThat(tanaka.inAnken()).isTrue();
        assertThat(tanaka.hoursPerDay()).isEqualTo(1.0);
        assertThat(after.stream().filter(m -> m.memberId().equals("minh")).findFirst().orElseThrow().inAnken()).isFalse();
        assertThat(loader.load(SCOPE).isMember(EPIC, "tanaka")).isTrue();
        // an already gives 8h a day to PAY-1100 and PAY-1300
        MemberHours an = after.stream().filter(m -> m.memberId().equals("an")).findFirst().orElseThrow();
        assertThat(an.elsewhere()).extracting(h -> h.epicKey() + "=" + h.hours()).containsExactlyInAnyOrder("PAY-1100=5.0", "PAY-1300=3.0");
        assertThat(an.workingDayH()).isEqualTo(8.0);
        assertThatThrownBy(() -> onboarding.saveMembers(PIM, EPIC, List.of(new MemberIn("an", true, 2.0)))).isInstanceOf(Invalid.class);
    }

    @Test
    void membersStepSavesHoursOverTheDayOnceConfirmed() {
        onboarding.start(PIM, EPIC);
        List<MemberHours> after = onboarding.saveMembers(PIM, EPIC, List.of(new MemberIn("an", true, 2.0)), true);
        assertThat(after.stream().filter(m -> m.memberId().equals("an")).findFirst().orElseThrow().hoursPerDay()).isEqualTo(2.0);
        assertThatThrownBy(() -> onboarding.saveMembers(PIM, EPIC, List.of(new MemberIn("an", true, 25.0)), true))
                .isInstanceOf(Invalid.class).hasMessageContaining("between 0 and 24");
    }

    @Test
    void discardKeepsTheEpicTrackedAndDropsTheDrafts() {
        onboarding.start(PIM, EPIC);
        estimation.save(EPIC, List.of(new EstimationRow(0, EPIC, 0, "Prepare", "Study", "Requirement study", null, 8, null, null, null, null, null)));
        onboarding.discard(EPIC);
        assertThat(onboarding.list()).isEmpty();
        assertThat(estimation.findByEpic(EPIC)).isEmpty();
        assertThat(versions.findByEpic(EPIC)).isEmpty();
        assertThat(settings.load().jira.epics).contains(EPIC);
    }

    @Test
    void finishNeedsEveryRowPushed() {
        onboarding.start(PIM, EPIC);
        estimation.save(EPIC, List.of(new EstimationRow(0, EPIC, 0, "Prepare", "Study", "Requirement study", null, 8, null, null, null, null, null)));
        assertThatThrownBy(() -> onboarding.finish(PIM, EPIC)).isInstanceOf(Invalid.class).hasMessageContaining("Push");
        estimation.markPushed(List.of(estimation.findByEpic(EPIC).get(0).id()), "2026-10-07T09:00:00Z");
        OnboardingView v = onboarding.finish(PIM, EPIC);
        assertThat(v.finishedAt()).isNotNull();
        assertThat(v.step()).isEqualTo(6);
    }

    @Test
    void phasesWithoutAStartFollowThePreviousOne() {
        onboarding.start(PIM, EPIC);
        onboarding.saveMembers(PIM, EPIC, List.of(new MemberIn("tanaka", true, 4.0)));
        estimation.save(EPIC, List.of(new EstimationRow(0, EPIC, 0, "Prepare", "Study", "Requirement study", null, 8, null, null, null, null, null),
                new EstimationRow(0, EPIC, 1, "Implement_Coding", "Code", "Refund", null, 8, null, null, null, null, null)));
        var rows = schedule.preview(SCOPE, EPIC, List.of(new PhaseRowIn("Prepare", "2026-10-15", List.of("tanaka")),
                new PhaseRowIn("Implement_Coding", null, List.of("tanaka"))));
        assertThat(rows.get(0).end()).isEqualTo("2026-10-16"); // 8h at 4h a day: Thu and Fri
        assertThat(rows.get(1).start()).isEqualTo("2026-10-19"); // the next working day, after the weekend
        assertThatThrownBy(() -> schedule.preview(SCOPE, EPIC, List.of(new PhaseRowIn("Prepare", null, List.of("tanaka")))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("phases.0.start");
    }
}
