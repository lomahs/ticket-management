package tracker.domain;

import org.jspecify.annotations.Nullable;

/** A person's request for access; memberId is the member it is for (matched by e-mail, or chosen at approval). */
public record AccessRequest(int id, String email, String name, String note, String requestedAt, RequestStatus status,
        @Nullable String decidedBy, @Nullable String decidedAt, @Nullable String memberId) {
}
