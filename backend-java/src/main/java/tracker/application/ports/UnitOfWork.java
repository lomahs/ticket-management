package tracker.application.ports;

import tracker.application.ports.Repositories.AnkenRepository;
import tracker.application.ports.Repositories.CalendarRepository;
import tracker.application.ports.Repositories.IssueRepository;
import tracker.application.ports.Repositories.MemberRepository;
import tracker.application.ports.Repositories.SettingsRepository;
import tracker.application.ports.Repositories.SyncRunRepository;

/** One transaction: closing it rolls back anything not committed. */
public interface UnitOfWork extends AutoCloseable {
    MemberRepository members();

    IssueRepository issues();

    AnkenRepository anken();

    CalendarRepository calendar();

    SettingsRepository settings();

    SyncRunRepository syncRuns();

    void commit();

    @Override
    void close();
}
