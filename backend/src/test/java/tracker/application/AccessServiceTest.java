package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tracker.TrackerTest;
import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.service.AccessService;
import tracker.application.service.AccessService.Approval;
import tracker.application.service.AccessService.UserPatch;
import tracker.application.settings.SettingsStore;
import tracker.domain.AccessRequest;
import tracker.domain.AccessStatus;
import tracker.domain.Feature;
import tracker.domain.Identity;
import tracker.domain.Level;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.RequestStatus;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Who may sign in, access requests and user administration, on an empty database with a few members. */
@TrackerTest
class AccessServiceTest {
    static final Identity LAN = new Identity("lan.vo@acme.vn", "Lan Vo");
    static final Identity STRANGER = new Identity("new.person@acme.vn", "New Person");

    @Autowired
    AccessRepository access;
    @Autowired
    MemberRepository members;
    @Autowired
    SettingsStore settings;
    @Autowired
    Clock clock;

    AccessService service;
    Viewer admin;

    @BeforeEach
    void setUp() {
        service = new AccessService(access, members, settings, clock, new AccessService.Config(Set.of("lan.vo@acme.vn")));
        members.save(new Member("minh", "Minh", "Minh Tran", Role.PM, "VN", "#111", "minh@acme.vn", Permission.ADMIN, false, null));
        members.save(new Member("an", "An", "An Nguyen", Role.BA, "VN", "#222", "an@acme.vn", Permission.USER, false, null));
        members.save(new Member("tanaka", "Tanaka", "Tanaka San", Role.JM, "JP", "#333", "tanaka@acme.vn", Permission.USER, false, null));
        access.saveStatus("minh", AccessStatus.ACTIVE);
        access.saveStatus("an", AccessStatus.ACTIVE);
        admin = service.viewer(new Identity("minh@acme.vn", "Minh"));
    }

