package tracker.application.ports;

import java.util.List;
import java.util.Map;
import java.util.Set;

import tracker.domain.AnkenMember;

/** Who works on which anken (epic). */
public interface AnkenMemberRepository {
    /** Ordered by member id. */
    List<AnkenMember> findByEpic(String epic);

    Set<String> findEpicsOf(String memberId);

    /** Epic to member ids. */
    Map<String, Set<String>> findAll();

    boolean exists(String epic, String memberId);

    /** Adding an existing pair changes nothing. */
    void add(String epic, String memberId, String by, String at);

    void remove(String epic, String memberId);

    boolean isEmpty();
}
