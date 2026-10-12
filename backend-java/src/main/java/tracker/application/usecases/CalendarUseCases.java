package tracker.application.usecases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.Context;
import tracker.application.Deps;
import tracker.application.ports.UnitOfWork;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Dates;
import tracker.domain.Holiday;
import tracker.domain.Leave;
import tracker.domain.LeaveImport;
import tracker.domain.LeaveImport.ImportRow;
import tracker.domain.LeaveType;
import tracker.domain.LeaveTypeInfo;
import tracker.domain.Member;
import tracker.domain.WorkCalendar;
import tracker.application.usecases.PlanningUseCases.DayHead;

/** Team calendar: month view, leave editing, Excel/CSV import and export rows. */
public final class CalendarUseCases {
    private CalendarUseCases() {
    }

    public static final int MAX_LEAVE_DAYS = 60;

    /** @param kind holiday | leave | null */
    public record CalendarCell(String date, String kind, String label, Leave leave) {
    }

    public record CalendarRow(String memberId, List<CalendarCell> cells, double daysOff) {
    }

    public record CalendarView(String month, List<DayHead> days, List<CalendarRow> rows) {
    }

    public static CalendarView month(Deps d, String mon) {
        String today = d.clock().today();
        Map<String, Member> members;
        WorkCalendar cal;
        try (UnitOfWork u = d.uow().begin()) {
            TrackerSettings settings = u.settings().load();
            members = Context.membersById(u.members().list());
            cal = Context.makeCalendar(u, settings, members, u.calendar().leaves());
        }
        List<String> days = Dates.monthDays(mon).stream().filter(cal::visible).toList();
        List<CalendarRow> rows = new ArrayList<>();
        for (String mid : members.keySet()) {
            List<CalendarCell> cells = new ArrayList<>();
            double off = 0.0;
            for (String x : days) {
                Holiday h = cal.holidayFor(mid, x);
                Leave lv = cal.leave(mid, x);
                if (h != null) {
                    cells.add(new CalendarCell(x, "holiday", h.name(), null));
                } else if (lv != null) {
                    LeaveTypeInfo info = LeaveTypeInfo.of(lv.type());
                    cells.add(new CalendarCell(x, "leave", info.label(), lv));
                    off += info.full() ? 1 : 0.5;
                } else {
                    cells.add(new CalendarCell(x, null, null, null));
                }
            }
            rows.add(new CalendarRow(mid, cells, off));
        }
        WorkCalendar c = cal;
        return new CalendarView(mon, days.stream().map(x -> new DayHead(x, x.equals(today), c.extraDays().get(x))).toList(), rows);
    }

    public record LeaveDraft(Integer editId, String memberId, LeaveType type, String dateFrom, String dateTo, String note) {
    }

    /** Stores leave on each working day of the range (non-working days are skipped). Returns the number of days. */
    public static int saveLeave(Deps d, LeaveDraft draft) {
        if (draft.dateTo().compareTo(draft.dateFrom()) < 0) {
            throw new Invalid("To date is before From date", "to");
        }
        try (UnitOfWork u = d.uow().begin()) {
            TrackerSettings settings = u.settings().load();
            Map<String, Member> members = Context.membersById(u.members().list());
            if (!members.containsKey(draft.memberId())) {
                throw new Invalid("Unknown member " + draft.memberId(), "member");
            }
            List<Leave> leaves = u.calendar().leaves();
            WorkCalendar cal = Context.makeCalendar(u, settings, members, leaves);
            Integer editId = draft.editId();
            if (editId != null) {
                if (leaves.stream().noneMatch(lv -> lv.id() == editId)) {
                    throw new NotFound("Leave " + editId + " was not found");
                }
                u.calendar().deleteLeave(editId);
            }
            Map<String, Leave> existing = new HashMap<>();
            for (Leave lv : leaves) {
                if (editId == null || lv.id() != editId) {
                    existing.put(lv.memberId() + "\u0000" + lv.date(), lv);
                }
            }
            int n = 0;
            for (String x : Dates.daysBetween(draft.dateFrom(), draft.dateTo())) {
                if (n >= MAX_LEAVE_DAYS) {
                    break;
                }
                if (!cal.visible(x)) {
                    continue;
                }
                Leave old = existing.get(draft.memberId() + "\u0000" + x);
                if (old != null) {
                    u.calendar().deleteLeave(old.id());
                }
                int id = old != null ? old.id() : (n == 0 && editId != null && editId != 0 ? editId : 0);
                u.calendar().addLeave(new Leave(id, draft.memberId(), x, draft.type(), draft.note()));
                n++;
            }
            u.commit();
            return n;
        }
    }

