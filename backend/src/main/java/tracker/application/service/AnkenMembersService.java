package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Conflict;
import tracker.application.AppError.Forbidden;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.service.AnkenService.NotMember;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenMember;
import tracker.domain.AnkenScope;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.RequestStatus;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Who works on each anken: the member list, adding and removing people, new people and their role. */
@Service
public class AnkenMembersService {
    private final ScopeService scopes;
    private final AnkenService anken;
    private final AccessService accessService;
    private final AccessRepository access;
    private final MemberRepository members;
    private final AnkenMemberRepository ankenMembers;
    private final Clock clock;

    public AnkenMembersService(ScopeService scopes, AnkenService anken, AccessService accessService, AccessRepository access,
                               MemberRepository members, AnkenMemberRepository ankenMembers, Clock clock) {
        this.scopes = scopes;
        this.anken = anken;
        this.accessService = accessService;
        this.access = access;
        this.members = members;
        this.ankenMembers = ankenMembers;
        this.clock = clock;
    }

    public record AnkenMemberRow(Member member, AccessStatus access, String addedAt, String addedBy) {
    }

    /** @param candidates every member who is not in the anken, by name */
    public record AnkenMembersView(String epicKey, List<AnkenMemberRow> members, List<NotMember> notMembers,
                                   List<Member> candidates) {
    }

    /** @param epics the ankens the new person joins */
    public record NewPerson(String email, String name, @Nullable Role role, List<String> epics) {
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    public AnkenMembersView members(Viewer v, String epic) {
        scopes.requireVisible(v, epic);
        Map<String, Member> all = members.findAll().stream().collect(Collectors.toMap(Member::id, Function.identity()));
        Set<String> pending = access.findRequests(RequestStatus.PENDING).stream().map(r -> r.email().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<AnkenMemberRow> rows = new ArrayList<>();
        for (AnkenMember am : ankenMembers.findByEpic(epic)) {
            Member m = all.remove(am.memberId());
            if (m != null) {
                AccessStatus status = access.findStatus(m.id())
                        .orElse(pending.contains(m.email().toLowerCase(Locale.ROOT)) ? AccessStatus.REQUESTED : AccessStatus.NONE);
                rows.add(new AnkenMemberRow(m, status, am.addedAt(), am.addedBy()));
            }
        }
        List<Member> candidates = all.values().stream()
                .sorted(Comparator.comparing(Member::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Member::id)).toList();
        return new AnkenMembersView(epic, rows, anken.notMembers(new AnkenScope(Set.of(epic)), epic), candidates);
    }

    /** Adds the member to the anken; adding a member twice changes nothing. They are asked to sign in when they cannot yet. */
    @Transactional
    public AnkenMembersView add(Viewer v, String epic, String memberId) {
        scopes.requireVisible(v, epic);
        Member m = members.findById(memberId).orElseThrow(() -> new NotFound("Member " + memberId + " was not found", "member_id"));
        ankenMembers.add(epic, m.id(), v.email(), now());
        accessService.fileRequestFor(v, m, "Added to " + epic + " by " + v.name());
        return members(v, epic);
    }

    @Transactional
    public AnkenMembersView remove(Viewer v, String epic, String memberId) {
        scopes.requireVisible(v, epic);
        ankenMembers.remove(epic, memberId);
        return members(v, epic);
    }

    private static void requireAdminForPmRoles(Viewer v, @Nullable Role role) {
        if ((role == Role.PM || role == Role.PIM) && !v.admin()) {
            throw new Forbidden("Only the admin can set PM or PIM");
        }
    }

    /** A new person in the ankens {@code p.epics()}; they are asked to sign in. */
    @Transactional
    public Member addPerson(Viewer v, NewPerson p) {
        requireAdminForPmRoles(v, p.role());
        String email = p.email().strip().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            throw new Invalid("E-mail is required", "email");
        }
        if (email.equals(AccessService.BREAKGLASS_EMAIL)) {
            throw new Invalid("This e-mail belongs to the break-glass login", "email");
        }
        if (!members.findAllByEmail(email).isEmpty()) {
            throw new Conflict("A member with this e-mail already exists", "email");
        }
        if (p.epics().isEmpty() && !v.admin()) {
            throw new Invalid("Choose at least one anken", "epics");
        }
        for (String epic : p.epics()) {
            scopes.requireVisible(v, epic);
        }
        Member m = accessService.createMember(email, p.name(), p.role(), Permission.USER);
        for (String epic : p.epics()) {
            ankenMembers.add(epic, m.id(), v.email(), now());
        }
        accessService.fileRequestFor(v, m,
                (p.epics().isEmpty() ? "Added" : "Added to " + String.join(", ", p.epics())) + " by " + v.name());
        return m;
    }

    /** Sets or, with null, clears the role of a member of an anken the viewer sees. */
    @Transactional
    public Member setRole(Viewer v, String memberId, @Nullable Role role) {
        requireAdminForPmRoles(v, role);
        Set<String> visible = scopes.visible(v);
        Member m = members.findById(memberId).filter(x -> ankenMembers.findEpicsOf(x.id()).stream().anyMatch(visible::contains))
                .orElseThrow(() -> new NotFound("Member " + memberId + " was not found", "member_id"));
        requireAdminForPmRoles(v, m.role());
        if (role == Role.JM && access.findStatus(m.id()).orElse(null) == AccessStatus.ACTIVE) {
            throw new Invalid("A JM cannot sign in", "role");
        }
        Member updated = new Member(m.id(), m.name(), m.fullName(), role, m.country(), m.color(), m.email(), m.permission(),
                m.external(), m.jiraAccountId());
        members.save(updated);
        return updated;
    }
}
