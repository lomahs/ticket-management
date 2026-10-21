package tracker.application.ports;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import tracker.domain.AccessRequest;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScopeSetting;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.RequestStatus;

/** Sign-in status, per-member permission overrides and access requests. */
public interface AccessRepository {
    /** Member id to ACTIVE or DISABLED; a missing key means none. */
    Map<String, AccessStatus> findStatuses();

    Optional<AccessStatus> findStatus(String memberId);

    /** Only ACTIVE and DISABLED can be stored; anything else throws IllegalArgumentException. */
    void saveStatus(String memberId, AccessStatus status);

    Map<String, Map<Feature, Level>> findOverrides();

    Map<Feature, Level> findOverrides(String memberId);

    /** A null level removes the override. */
    void saveOverride(String memberId, Feature feature, @Nullable Level level);

    AccessRequest addRequest(String email, String name, String note, String at, @Nullable String memberId);

    Optional<AccessRequest> findRequest(int id);

    Optional<AccessRequest> findLatestRequest(String email);

    /** Newest first; null status means all. */
    List<AccessRequest> findRequests(@Nullable RequestStatus status);

    void decide(int id, RequestStatus status, String by, String at, @Nullable String memberId);

    /** DEFAULT when the member has no row. */
    AnkenScopeSetting findAnkenScope(String memberId);

    /** Member id to setting, only for members with a non-default one. */
    Map<String, AnkenScopeSetting> findAnkenScopes();

    /** Saving DEFAULT removes the row. */
    void saveAnkenScope(String memberId, AnkenScopeSetting s);
}
