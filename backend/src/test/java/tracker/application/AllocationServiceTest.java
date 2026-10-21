package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.application.AppError.Invalid;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.service.AllocationService;
import tracker.application.service.AllocationService.AllocationChange;
import tracker.application.service.AllocationService.AllocationTimeline;
import tracker.domain.AccessStatus;
import tracker.domain.Allocation;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Setting and reading a member's allocation timeline, on the demo data (8h days). */
@DemoTest
@DirtiesContext
class AllocationServiceTest {
    static final String X = "PAY-1100";
    static final String Y = "PAY-1300";
    static final String M = "binh";

    @Autowired
    AllocationService service;
    @Autowired
    AllocationRepository allocations;
    @Autowired
    AnkenMemberRepository ankenMembers;

    final Viewer pm = new Viewer("pmv@acme.vn", "pmv", "pmv", Role.PM, false, AccessStatus.ACTIVE, Permissions.defaults(Role.PM), true,
            Set.of());

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM allocations").update();
    }

    void bothMember() {
        ankenMembers.add(X, M, "t", "2026-10-01T00:00:00Z");
        ankenMembers.add(Y, M, "t", "2026-10-01T00:00:00Z");
    }

    @Test
    void setAndReadTimeline() {
        bothMember();
        service.set(pm, M, new AllocationChange("2026-10-01", Map.of(X, 5.0, Y, 3.0)));
        AllocationTimeline t = service.set(pm, M, new AllocationChange("2026-11-02", Map.of(X, 0.0, Y, 8.0)));

        assertThat(t.steps()).hasSize(2);
        assertThat(t.steps().get(0).hours()).containsEntry(X, 5.0).containsEntry(Y, 3.0);
        assertThat(t.steps().get(1).hours()).containsEntry(X, 0.0).containsEntry(Y, 8.0);
        assertThat(t.steps().get(1).setBy()).isEqualTo("pmv@acme.vn");
        assertThat(t.hoursPerDay()).isEqualTo(8.0);
        assertThat(t.epics()).contains(X, Y);
    }

    @Test
    void totalAboveTheWorkingDayIsRefused() {
        bothMember();
        service.set(pm, M, new AllocationChange("2026-10-01", Map.of(X, 5.0)));
        assertThatThrownBy(() -> service.set(pm, M, new AllocationChange("2026-10-01", Map.of(Y, 4.0))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("hours");
        assertThat(allocations.findByMember(M)).containsExactly(new Allocation(M, X, "2026-10-01", 5.0));
    }

    @Test
    void aValueThatCarriesForwardCannotPushALaterDateOver() {
        bothMember();
        service.set(pm, M, new AllocationChange("2026-11-01", Map.of(X, 5.0)));
        assertThatThrownBy(() -> service.set(pm, M, new AllocationChange("2026-10-01", Map.of(Y, 4.0))))
                .isInstanceOf(Invalid.class).hasMessage("Total 9.0h is more than 8.0h a day").extracting("field").isEqualTo("hours");
        assertThat(allocations.findByMember(M)).extracting(Allocation::epicKey).containsExactly(X);
    }

    @Test
    void notAMemberIsRefused() {
        ankenMembers.remove(Y, M);
        assertThatThrownBy(() -> service.set(pm, M, new AllocationChange("2026-10-01", Map.of(Y, 2.0))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("hours." + Y);
        assertThat(allocations.findByMember(M)).isEmpty();
        assertThat(List.of(Y)).doesNotContainAnyElementsOf(service.timeline(pm, M).epics());
    }
}