    @Test
    void strangerHasNoAccessUntilApproved() {
        assertThat(service.viewer(STRANGER).access()).isEqualTo(AccessStatus.NONE);
        assertThat(service.requestAccess(STRANGER, "please").access()).isEqualTo(AccessStatus.REQUESTED);
        service.requestAccess(STRANGER, "again");
        assertThat(service.requests(RequestStatus.PENDING)).hasSize(1);

        int id = service.requests(RequestStatus.PENDING).get(0).id();
        service.approve(admin, id, new Approval(null, Role.BA, null));

        Viewer v = service.viewer(STRANGER);
        assertThat(v.access()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(v.role()).isEqualTo(Role.BA);
        assertThat(v.can(Feature.MATRIX, Level.READ)).isTrue();
        assertThat(v.can(Feature.MATRIX, Level.WRITE)).isFalse();
    }

    @Test
    void requestOfASyncedMemberLinksToThem() {
        members.save(new Member("sync", "Sync", "Sync Person", null, "VN", "#444", "sync@acme.vn", Permission.USER, false, "acc-1"));
        Identity sync = new Identity("sync@acme.vn", "Sync");
        service.requestAccess(sync, "");
        AccessRequest r = service.requests(RequestStatus.PENDING).get(0);
        assertThat(r.memberId()).isEqualTo("sync");

        int before = members.findAll().size();
        service.approve(admin, r.id(), new Approval(null, Role.BA, null));
        assertThat(members.findAll()).hasSize(before);
        assertThat(service.viewer(sync).memberId()).isEqualTo("sync");
        assertThat(service.viewer(sync).access()).isEqualTo(AccessStatus.ACTIVE);
    }

    @Test
    void approveWithoutLinkWhenEmailExistsConflicts() {
        service.requestAccess(STRANGER, "");
        int id = service.requests(RequestStatus.PENDING).get(0).id();
        members.save(new Member("np", "NP", "New Person", null, "VN", "#555", "new.person@acme.vn", Permission.USER, false, null));
        assertThatThrownBy(() -> service.approve(admin, id, new Approval(null, Role.BA, null))).isInstanceOf(Conflict.class);
    }

    @Test
    void bootstrapAdminIsCreatedOnFirstLogin() {
        Viewer v = service.onLogin(LAN);
        Member m = members.findByEmail("lan.vo@acme.vn").orElseThrow();
        assertThat(m.permission()).isEqualTo(Permission.ADMIN);
        assertThat(access.findStatus(m.id())).contains(AccessStatus.ACTIVE);
        assertThat(v.admin()).isTrue();
        assertThat(service.viewer(LAN).admin()).isTrue();
    }

    @Test
    void adminCannotLockThemselvesOut() {
        assertThatThrownBy(() -> service.patchUser(admin, "minh", patch(false, null))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> service.patchUser(admin, "minh", patch(null, AccessStatus.DISABLED))).isInstanceOf(Invalid.class);
        service.onLogin(LAN);
        String lanId = members.findByEmail("lan.vo@acme.vn").orElseThrow().id();
        assertThatThrownBy(() -> service.patchUser(admin, lanId, patch(false, null))).isInstanceOf(Invalid.class)
                .hasMessage("Set in TRACKER_ADMINS");
    }

    @Test
    void jmCannotBeActive() {
        assertThatThrownBy(() -> service.patchUser(admin, "tanaka", patch(null, AccessStatus.ACTIVE))).isInstanceOf(Invalid.class)
                .hasMessage("A JM cannot sign in");
        service.requestAccess(new Identity("tanaka@acme.vn", "Tanaka"), "");
        int id = service.requests(RequestStatus.PENDING).get(0).id();
        assertThatThrownBy(() -> service.approve(admin, id, new Approval(null, Role.JM, null))).isInstanceOf(Invalid.class);
    }

    @Test
    void overridesApplyAndReset() {
        Identity an = new Identity("an@acme.vn", "An");
        service.patchUser(admin, "an", new UserPatch(null, null, null, false, null, null, null, Map.of(Feature.PLANNING, Level.WRITE)));
        assertThat(service.viewer(an).can(Feature.PLANNING, Level.WRITE)).isTrue();
        Map<Feature, Level> reset = new java.util.HashMap<>();
        reset.put(Feature.PLANNING, null);
        service.patchUser(admin, "an", new UserPatch(null, null, null, false, null, null, null, reset));
        assertThat(service.viewer(an).can(Feature.PLANNING, Level.WRITE)).isFalse();
        assertThat(service.viewer(an).can(Feature.PLANNING, Level.READ)).isTrue();
    }

    @Test
    void disabledUserIsNotActive() {
        Identity an = new Identity("an@acme.vn", "An");
        service.patchUser(admin, "an", patch(null, AccessStatus.DISABLED));
        assertThat(service.viewer(an).access()).isEqualTo(AccessStatus.DISABLED);
        assertThatThrownBy(() -> service.requestAccess(an, "")).isInstanceOf(Invalid.class);
    }

    @Test
    void duplicateEmailResolvesToTheMemberWithAccess() {
        members.save(new Member("an-old", "An old", "An Nguyen", Role.BA, "VN", "#666", "AN@acme.vn", Permission.USER, false, null));
        // "an" has the access row; "an-old" sorts first by id but has none
        Viewer v = service.viewer(new Identity("an@acme.vn", "An"));
        assertThat(v.memberId()).isEqualTo("an");
        assertThat(v.access()).isEqualTo(AccessStatus.ACTIVE);
    }

    @Test
    void rejectAndUsersList() {
        service.requestAccess(STRANGER, "");
        int id = service.requests(null).get(0).id();
        assertThat(service.reject(admin, id).status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(service.viewer(STRANGER).access()).isEqualTo(AccessStatus.REJECTED);
        assertThatThrownBy(() -> service.reject(admin, id)).isInstanceOf(Conflict.class);
        assertThat(service.users()).extracting(u -> u.member().name()).isSorted();
    }

    private static void field(Throwable e, String field) {
        assertThat(((tracker.application.AppError) e).field()).isEqualTo(field);
    }

    @Test
    void approveLinkedToAMemberWithAnotherEmailConflicts() {
        members.save(new Member("other", "Other", "Other", null, "VN", "#777", "other@acme.vn", Permission.USER, false, null));
        service.requestAccess(STRANGER, "");
        int id = service.requests(RequestStatus.PENDING).get(0).id();
        assertThatThrownBy(() -> service.approve(admin, id, new Approval("other", Role.BA, null))).isInstanceOf(Conflict.class)
                .hasMessage("That member signs in as other@acme.vn; change its e-mail first");
        assertThat(members.findById("other").orElseThrow().email()).isEqualTo("other@acme.vn");
    }

    @Test
    void adminCannotChangeOwnEmailOrActivateAMemberSharingIt() {
        assertThatThrownBy(() -> service.patchUser(admin, "minh", new UserPatch(null, "x@acme.vn", null, false, null, null, null, Map.of())))
                .isInstanceOf(Invalid.class).hasMessage("You cannot change your own e-mail");
        members.save(new Member("minh2", "Minh 2", "Minh 2", Role.BA, "VN", "#888", "minh@acme.vn", Permission.USER, false, null));
        assertThatThrownBy(() -> service.patchUser(admin, "minh2", patch(null, AccessStatus.ACTIVE))).isInstanceOf(Invalid.class)
                .hasMessage("This member shares your e-mail; merge members first");
    }

    @Test
    void patchRulesAreEnforced() {
        assertThatThrownBy(() -> service.patchUser(admin, "an", new UserPatch(null, null, null, false, "XX", null, null, Map.of())))
                .isInstanceOf(Invalid.class).satisfies(e -> field(e, "country"));
        assertThatThrownBy(() -> service.patchUser(admin, "an", new UserPatch(" ", null, null, false, null, null, null, Map.of())))
                .isInstanceOf(Invalid.class).satisfies(e -> field(e, "name"));
        assertThatThrownBy(() -> service.patchUser(admin, "zz", patch(null, null))).isInstanceOf(tracker.application.AppError.NotFound.class);
        assertThatThrownBy(() -> service.patchUser(admin, "an", new UserPatch(null, "MINH@acme.vn", null, false, null, null, null, Map.of())))
                .isInstanceOf(Conflict.class).satisfies(e -> field(e, "email"));
        members.save(new Member("noemail", "No Email", "No Email", Role.BA, "VN", "#999", "", Permission.USER, false, null));
        assertThatThrownBy(() -> service.patchUser(admin, "noemail", patch(null, AccessStatus.ACTIVE))).isInstanceOf(Invalid.class)
                .hasMessage("Needs an e-mail to sign in");
        assertThatThrownBy(() -> service.patchUser(admin, "an", patch(null, AccessStatus.REQUESTED))).isInstanceOf(Invalid.class)
                .satisfies(e -> field(e, "access"));
        assertThatThrownBy(() -> service.patchUser(admin, "an", new UserPatch(null, null, Role.JM, false, null, null, null, Map.of())))
                .isInstanceOf(Invalid.class).hasMessage("A JM cannot sign in");
    }

    @Test
    void approveNeedsAPendingRequest() {
        service.requestAccess(STRANGER, "");
        int id = service.requests(RequestStatus.PENDING).get(0).id();
        service.approve(admin, id, new Approval(null, Role.BA, null));
        assertThatThrownBy(() -> service.approve(admin, id, new Approval(null, Role.BA, null))).isInstanceOf(Conflict.class);
    }

    @Test
    void patchUpdatesNameRoleAndCountry() {
        AccessService.UserRow r = service.patchUser(admin, "an", new UserPatch("Ann", null, Role.BAL, false, "JP", null, null, Map.of()));
        assertThat(r.member().name()).isEqualTo("Ann");
        assertThat(r.member().role()).isEqualTo(Role.BAL);
        assertThat(r.member().country()).isEqualTo("JP");
        assertThat(service.patchUser(admin, "an", new UserPatch(null, null, null, true, null, null, null, Map.of())).member().role()).isNull();
    }

    private static UserPatch patch(Boolean admin, AccessStatus status) {
        return new UserPatch(null, null, null, false, null, admin, status, Map.of());
    }
}
