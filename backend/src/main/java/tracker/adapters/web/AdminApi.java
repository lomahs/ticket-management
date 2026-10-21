package tracker.adapters.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Open;
import tracker.adapters.web.auth.Open.Rule;
import tracker.application.service.AccessService;
import tracker.application.service.AccessService.Approval;
import tracker.application.service.AccessService.UserPatch;
import tracker.application.service.AccessService.UserRow;
import tracker.domain.AccessRequest;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScopeSetting;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.RequestStatus;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Users, permission overrides and access requests. Admins only. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminApi {
    private final AccessService access;

    public AdminApi(AccessService access) {
        this.access = access;
    }

    public record UserPatchIn(@Nullable String name, @Nullable @Email String email, @Nullable Role role,
                              @Nullable Boolean clearRole, @Nullable Country country, @Nullable Boolean admin,
                              @Nullable AccessStatus access, @Nullable Map<Feature, @Nullable Level> overrides,
                              @Nullable AnkenScopeSetting ankenScope, @Nullable List<String> anken) {
    }

    public record ApproveIn(@Nullable String memberId, @NotNull Role role, @Nullable String name) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/users")
    public List<UserRow> users() {
        return access.users();
    }

    @Open(Rule.ADMIN)
    @PatchMapping("/users/{memberId}")
    public UserRow patchUser(Viewer actor, @PathVariable String memberId, @RequestBody @Valid UserPatchIn b) {
        return access.patchUser(actor, memberId, new UserPatch(b.name(), b.email(), b.role(), Boolean.TRUE.equals(b.clearRole()),
                b.country() == null ? null : b.country().name(), b.admin(), b.access(),
                b.overrides() == null ? Map.of() : b.overrides(), b.ankenScope(), b.anken()));
    }

    @Open(Rule.ADMIN)
    @GetMapping("/access-requests")
    public List<AccessRequest> requests(@RequestParam(required = false) @Pattern(regexp = "^(pending|approved|rejected)$", message = "status must be pending, approved or rejected") @Nullable String status) {
        return access.requests(status == null ? null : RequestStatus.of(status));
    }

    @Open(Rule.ADMIN)
    @PostMapping("/access-requests/{id}/approve")
    public UserRow approve(Viewer actor, @PathVariable int id, @RequestBody @Valid ApproveIn b) {
        return access.approve(actor, id, new Approval(b.memberId(), b.role(), b.name()));
    }

    @Open(Rule.ADMIN)
    @PostMapping("/access-requests/{id}/reject")
    public AccessRequest reject(Viewer actor, @PathVariable int id) {
        return access.reject(actor, id);
    }
}
