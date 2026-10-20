package tracker.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.MemberRepository;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;

@Repository
class JdbcMemberRepository implements MemberRepository {
    private final JdbcClient jdbc;

    JdbcMemberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Member member(ResultSet r, int row) throws SQLException {
        String role = r.getString("role");
        return new Member(r.getString("id"), r.getString("name"), r.getString("full_name"), role != null ? Role.of(role) : null,
                r.getString("country"), r.getString("color"), r.getString("email"), Permission.of(r.getString("permission")),
                r.getBoolean("external"), r.getString("jira_account_id"));
    }

    @Override
    public List<Member> findAll() {
        return jdbc.sql("SELECT * FROM members ORDER BY id").query(JdbcMemberRepository::member).list();
    }

    @Override
    public Optional<Member> findById(String memberId) {
        return jdbc.sql("SELECT * FROM members WHERE id = :id").param("id", memberId).query(JdbcMemberRepository::member).optional();
    }

    @Override
    public Optional<Member> findByJiraAccountId(String accountId) {
        return jdbc.sql("SELECT * FROM members WHERE jira_account_id = :account").param("account", accountId)
                .query(JdbcMemberRepository::member).optional();
    }

    @Override
    public Optional<Member> findByEmail(String email) {
        return jdbc.sql("SELECT * FROM members WHERE email <> '' AND lower(email) = lower(:email) LIMIT 1").param("email", email)
                .query(JdbcMemberRepository::member).optional();
    }

    @Override
    public List<Member> findAllByEmail(String email) {
        return jdbc.sql("SELECT * FROM members WHERE email <> '' AND lower(email) = lower(:email) ORDER BY id").param("email", email)
                .query(JdbcMemberRepository::member).list();
    }

    @Override
    public void save(Member m) {
        jdbc.sql("""
                INSERT INTO members (id, jira_account_id, name, full_name, email, role, country, color, permission, external)
                VALUES (:id, :account, :name, :fullName, :email, :role, :country, :color, :permission, :external)
                ON CONFLICT (id) DO UPDATE SET jira_account_id = excluded.jira_account_id, name = excluded.name,
                    full_name = excluded.full_name, email = excluded.email, role = excluded.role, country = excluded.country,
                    color = excluded.color, permission = excluded.permission, external = excluded.external
                """)
                .param("id", m.id()).param("account", m.jiraAccountId()).param("name", m.name()).param("fullName", m.fullName())
                .param("email", m.email()).param("role", m.role() != null ? m.role().value() : null).param("country", m.country())
                .param("color", m.color()).param("permission", m.permission().value()).param("external", m.external())
                .update();
    }
}
