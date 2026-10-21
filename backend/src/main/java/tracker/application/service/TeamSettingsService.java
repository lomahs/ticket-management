package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.ports.CalendarRepository;
import tracker.application.settings.SettingsStore;
import tracker.domain.Dates;
import tracker.domain.Holiday;
import tracker.domain.Text;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;

/** Team settings: members, public holidays, extra working days, working hours and alert thresholds. */
@Service
public class TeamSettingsService {
    public static final List<String> COUNTRIES = List.of("VN", "JP");

    private final SettingsStore settings;
    private final CalendarRepository calendar;

    public TeamSettingsService(SettingsStore settings, CalendarRepository calendar) {
        this.settings = settings;
        this.calendar = calendar;
    }

    // --- calendar ---------------------------------------------------------------------------------------------------

    public List<Holiday> holidays() {
        List<Holiday> out = new ArrayList<>(calendar.findHolidays().values());
        out.sort(Comparator.comparing(Holiday::date));
        return out;
    }

    @Transactional
    public List<Holiday> addHoliday(String date, String name, List<String> countries) {
        if (name.isBlank()) {
            throw new Invalid("Name is required", "name");
        }
        if (countries.isEmpty() || !COUNTRIES.containsAll(countries)) {
            throw new Invalid("Choose Vietnam, Japan or both", "countries");
        }
        calendar.saveHoliday(new Holiday(date, countries, name.strip()));
        return holidays();
    }

    @Transactional
    public List<Holiday> deleteHoliday(String date) {
        calendar.deleteHoliday(date);
        return holidays();
    }

    /** Extra working days by date. */
    public Map<String, String> extraDays() {
        return new LinkedHashMap<>(new TreeMap<>(calendar.findExtraDays()));
    }

    @Transactional
    public Map<String, String> addExtraDay(String date, String note) {
        if (settings.load().workingHours.days().contains(Dates.weekday(date))) {
            throw new Invalid(Dates.dm(date) + " is already a working day", "date");
        }
        if (calendar.findExtraDays().containsKey(date)) {
            throw new Conflict(Dates.dm(date) + " is already in the list", "date");
        }
        calendar.saveExtraDay(date, Text.orElse(note.strip(), "Extra working day"));
        return extraDays();
    }

    @Transactional
    public Map<String, String> deleteExtraDay(String date) {
        calendar.deleteExtraDay(date);
        return extraDays();
    }

    // --- working hours & alerts -------------------------------------------------------------------------------------

    public WorkingHours workingHours() {
        return settings.load().workingHours;
    }

    /** @param wh days are 0 (Sunday) … 6 (Saturday) */
    public WorkingHours saveWorkingHours(WorkingHours wh) {
        if (!(0 < wh.hoursPerDay() && wh.hoursPerDay() <= 24)) {
            throw new Invalid("Hours per working day must be between 0 and 24", "hours_per_day");
        }
        if (wh.timesheetToleranceH() < 0) {
            throw new Invalid("Tolerance cannot be negative", "timesheet_tolerance_h");
        }
        if (wh.days().isEmpty() || wh.days().stream().anyMatch(x -> x < 0 || x > 6)) {
            throw new Invalid("Choose at least one working day", "days");
        }
        WorkingHours saved = new WorkingHours(wh.hoursPerDay(), List.copyOf(new TreeSet<>(wh.days())), wh.timesheetToleranceH());
        settings.update(s -> s.workingHours = saved);
        return saved;
    }

    public Thresholds alerts() {
        return settings.load().thresholds;
    }

    public Thresholds saveAlerts(Thresholds t) {
        if (t.soonDays() < 0 || t.effortWarnPct() < 0 || t.heldDays() < 0 || t.reviewRounds() < 0 || t.capacityPct() < 0 || t.effortDiffH() < 0) {
            throw new Invalid("Thresholds cannot be negative");
        }
        settings.update(s -> s.thresholds = t);
        return t;
    }
}
