package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.settings.SettingsStore;
import tracker.domain.AccessRequest;
import tracker.domain.AccessStatus;
import tracker.domain.Feature;
import tracker.domain.Identity;
import tracker.domain.Level;
import tracker.domain.Member;
import tracker.domain.MemberIds;
import tracker.domain.Permission;
import tracker.domain.Permissions;
import tracker.domain.RequestStatus;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Who may use the app: the viewer of a login, access requests, and the administration of users. */
@Service
public class AccessService {
    /** The identity of the break-glass login. */
    public static final String BREAKGLASS_EMAIL = "breakglass@local";

    /** @param adminEmails bootstrap admins (lowercase e-mails): always admin and active */
    public record Config(Set<String> adminEmails) {
    }

    private final AccessRepository access;
    private final MemberRepository members;
    private final SettingsStore settings;
    private final Clock clock;
    private final Config config;

    public AccessService(AccessRepository access, MemberRepository members, SettingsStore settings, Clock clock, Config config) {
        this.access = access;
        this.members = members;
        this.settings = settings;
        this.clock = clock;
        this.config = config;
    }

    // --- the viewer -------------------------------------------------------------------------------------------------

    /**
     * The member an e-mail belongs to. Members may share an e-mail: an active one wins, then one with any sign-in status,
     * else the lowest id.
     */
    private Optional<Member> resolve(String email) {
        List<Member> all = members.findAllByEmail(email); // ordered by id
        Map<String, AccessStatus> statuses = new HashMap<>();
        for (Member m : all) {
            access.findStatus(m.id()).ifPresent(st -> statuses.put(m.id(), st));
        }
        return all.stream().filter(m -> statuses.get(m.id()) == AccessStatus.ACTIVE).findFirst()
                .or(() -> all.stream().filter(m -> statuses.containsKey(m.id())).findFirst())
                .or(() -> all.stream().findFirst());
    }

    /** Whether some member is an active admin (who can approve access requests). */
    public boolean hasActiveAdmin() {
        Map<String, AccessStatus> statuses = access.findStatuses();
        return members.findAll().stream()
                .anyMatch(m -> m.permission() == Permission.ADMIN && statuses.get(m.id()) == AccessStatus.ACTIVE);
    }

    /** Whether there are bootstrap admins ({@code tracker.admins}). */
    public boolean hasBootstrapAdmins() {
        return !config.adminEmails().isEmpty();
    }

    private boolean bootstrap(String email) {
        return config.adminEmails().contains(email.toLowerCase(Locale.ROOT));
    }

