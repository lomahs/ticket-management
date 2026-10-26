package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.service.AnkenService;
import tracker.application.service.TicketCheckService;
import tracker.application.service.TicketCheckService.CheckCount;
import tracker.application.service.TicketCheckService.CheckRow;
import tracker.domain.AnkenScope;
import tracker.domain.TicketChecks;

/** Ticket Check and the single tickets of the matrix on the demo data (read only). */
@DemoTest
class TicketCheckServiceTest {
    static final AnkenScope ALL = new AnkenScope(Set.copyOf(Dataset.TRACKED_EPICS));

    @Autowired
    TicketCheckService checks;
    @Autowired
    AnkenService anken;

    static List<String> keysWith(TicketCheckService.TicketCheckView v, String check) {
        return v.rows().stream().filter(r -> r.problems().contains(check)).map(r -> r.card().key()).toList();
    }

    @Test
    void listsEveryCheckAndTheSubTasksWithAProblem() {
        TicketCheckService.TicketCheckView v = checks.view(ALL);
        assertThat(v.counts()).extracting(CheckCount::check).containsExactlyElementsOf(TicketChecks.ALL);
        assertThat(keysWith(v, TicketChecks.NO_LABELS)).containsExactlyInAnyOrder("PAY-1173", "PAY-1188", "PAY-1265");
        assertThat(keysWith(v, TicketChecks.RULE)).contains("PAY-1104", "CRM-412").doesNotContain("PAY-1161", "PAY-1213", "PAY-1171");
        assertThat(v.counts()).allMatch(c -> c.count() == keysWith(v, c.check()).size());
        assertThat(v.rows()).allMatch(r -> !r.problems().isEmpty());
        CheckRow kickOff = v.rows().stream().filter(r -> r.card().key().equals("PAY-1104")).findFirst().orElseThrow();
        assertThat(List.of(kickOff.epicKey(), kickOff.taskKey(), kickOff.taskName())).containsExactly("PAY-1100", "PAY-1101", "Payment flow revision");
        assertThat(v.checked()).isGreaterThan(v.rows().size());
    }

    @Test
    void theScopeNarrowsTheList() {
        TicketCheckService.TicketCheckView one = checks.view(new AnkenScope(Set.of("PAY-1300")));
        assertThat(one.rows()).isNotEmpty().allMatch(r -> r.epicKey().equals("PAY-1300"));
    }

    @Test
    void theMatrixListsSingleTicketsOfTheShownTasks() {
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", List.of("PAY-1160"), null, null);
        assertThat(m.functions()).isEmpty();
        assertThat(m.tickets()).extracting(t -> t.taskKey() + "/" + t.name() + "/" + t.card().key())
                .containsExactly("PAY-1160/Requirement study/PAY-1161", "PAY-1160/Requirement study/PAY-1162",
                        "PAY-1160/Requirement study/PAY-1163", "PAY-1160/Requirement study/PAY-1164");
        assertThat(anken.matrix(ALL, "PAY-1100", null, "Implement_Coding", null).tickets()).isEmpty();
        // a QnA Task's workflow is off: its sub-tasks are single tickets named by their Summary
        assertThat(anken.matrix(ALL, "PAY-1100", List.of("PAY-1165"), null, null).tickets()).extracting(AnkenService.MatrixTicket::name)
                .contains("Q12 · Card limit per transaction");
    }
}
