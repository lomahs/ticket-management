package tracker.adapters.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.AppError.Invalid;
import tracker.application.service.AnkenMembersService;
import tracker.application.service.AnkenMembersService.AnkenMembersView;
import tracker.application.service.AnkenMembersService.NewPerson;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Member;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** The people of each anken, and new people. */
@RestController
@RequestMapping("/api/v1")
public class AnkenMembersApi {
    private final AnkenMembersService service;

    public AnkenMembersApi(AnkenMembersService service) {
        this.service = service;
    }

    public record AddMemberIn(@NotBlank String memberId) {
    }

    public record NewPersonIn(@NotBlank String email, @NotBlank String name, @Nullable Role role, @NotNull List<String> epics) {
    }

    /** {@code clearRole} removes the role; otherwise a role is required. */
    public record PersonPatchIn(@Nullable Role role, @Nullable Boolean clearRole) {
    }

    @Requires(feature = Feature.MEMBERS, level = Level.READ)
    @GetMapping("/anken/{epic}/members")
    public AnkenMembersView members(@PathVariable("epic") String epic, Viewer viewer) {
        return service.members(viewer, epic);
    }

    @Requires(feature = Feature.MEMBERS, level = Level.WRITE)
    @PostMapping("/anken/{epic}/members")
    public AnkenMembersView add(@PathVariable("epic") String epic, @RequestBody @Valid AddMemberIn body, Viewer viewer) {
        return service.add(viewer, epic, body.memberId());
    }

    @Requires(feature = Feature.MEMBERS, level = Level.WRITE)
    @DeleteMapping("/anken/{epic}/members/{memberId}")
    public AnkenMembersView remove(@PathVariable("epic") String epic, @PathVariable("memberId") String memberId, Viewer viewer) {
        return service.remove(viewer, epic, memberId);
    }

    @Requires(feature = Feature.MEMBERS, level = Level.WRITE)
    @PostMapping("/people")
    @ResponseStatus(HttpStatus.CREATED)
    public Member addPerson(@RequestBody @Valid NewPersonIn body, Viewer viewer) {
        return service.addPerson(viewer, new NewPerson(body.email(), body.name(), body.role(), body.epics()));
    }

    @Requires(feature = Feature.MEMBERS, level = Level.WRITE)
    @PatchMapping("/people/{memberId}")
    public Member setRole(@PathVariable("memberId") String memberId, @RequestBody @Valid PersonPatchIn body, Viewer viewer) {
        boolean clear = Boolean.TRUE.equals(body.clearRole());
        if (!clear && body.role() == null) {
            throw new Invalid("Choose a role or clear it", "role");
        }
        return service.setRole(viewer, memberId, clear ? null : body.role());
    }
}
