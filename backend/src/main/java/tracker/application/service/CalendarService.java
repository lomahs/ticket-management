package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Forbidden;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.SettingsRepository;
import tracker.application.service.PlanningService.DayHead;
import tracker.application.settings.TrackerSettings;
import tracker.domain.AnkenScope;
import tracker.domain.Dates;
import tracker.domain.Feature;
import tracker.domain.Holiday;
import tracker.domain.Leave;
import tracker.domain.LeaveImport.ImportRow;
import tracker.domain.LeaveImport;
import tracker.domain.LeaveType;
import tracker.domain.LeaveTypeInfo;
import tracker.domain.Level;
import tracker.domain.Member;
import tracker.domain.Viewer;
import tracker.domain.WorkCalendar;

/** Team calendar: month view, leave editing, Excel/CSV import and export rows. */
@Service
public class CalendarService {
    private final TrackerContextLoader context;
    private final SettingsRepository settings;
    private final CalendarRepository calendar;

    public CalendarService(TrackerContextLoader context, SettingsRepository settings, CalendarRepository calendar) {
        this.context = context;
        this.settings = settings;
        this.calendar = calendar;
    }

    public static final int MAX_LEAVE_DAYS = 60;

    /** @param kind holiday | leave | null */
    public record CalendarCell(String date, @Nullable String kind, @Nullable String label, @Nullable Leave leave) {
    }

    public record CalendarRow(String memberId, List<CalendarCell> cells, double daysOff) {
    }

    public record CalendarView(String month, List<DayHead> days, List<CalendarRow> rows) {
    }

    /** One row per person of the scope. */
    public CalendarView month(AnkenScope scope, String mon) {
        String today = context.today();
        Map<String, Member> members = context.membersById();
        WorkCalendar cal = context.calendar(settings.load(), members, calendar.findLeaves());
        List<String> days = Dates.monthDays(mon).stream().filter(cal::visible).toList();
        List<CalendarRow> rows = new ArrayList<>();
        for (String mid : context.people(scope)) {
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
        return new CalendarView(mon, days.stream().map(x -> new DayHead(x, x.equals(today), cal.extraDays().get(x))).toList(), rows);
    }

    /** @param editId the leave being edited, or null for new leave */
    public record LeaveDraft(@Nullable Integer editId, String memberId, LeaveType type, String dateFrom, String dateTo, String note) {
    }

    /** Without Calendar write a viewer changes only their own leave: the draft's member and the stored leave's member. */
    private static void ownLeave(Viewer viewer, String memberId) {
        if (!viewer.can(Feature.CALENDAR, Level.WRITE) && (viewer.memberId() == null || !viewer.memberId().equals(memberId))) {
            throw new Forbidden("You can only change your own leave");
        }
    }

    /** Leave is changed only for the people of the scope; anyone else is not found. */
    private static void inScope(List<String> people, String memberId) {
        if (!people.contains(memberId)) {
            throw new NotFound("Member " + memberId + " was not found", "member_id");
        }
    }

    private static Leave stored(List<Leave> leaves, int leaveId) {
        return leaves.stream().filter(lv -> lv.id() == leaveId).findFirst()
                .orElseThrow(() -> new NotFound("Leave " + leaveId + " was not found"));
    }

    /**
     * Stores leave on each working day of the range (non-working days are skipped). Returns the number of days. The draft's
     * member, and the edited leave's, must be people of the scope. Without Calendar write the viewer may only add or edit their
     * own leave.
     */
    @Transactional
    public int saveLeave(AnkenScope scope, LeaveDraft draft, Viewer viewer) {
        List<String> people = context.people(scope);
        inScope(people, draft.memberId());
        ownLeave(viewer, draft.memberId());
        if (draft.dateTo().compareTo(draft.dateFrom()) < 0) {
            throw new Invalid("To date is before From date", "to");
        }
        Map<String, Member> members = context.membersById();
        List<Leave> leaves = calendar.findLeaves();
        WorkCalendar cal = context.calendar(settings.load(), members, leaves);
        Integer editId = draft.editId();
        if (editId != null) {
            String owner = stored(leaves, editId).memberId();
            inScope(people, owner);
            ownLeave(viewer, owner);
            calendar.deleteLeave(editId);
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
                calendar.deleteLeave(old.id());
            }
            int id = old != null ? old.id() : (n == 0 && editId != null && editId != 0 ? editId : 0);
            calendar.saveLeave(new Leave(id, draft.memberId(), x, draft.type(), draft.note()));
            n++;
        }
        return n;
    }

    /** Only leave of the people of the scope; without Calendar write the viewer may only delete their own leave. */
    @Transactional
    public void deleteLeave(AnkenScope scope, int leaveId, Viewer viewer) {
        String owner = stored(calendar.findLeaves(), leaveId).memberId();
        inScope(context.people(scope), owner);
        ownLeave(viewer, owner);
        calendar.deleteLeave(leaveId);
    }

    /** @param workingDays days the valid rows will add */
    public record ImportPreview(List<ImportRow> rows, int workingDays) {
    }

    public record ImportResult(int imported, @Nullable String month) {
    }

    private record Checked(TrackerSettings settings, List<ImportRow> rows) {
    }

    /** Rows for anyone but the people of the scope are rows of an unknown member. */
    private Checked validate(AnkenScope scope, List<? extends List<?>> rows) {
        List<String> people = context.people(scope);
        Map<String, Member> members = new LinkedHashMap<>(context.membersById());
        members.keySet().retainAll(people);
        return new Checked(settings.load(), LeaveImport.validateRows(rows, members, calendar.findLeaves()));
    }

    public ImportPreview importPreview(AnkenScope scope, List<? extends List<?>> rows) {
        Checked c = validate(scope, rows);
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

    /** Adds the leave of the valid rows; invalid rows are skipped. */
    @Transactional
    public ImportResult importConfirm(AnkenScope scope, List<? extends List<?>> rows) {
        Checked c = validate(scope, rows);
        List<ImportRow> ok = c.rows().stream().filter(r -> r.error().isEmpty()).toList();
        List<Integer> workingDays = c.settings().workingHours.days();
        int n = 0;
        for (ImportRow r : ok) {
            for (String x : Dates.daysBetween(r.dateFrom(), r.dateTo())) {
                if (workingDays.contains(Dates.weekday(x))) {
                    calendar.saveLeave(new Leave(0, r.memberId(), x, r.type(), r.note()));
                    n++;
                }
            }
        }
        return new ImportResult(n, ok.isEmpty() ? null : ok.get(0).dateFrom().substring(0, 7));
    }

    /** One exported line: who (member or countries), date, type, note. */
    public record ExportRow(String who, String date, String type, String note) {
    }

    /** Rows for the month: leave of the people of the scope by date, then holidays (who = countries). */
    public List<ExportRow> export(AnkenScope scope, String mon) {
        Map<String, Member> members = context.membersById();
        List<String> people = context.people(scope);
        List<Leave> leaves = new ArrayList<>(calendar.findLeaves().stream()
                .filter(lv -> lv.date().startsWith(mon) && people.contains(lv.memberId())).toList());
        List<Holiday> hols = new ArrayList<>(calendar.findHolidays().values().stream().filter(h -> h.date().startsWith(mon)).toList());
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
