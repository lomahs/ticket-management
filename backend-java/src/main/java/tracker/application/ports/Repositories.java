package tracker.application.ports;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.Holiday;
import tracker.domain.Issue;
import tracker.domain.Leave;
import tracker.domain.Member;

/** Repository ports. */
public final class Repositories {
    private Repositories() {
    }

    public interface MemberRepository {
        List<Member> list();

        Member get(String memberId);

        Member byAccount(String accountId);

        void save(Member member);
    }

    public interface IssueRepository {
        List<Issue> list();

        List<Issue> list(Collection<String> epicKeys);

        Issue get(String key);

        void save(Issue issue);

        void delete(Collection<String> keys);
    }

    public interface AnkenRepository {
        List<Anken> list();

        void save(Anken anken);

        void deleteExcept(Collection<String> keys);
    }

    public interface CalendarRepository {
        List<Leave> leaves();

        /** Stores a leave; a leave with id 0 gets a new id. */
        Leave addLeave(Leave leave);

        void deleteLeave(int leaveId);

        Map<String, Holiday> holidays();

        void saveHoliday(Holiday holiday);

        void deleteHoliday(String date);

        Map<String, String> extraDays();

        void saveExtraDay(String date, String note);

        void deleteExtraDay(String date);
    }

    public interface SettingsRepository {
        TrackerSettings load();

        void save(TrackerSettings settings);
    }

    /** @param status running | ok | failed */
    public record SyncRun(int id, Instant startedAt, Instant finishedAt, boolean full, String status, int issues, String error) {
    }

    public interface SyncRunRepository {
        int start(boolean full, Instant at);

        void finish(int runId, Instant at, String status, int issues, String error);

        SyncRun latest();

        SyncRun latestOk();
    }
}
