package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CalendarTest {
    static final Map<String, Member> MEMBERS = Map.of(
            "binh", new Member("binh", "Bình", "Trần Văn Bình", Role.BA, "VN", "#000"),
            "tanaka", new Member("tanaka", "Tanaka", "Tanaka Hiroshi", Role.JM, "JP", "#000"));
    static final Map<String, Holiday> HOLIDAYS = holidays();

    private static Map<String, Holiday> holidays() {
        Map<String, Holiday> h = new LinkedHashMap<>();
        h.put("2026-09-21", new Holiday("2026-09-21", List.of("JP"), "Respect for the Aged Day (JP)"));
        h.put("2026-09-22", new Holiday("2026-09-22", List.of("JP"), "Citizens' Holiday (JP)"));
        h.put("2026-09-23", new Holiday("2026-09-23", List.of("JP"), "Autumnal Equinox Day (JP)"));
        h.put("2027-01-01", new Holiday("2027-01-01", List.of("VN", "JP"), "New Year's Day"));
        return h;
    }

    static WorkCalendar cal(List<Leave> leaves, Map<String, String> extra) {
        return new WorkCalendar(List.of(1, 2, 3, 4, 5), extra, HOLIDAYS, MEMBERS, leaves);
    }

    static WorkCalendar cal() {
        return cal(List.of(), Map.of());
    }

    @Test
    void weekendIsOffForEveryone() {
        assertThat(cal().off("2026-10-10", "binh")).isEqualTo("we");
    }

    @Test
    void countryHolidayAppliesOnlyToMembersOfThatCountry() {
        WorkCalendar c = cal();
        assertThat(c.off("2026-09-21", "tanaka")).isEqualTo("Respect for the Aged Day (JP)");
        assertThat(c.off("2026-09-21", "binh")).isNull();
        assertThat(c.off("2027-01-01", "binh")).isEqualTo("New Year's Day");
    }

    @Test
    void fullLeaveBlocksTheDayAndHalfLeaveHalvesCapacity() {
        WorkCalendar c = cal(List.of(new Leave(1, "binh", "2026-10-09", LeaveType.FULL, ""),
                new Leave(2, "binh", "2026-10-20", LeaveType.HALF, "")), Map.of());
        assertThat(c.off("2026-10-09", "binh")).isEqualTo("Bình · All day");
        assertThat(c.off("2026-10-20", "binh")).isNull();
        assertThat(c.capacityFactor("binh", "2026-10-20")).isEqualTo(0.5);
        assertThat(c.capacityFactor("binh", "2026-10-09")).isEqualTo(0);
        assertThat(c.capacityFactor("binh", "2026-10-21")).isEqualTo(1);
    }

    @Test
    void tentativeFullLeaveStillBlocks() {
        WorkCalendar c = cal(List.of(new Leave(1, "binh", "2026-10-22", LeaveType.FULL_TENTATIVE, "")), Map.of());
        assertThat(c.off("2026-10-22", "binh")).isEqualTo("Bình · All day (tentative)");
    }

    @Test
    void extraWorkingDayOpensAWeekend() {
        WorkCalendar c = cal(List.of(), Map.of("2026-10-10", "Release support"));
        assertThat(c.visible("2026-10-10")).isTrue();
        assertThat(c.off("2026-10-10", "binh")).isNull();
        assertThat(c.visible("2026-10-11")).isFalse();
    }

    @Test
    void spanSkipsHolidaysAndRecordsThem() {
        List<DateReason> skipped = new ArrayList<>();
        assertThat(cal().span("2026-09-18", 2, "tanaka", skipped)).containsExactly("2026-09-18", "2026-09-24");
        assertThat(skipped).containsExactly(new DateReason("2026-09-21", "Respect for the Aged Day (JP)"),
                new DateReason("2026-09-22", "Citizens' Holiday (JP)"), new DateReason("2026-09-23", "Autumnal Equinox Day (JP)"));
    }

    @Test
    void spanStartsOnNextWorkingDay() {
        assertThat(cal().span("2026-10-10", 1, "binh")).containsExactly("2026-10-12", "2026-10-12");
    }
}
