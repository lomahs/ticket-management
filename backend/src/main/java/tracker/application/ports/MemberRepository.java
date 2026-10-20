package tracker.application.ports;

import java.util.List;
import java.util.Optional;

import tracker.domain.Member;

public interface MemberRepository {
    List<Member> findAll();

    Optional<Member> findById(String memberId);

    Optional<Member> findByJiraAccountId(String accountId);

    /** Case-insensitive; a blank e-mail never matches. */
    Optional<Member> findByEmail(String email);

    /** Every member with this e-mail (members may share one), by id; case-insensitive, a blank e-mail never matches. */
    List<Member> findAllByEmail(String email);

    void save(Member member);
}
