package tracker.application.ports;

import java.util.List;

import tracker.domain.ScheduleVersion;

/** The saved master schedules of each anken. */
public interface MasterScheduleRepository {
    /** Oldest first. */
    List<ScheduleVersion> findByEpic(String epic);

    /** Stores a new version (its id is ignored) and returns it with its id. */
    ScheduleVersion add(ScheduleVersion v);

    void deleteByEpic(String epic);
}
