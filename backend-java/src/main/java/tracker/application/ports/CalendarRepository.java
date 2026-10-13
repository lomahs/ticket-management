package tracker.application.ports;

import java.util.List;
import java.util.Map;

import tracker.domain.Holiday;
import tracker.domain.Leave;

/** Leave, public holidays and extra working days. */
public interface CalendarRepository {
    List<Leave> findLeaves();

    /** Stores a leave; a leave with id 0 gets a new id. */
    Leave saveLeave(Leave leave);

    void deleteLeave(int leaveId);

    /** Holidays by date. */
    Map<String, Holiday> findHolidays();

    void saveHoliday(Holiday holiday);

    void deleteHoliday(String date);

    /** Extra working days: date → note. */
    Map<String, String> findExtraDays();

    void saveExtraDay(String date, String note);

    void deleteExtraDay(String date);
}
