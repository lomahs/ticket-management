package tracker.application.ports;

import java.util.Collection;
import java.util.List;

import tracker.domain.Anken;

public interface AnkenRepository {
    List<Anken> findAll();

    void save(Anken anken);

    void deleteAllExcept(Collection<String> keys);
}
