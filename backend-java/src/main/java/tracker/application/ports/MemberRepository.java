package tracker.application.ports;

import java.util.List;
import java.util.Optional;

import tracker.domain.Member;

public interface MemberRepository {
    List<Member> findAll();

    Optional<Member> findById(String memberId);

    Optional<Member> findByJiraAccountId(String accountId);

    void save(Member member);
}
