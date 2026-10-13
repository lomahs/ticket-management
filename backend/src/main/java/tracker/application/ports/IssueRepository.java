package tracker.application.ports;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import tracker.domain.Issue;

/** Issues with their worklogs and change events. */
public interface IssueRepository {
    List<Issue> findAll();

    List<Issue> findByEpics(Collection<String> epicKeys);

    Optional<Issue> findByKey(String key);

    void save(Issue issue);

    void deleteAll(Collection<String> keys);
}
