package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.AppError.NotFound;
import tracker.application.ports.DailySnapshotRepository;
import tracker.application.service.DailyService;
import tracker.application.service.DailyService.DailyContent;
import tracker.application.service.DailyService.DailyGroup;
import tracker.application.service.DailyService.DailySubPhase;
import tracker.application.service.DailyService.DailyTyped;
import tracker.application.service.DailyService.DailyView;
import tracker.domain.AnkenScope;

/** The Daily Overview on the demo data; captures and notes write, so the context is dropped afterwards. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DailyServiceTest {
    static final AnkenScope ALL = new AnkenScope(Set.copyOf(Dataset.TRACKED_EPICS));

    @Autowired
    DailyService daily;
    @Autowired
    DailySnapshotRepository snapshots;

    static DailyGroup group(DailyContent c, String name) {
        return c.groups().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void todayIsLiveWithStartedPhaseGroupsOnly() {
        DailyView v = daily.view(ALL, "PAY-1100", null);
        assertThat(List.of(v.live(), v.today(), v.autoCapture(), v.content().date())).containsExactly(true, "2026-10-07", "18:00", "2026-10-07");
        assertThat(v.capturedAt()).isNull();
        assertThat(v.content().groups()).extracting(DailyGroup::name, DailyGroup::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Prepare", "current"),
                        org.assertj.core.groups.Tuple.tuple("Implement_Coding", "current"),
                        org.assertj.core.groups.Tuple.tuple("Function Unit Test", "current"));
        assertThat(v.content().pct()).isBetween(1, 99);
        assertThat(v.content().diffH()).isEqualTo(v.content().actualToDateH() - v.content().planToDateH(),
                org.assertj.core.data.Offset.offset(0.011));
        assertThat(v.content().burndowns()).extracting(DailyService.DailyBurndown::phase)
                .containsExactly(null, "Prepare", "Implement_Coding", "Function Unit Test");
    }

    @Test
    void eachTaskWithSubTasksIsRolledUpOnItsOwn() {
        List<DailySubPhase> tasks = daily.view(ALL, "PAY-1100", null).content().groups().stream().flatMap(g -> g.subPhases().stream()).toList();
        assertThat(tasks).isNotEmpty().allMatch(t -> t.total() > 0 && t.closed() <= t.total() && t.pct() >= 0 && t.pct() <= 100)
                .allMatch(t -> List.of("done", "current", "not_started").contains(t.state()));
        DailyGroup coding = group(daily.view(ALL, "PAY-1100", null).content(), "Implement_Coding");
        assertThat(coding.subPhases().stream().mapToInt(DailySubPhase::total).sum()).isEqualTo(coding.total());
    }

    @Test
    void studyTicketsThatShareANameAreSingleTicketsEach() {
        List<DailySubPhase> prepare = group(daily.view(ALL, "PAY-1100", null).content(), "Prepare").subPhases();
        assertThat(prepare).extracting(DailySubPhase::key).containsExactly("PAY-1101", "PAY-1160"); // typed Tasks are not listed
        DailySubPhase study = prepare.get(1);
        assertThat(study.name()).isEqualTo("Requirement study");
        assertThat(study.functions()).isEmpty();
        assertThat(study.tickets()).extracting(t -> t.key() + "/" + t.name() + "/" + t.assigneeId())
                .containsExactly("PAY-1161/Requirement study/an", "PAY-1162/Requirement study/binh", "PAY-1163/Requirement study/chi",
                        "PAY-1164/Requirement study/dung");
        assertThat(prepare.get(0).unmatched()).isPositive(); // "Kick-off with JM" and the rest do not match the name rule
    }

    @Test
    void functionsHaveFiveStepSlotsWithLateAndRounds() {
        DailySubPhase coding = group(daily.view(ALL, "PAY-1100", null).content(), "Implement_Coding").subPhases().get(0);
        assertThat(coding.functions()).hasSize(12).allMatch(f -> f.steps().size() == 5);
        DailyService.DailyFunction f03 = coding.functions().stream().filter(f -> f.id().equals("F03")).findFirst().orElseThrow();
        assertThat(f03.baId()).isEqualTo("binh");
        DailyService.DailyStep fixBal = f03.steps().get(2);
        assertThat(List.of(fixBal.step(), fixBal.late(), fixBal.rounds())).containsExactly("fixb", true, 3);
        assertThat(coding.functions().stream().flatMap(f -> f.steps().stream()).filter(Objects::nonNull)
                .filter(s -> s.step().equals("jmr")).allMatch(s -> s.withJm())).isTrue();
    }

    @Test
    void typedTicketsAreListedByTypeWithWhereTheyAreAndTheirNote() {
        List<DailyTyped> typed = daily.view(ALL, "PAY-1100", null).content().typed();
        assertThat(typed).extracting(t -> t.type() + "/" + t.key() + "/" + (t.withJm() ? "JM" : "Team"))
                .containsExactly("qna/PAY-1170/JM", "qna/PAY-1171/JM", "qna/PAY-1172/JM", "qna/PAY-1173/Team", "qna/PAY-1168/Team",
                        "work_request/PAY-1176/Team", "work_request/PAY-1177/JM");
        DailyTyped closed = typed.get(4);
        assertThat(List.of(closed.status(), Objects.toString(closed.closedOn()))).containsExactly("C", "2026-10-05");
        assertThat(typed.get(1).note()).isEqualTo(Dataset.NOTES.get("PAY-1171"));
        assertThat(typed.get(2).note()).isEmpty();
    }

    @Test
    void aNoteIsSavedAndABlankOneRemoved() {
        daily.saveNote(ALL, "PAY-1172", "  Ask again on Friday ", "minh");
        assertThat(daily.view(ALL, "PAY-1100", null).content().typed()).filteredOn(t -> t.key().equals("PAY-1172"))
                .extracting(DailyTyped::note).containsExactly("Ask again on Friday");
        daily.saveNote(ALL, "PAY-1172", " ", "minh");
        assertThat(daily.view(ALL, "PAY-1100", null).content().typed()).filteredOn(t -> t.key().equals("PAY-1172"))
                .extracting(DailyTyped::note).containsExactly("");
        assertThatThrownBy(() -> daily.saveNote(new AnkenScope(Set.of("PAY-1300")), "PAY-1172", "x", "an")).isInstanceOf(NotFound.class);
    }

    @Test
    void aCaptureFreezesTheDayAndAPastDayIsReadBack() {
        DailyView captured = daily.capture(ALL, "PAY-1100", "minh");
        assertThat(captured.live()).isTrue();
        assertThat(captured.capturedAt()).startsWith("2026-10-07T");
        assertThat(captured.snapshots()).extracting(DailyService.SnapshotOption::date, DailyService.SnapshotOption::capturedBy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2026-10-07", "minh"));
        assertThat(daily.uncaptured()).doesNotContain("PAY-1100").contains("PAY-1300");

        // a past day: the stored content, notes as they were, and nothing recomputed
        DailyContent then = captured.content();
        snapshots.save("PAY-1100", "2026-10-06", "2026-10-06T18:00:00+07:00", "auto", then);
        daily.saveNote(ALL, "PAY-1171", "changed later", "minh");
        DailyView past = daily.view(ALL, "PAY-1100", "2026-10-06");
        assertThat(List.of(past.live(), past.capturedBy())).containsExactly(false, "auto");
        assertThat(past.content()).isEqualTo(then);
        assertThat(past.snapshots()).extracting(DailyService.SnapshotOption::date).containsExactly("2026-10-07", "2026-10-06");
        assertThatThrownBy(() -> daily.view(ALL, "PAY-1100", "2026-10-01")).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> daily.view(new AnkenScope(Set.of("PAY-1300")), "PAY-1100", null)).isInstanceOf(NotFound.class);
    }
}
