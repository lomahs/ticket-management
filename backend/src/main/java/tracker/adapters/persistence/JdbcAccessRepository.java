package tracker.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import tracker.application.ports.AccessRepository;
import tracker.domain.AccessRequest;
import tracker.domain.AccessStatus;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.RequestStatus;

@Repository
class JdbcAccessRepository implements AccessRepository {
    private final JdbcClient jdbc;

    JdbcAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AccessRequest request(ResultSet r, int row) throws SQLException {
        return new AccessRequest(r.getInt("id"), r.getString("email"), r.getString("name"), r.getString("note"), r.getString("requested_at"),
                RequestStatus.of(r.getString("status")), r.getString("decided_by"), r.getString("decided_at"), r.getString("member_id"));
    }

    @Override
    public Map<String, AccessStatus> findStatuses() {
        Map<String, AccessStatus> out = new HashMap<>();
        jdbc.sql("SELECT member_id, status FROM member_access").query(r -> {
            out.put(r.getString("member_id"), AccessStatus.of(r.getString("status")));
        });
        return out;
    }

    @Override
    public Optional<AccessStatus> findStatus(String memberId) {
        return jdbc.sql("SELECT status FROM member_access WHERE member_id = :id").param("id", memberId).query(String.class).optional()
                .map(AccessStatus::of);
    }

    @Override
    public void saveStatus(String memberId, AccessStatus status) {
        if (status != AccessStatus.ACTIVE && status != AccessStatus.DISABLED) {
            throw new IllegalArgumentException("Only active or disabled can be stored, not " + status);
        }
        jdbc.sql("INSERT INTO member_access (member_id, status) VALUES (:id, :status) ON CONFLICT (member_id) DO UPDATE SET status = excluded.status")
                .param("id", memberId).param("status", status.value()).update();
    }

    @Override
    public Map<String, Map<Feature, Level>> findOverrides() {
        Map<String, Map<Feature, Level>> out = new HashMap<>();
        jdbc.sql("SELECT member_id, feature, level FROM member_permissions").query(r -> {
            out.computeIfAbsent(r.getString("member_id"), k -> new EnumMap<>(Feature.class))
                    .put(Feature.of(r.getString("feature")), Level.of(r.getString("level")));
        });
        return out;
    }

    @Override
    public Map<Feature, Level> findOverrides(String memberId) {
        Map<Feature, Level> out = new EnumMap<>(Feature.class);
        jdbc.sql("SELECT feature, level FROM member_permissions WHERE member_id = :id").param("id", memberId).query(r -> {
            out.put(Feature.of(r.getString("feature")), Level.of(r.getString("level")));
        });
        return out;
    }

    @Override
    public void saveOverride(String memberId, Feature feature, @Nullable Level level) {
        if (level == null) {
            jdbc.sql("DELETE FROM member_permissions WHERE member_id = :id AND feature = :feature").param("id", memberId)
                    .param("feature", feature.value()).update();
            return;
        }
        jdbc.sql("""
                INSERT INTO member_permissions (member_id, feature, level) VALUES (:id, :feature, :level)
                ON CONFLICT (member_id, feature) DO UPDATE SET level = excluded.level
                """).param("id", memberId).param("feature", feature.value()).param("level", level.value()).update();
    }

    @Override
    public AccessRequest addRequest(String email, String name, String note, String at, @Nullable String memberId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO access_requests (email, name, note, requested_at, status, member_id)
                VALUES (:email, :name, :note, :at, :status, :member)
                """).param("email", email).param("name", name).param("note", note).param("at", at)
                .param("status", RequestStatus.PENDING.value()).param("member", memberId).update(keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("No id generated for the access request");
        }
        return findRequest(id.intValue()).orElseThrow();
    }

    @Override
    public Optional<AccessRequest> findRequest(int id) {
        return jdbc.sql("SELECT * FROM access_requests WHERE id = :id").param("id", id).query(JdbcAccessRepository::request).optional();
    }

    @Override
    public Optional<AccessRequest> findLatestRequest(String email) {
        return jdbc.sql("SELECT * FROM access_requests WHERE lower(email) = lower(:email) ORDER BY id DESC LIMIT 1").param("email", email)
                .query(JdbcAccessRepository::request).optional();
    }

    @Override
    public List<AccessRequest> findRequests(@Nullable RequestStatus status) {
        return jdbc.sql("SELECT * FROM access_requests WHERE (:status IS NULL OR status = :status) ORDER BY id DESC")
                .param("status", status != null ? status.value() : null).query(JdbcAccessRepository::request).list();
    }

    @Override
    public void decide(int id, RequestStatus status, String by, String at, @Nullable String memberId) {
        jdbc.sql("UPDATE access_requests SET status = :status, decided_by = :by, decided_at = :at, member_id = :member WHERE id = :id")
                .param("status", status.value()).param("by", by).param("at", at).param("member", memberId).param("id", id).update();
    }
}