    private static Map<Feature, Level> none() {
        Map<Feature, Level> levels = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            levels.put(f, Level.NONE);
        }
        return levels;
    }

    private AccessStatus fromRequest(String email) {
        return access.findLatestRequest(email).map(r -> switch (r.status()) {
            case PENDING -> AccessStatus.REQUESTED;
            case REJECTED -> AccessStatus.REJECTED;
            case APPROVED -> AccessStatus.NONE;
        }).orElse(AccessStatus.NONE);
    }

    public Viewer viewer(Identity id) {
        Member member = resolve(id.email()).orElse(null);
        String memberId = member != null ? member.id() : null;
        Role role = member != null ? member.role() : null;
        // Bootstrap admins stay active admins whatever their role, including JM.
        if (id.email().equals(BREAKGLASS_EMAIL) || bootstrap(id.email())) {
            Map<Feature, Level> levels = Permissions.effective(role, memberId != null ? access.findOverrides(memberId) : Map.of());
            return new Viewer(id.email(), id.name(), memberId, role, true, AccessStatus.ACTIVE, levels);
        }
        if (member == null) {
            return new Viewer(id.email(), id.name(), null, null, false, fromRequest(id.email()), none());
        }
        AccessStatus status = access.findStatus(member.id()).orElseGet(() -> fromRequest(id.email()));
        boolean admin = member.permission() == Permission.ADMIN && status == AccessStatus.ACTIVE;
        return new Viewer(id.email(), id.name(), member.id(), role, admin, status,
                Permissions.effective(role, access.findOverrides(member.id())));
    }

    /** A login succeeded: a bootstrap admin gets a member, ACTIVE and ADMIN; anyone else is looked up as they are. */
    @Transactional
    public Viewer onLogin(Identity id) {
        if (bootstrap(id.email()) && !id.email().equals(BREAKGLASS_EMAIL)) {
            Member m = resolve(id.email()).orElse(null);
            if (m == null) {
                m = createMember(id.email(), id.name(), null, Permission.ADMIN);
            } else if (m.permission() != Permission.ADMIN) {
                m = new Member(m.id(), m.name(), m.fullName(), m.role(), m.country(), m.color(), m.email(), Permission.ADMIN,
                        m.external(), m.jiraAccountId());
                members.save(m);
            }
            access.saveStatus(m.id(), AccessStatus.ACTIVE);
        }
        return viewer(id);
    }

    private Member createMember(String email, String name, @Nullable Role role, Permission permission) {
        String base = MemberIds.slugify(email.contains("@") ? email.substring(0, email.indexOf('@')) : email);
        String memberId = MemberIds.newId(base, members.findAll().stream().map(Member::id).collect(Collectors.toSet()));
        String display = name.isBlank() ? email : name.strip();
        Member m = new Member(memberId, display, display, role, settings.load().defaultCountry, MemberIds.color(memberId), email,
                permission, false, null);
        members.save(m);
        return m;
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    // --- requests ---------------------------------------------------------------------------------------------------

    @Transactional
    public Viewer requestAccess(Identity id, String note) {
        Viewer v = viewer(id);
        if (v.active()) {
            throw new Invalid("You already have access");
        }
        if (v.access() == AccessStatus.DISABLED) {
            throw new Invalid("Your access is disabled. Ask the admin.");
        }
        boolean pending = access.findLatestRequest(id.email()).map(r -> r.status() == RequestStatus.PENDING).orElse(false);
        if (!pending) {
            access.addRequest(id.email(), id.name(), note, now(), resolve(id.email()).map(Member::id).orElse(null));
        }
        return viewer(id);
    }

    public List<AccessRequest> requests(@Nullable RequestStatus status) {
        return access.findRequests(status);
    }

    public record Approval(@Nullable String memberId, Role role, @Nullable String name) {
    }

    @Transactional
    public UserRow approve(Viewer actor, int requestId, Approval a) {
        AccessRequest req = pending(requestId);
        if (a.role() == null || !List.of(Role.PM, Role.PIM, Role.BAL, Role.BA).contains(a.role())) {
            throw new Invalid("Choose PM, PIM, BAL or BA", "role");
        }
        String link = a.memberId() != null ? a.memberId() : req.memberId();
        Member m;
        if (link != null) {
            Member found = members.findById(link).orElseThrow(() -> new NotFound("Member " + link + " was not found", "member_id"));
            if (!found.email().isBlank() && !found.email().equalsIgnoreCase(req.email())) {
                throw new Conflict("That member signs in as " + found.email() + "; change its e-mail first", "member_id");
            }
            m = new Member(found.id(), found.name(), found.fullName(), a.role(), found.country(), found.color(),
                    found.email().isBlank() ? req.email() : found.email(), found.permission(), found.external(),
                    found.jiraAccountId());
            members.save(m);
        } else {
            if (resolve(req.email()).isPresent()) {
                throw new Conflict("A member with this e-mail exists; link to it", "member_id");
            }
            m = createMember(req.email(), a.name() != null && !a.name().isBlank() ? a.name() : req.name(), a.role(), Permission.USER);
        }
        access.saveStatus(m.id(), AccessStatus.ACTIVE);
        access.decide(requestId, RequestStatus.APPROVED, actor.email(), now(), m.id());
        return row(m, pendingEmails());
    }

    @Transactional
    public AccessRequest reject(Viewer actor, int requestId) {
        AccessRequest req = pending(requestId);
        access.decide(requestId, RequestStatus.REJECTED, actor.email(), now(), req.memberId());
        return access.findRequest(requestId).orElseThrow();
    }

    private AccessRequest pending(int requestId) {
        AccessRequest req = access.findRequest(requestId).orElseThrow(() -> new NotFound("Request " + requestId + " was not found"));
        if (req.status() != RequestStatus.PENDING) {
            throw new Conflict("This request was already decided", null);
        }
        return req;
    }

    // --- users ------------------------------------------------------------------------------------------------------

    public record UserRow(Member member, AccessStatus access, boolean bootstrapAdmin, Map<Feature, Level> overrides,
                          Map<Feature, Level> effective) {
    }

    private Set<String> pendingEmails() {
        return access.findRequests(RequestStatus.PENDING).stream().map(r -> r.email().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private UserRow row(Member m, Set<String> pendingEmails) {
        boolean bootstrap = bootstrap(m.email());
        // A bootstrap admin is active whatever is stored, like their viewer.
        AccessStatus status = bootstrap ? AccessStatus.ACTIVE : access.findStatus(m.id())
                .orElse(pendingEmails.contains(m.email().toLowerCase(Locale.ROOT)) ? AccessStatus.REQUESTED : AccessStatus.NONE);
        Map<Feature, Level> overrides = access.findOverrides(m.id());
        return new UserRow(m, status, bootstrap, overrides, Permissions.effective(m.role(), overrides));
    }

    public List<UserRow> users() {
        Set<String> pending = pendingEmails();
        List<UserRow> out = new ArrayList<>();
        for (Member m : members.findAll()) {
            out.add(row(m, pending));
        }
        out.sort(Comparator.comparing((UserRow u) -> u.member().name(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Fields that are null are not changed; {@code clearRole} removes the role; an override of null removes it. */
    public record UserPatch(@Nullable String name, @Nullable String email, @Nullable Role role, boolean clearRole,
                            @Nullable String country, @Nullable Boolean admin, @Nullable AccessStatus access,
                            Map<Feature, @Nullable Level> overrides) {
    }

    @Transactional
    public UserRow patchUser(Viewer actor, String memberId, UserPatch p) {
        if (p.country() != null && !TeamSettingsService.COUNTRIES.contains(p.country())) {
            throw new Invalid("Country must be one of " + String.join(", ", TeamSettingsService.COUNTRIES), "country");
        }
        if (p.name() != null && p.name().isBlank()) {
            throw new Invalid("Name is required", "name");
        }
        if (p.access() != null && p.access() != AccessStatus.ACTIVE && p.access() != AccessStatus.DISABLED) {
            throw new Invalid("Access can only be set to active or disabled", "access");
        }
        Member m = members.findById(memberId).orElseThrow(() -> new NotFound("Member " + memberId + " was not found"));
        String email = p.email() != null ? p.email().strip().toLowerCase(Locale.ROOT) : m.email();
        if (p.email() != null && email.equals(BREAKGLASS_EMAIL)) {
            throw new Invalid("This e-mail belongs to the break-glass login", "email");
        }
        if (p.email() != null && !email.isEmpty() && members.findAllByEmail(email).stream().anyMatch(o -> !o.id().equals(memberId))) {
            throw new Conflict("Another member already has this e-mail", "email");
        }
        Role role = p.clearRole() ? null : p.role() != null ? p.role() : m.role();
        AccessStatus status = p.access() != null ? p.access() : access.findStatus(memberId).orElse(null);

        boolean self = memberId.equals(actor.memberId());
        if (self && (Boolean.FALSE.equals(p.admin()) || p.access() == AccessStatus.DISABLED)) {
            throw new Invalid("You cannot remove your own admin access", p.access() == AccessStatus.DISABLED ? "access" : "admin");
        }
        if (self && p.email() != null && !email.isBlank() && !email.equals(actor.email())) {
            throw new Invalid("You cannot change your own e-mail", "email");
        }
        boolean sharesMyEmail = (!email.isBlank() && email.equals(actor.email()))
                || (!m.email().isBlank() && m.email().equalsIgnoreCase(actor.email()));
        if (!self && p.access() != null && sharesMyEmail) {
            throw new Invalid("This member shares your e-mail; merge members first", "access");
        }
        if (Boolean.FALSE.equals(p.admin()) && bootstrap(m.email())) {
            throw new Invalid("Set in TRACKER_ADMINS", "admin");
        }
        if (p.access() == AccessStatus.DISABLED && bootstrap(m.email())) {
            throw new Invalid("Set in TRACKER_ADMINS", "access");
        }
        if (status == AccessStatus.ACTIVE && role == Role.JM) {
            throw new Invalid("A JM cannot sign in", "role");
        }
        if (status == AccessStatus.ACTIVE && email.isBlank()) {
            throw new Invalid("Needs an e-mail to sign in", "email");
        }

        Permission permission = p.admin() == null ? m.permission() : p.admin() ? Permission.ADMIN : Permission.USER;
        Member updated = new Member(m.id(), p.name() != null ? p.name().strip() : m.name(), m.fullName(), role,
                p.country() != null ? p.country() : m.country(), m.color(), email, permission, m.external(), m.jiraAccountId());
        members.save(updated);
        if (p.access() != null) {
            access.saveStatus(memberId, p.access());
        }
        for (Map.Entry<Feature, @Nullable Level> o : p.overrides().entrySet()) {
            access.saveOverride(memberId, o.getKey(), o.getValue());
        }
        return row(updated, pendingEmails());
    }
}