    public static void deleteLeave(Deps d, int leaveId) {
        try (UnitOfWork u = d.uow().begin()) {
            if (u.calendar().leaves().stream().noneMatch(lv -> lv.id() == leaveId)) {
                throw new NotFound("Leave " + leaveId + " was not found");
            }
            u.calendar().deleteLeave(leaveId);
            u.commit();
        }
    }

    /** @param workingDays days the valid rows will add */
    public record ImportPreview(List<ImportRow> rows, int workingDays) {
    }

    public record ImportResult(int imported, String month) {
    }

    private record Checked(TrackerSettings settings, List<ImportRow> rows) {
    }

    private static Checked validate(Deps d, List<? extends List<?>> rows) {
        try (UnitOfWork u = d.uow().begin()) {
            TrackerSettings settings = u.settings().load();
            return new Checked(settings, LeaveImport.validateRows(rows, Context.membersById(u.members().list()), u.calendar().leaves()));
        }
    }

    public static ImportPreview importPreview(Deps d, List<? extends List<?>> rows) {
        Checked c = validate(d, rows);
        List<Integer> workingDays = c.settings().workingHours.days();
        int days = 0;
        for (ImportRow r : c.rows()) {
            if (r.error().isEmpty()) {
                for (String x : Dates.daysBetween(r.dateFrom(), r.dateTo())) {
                    if (workingDays.contains(Dates.weekday(x))) {
                        days++;
                    }
                }
            }
        }
        return new ImportPreview(c.rows(), days);
    }

    public static ImportResult importConfirm(Deps d, List<? extends List<?>> rows) {
        Checked c = validate(d, rows);
        List<ImportRow> ok = c.rows().stream().filter(r -> r.error().isEmpty()).toList();
        List<Integer> workingDays = c.settings().workingHours.days();
        int n = 0;
        try (UnitOfWork u = d.uow().begin()) {
            for (ImportRow r : ok) {
                for (String x : Dates.daysBetween(r.dateFrom(), r.dateTo())) {
                    if (workingDays.contains(Dates.weekday(x))) {
                        u.calendar().addLeave(new Leave(0, r.memberId(), x, r.type(), r.note()));
                        n++;
                    }
                }
            }
            u.commit();
        }
        return new ImportResult(n, ok.isEmpty() ? null : ok.get(0).dateFrom().substring(0, 7));
    }

    /** One exported line: who (member or countries), date, type, note. */
    public record ExportRow(String who, String date, String type, String note) {
    }

    /** Rows for the month: leave by date, then holidays (who = countries). */
    public static List<ExportRow> export(Deps d, String mon) {
        Map<String, Member> members;
        List<Leave> leaves;
        List<Holiday> hols;
        try (UnitOfWork u = d.uow().begin()) {
            members = Context.membersById(u.members().list());
            leaves = new ArrayList<>(u.calendar().leaves().stream().filter(lv -> lv.date().startsWith(mon)).toList());
            hols = new ArrayList<>(u.calendar().holidays().values().stream().filter(h -> h.date().startsWith(mon)).toList());
        }
        leaves.sort(Comparator.comparing(Leave::date).thenComparing(Leave::memberId));
        hols.sort(Comparator.comparing(Holiday::date));
        List<ExportRow> out = new ArrayList<>();
        for (Leave lv : leaves) {
            String who = members.containsKey(lv.memberId()) ? members.get(lv.memberId()).fullName() : lv.memberId();
            out.add(new ExportRow(who, lv.date(), LeaveTypeInfo.of(lv.type()).label(), lv.note()));
        }
        for (Holiday h : hols) {
            out.add(new ExportRow(String.join(" ", h.countries()), h.date(), "Holiday", h.name()));
        }
        return out;
    }
}
