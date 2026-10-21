package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.application.AppError.Conflict;
import tracker.application.AppError.Forbidden;
import tracker.application.AppError.Invalid;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.service.AccessService;
import tracker.application.service.AnkenMembersService;
import tracker.application.service.AnkenMembersService.NewPerson;
import tracker.application.service.AnkenService;
import tracker.application.service.AnkenService.NotMember;
import tracker.domain.AccessRequest;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScope;
import tracker.domain.Member;
import tracker.domain.Permissions;
import tracker.domain.RequestStatus;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** The anken member list, adding and removing people, new people and the not-a-member warning, on the demo data. */
@DemoTest
@DirtiesContext
class AnkenMembersServiceTest {
    static final AnkenScope ALL = ServiceReadsTest.ALL;

    @Autowired
    AnkenMembersService service;
    @Autowired
    AnkenService anken;
    @Autowired
    AccessService access;
    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    MemberRepository members;
    @Autowired
    JdbcClient jdbc;

    static Viewer viewer(String id, Role role) {
        return new Viewer(id + "@acme.vn", id, id, role, false, AccessStatus.ACTIVE, Permissions.defaults(role), true, Set.of());
    }

    final Viewer pm = viewer("pmv", Role.PM);
    final Viewer pim = viewer("pimv", Role.PIM);

    @Test
    void addingANotMemberClearsTheWarningAndFilesARequest() {
        ankenMembers.remove("PAY-1100", "binh");
        jdbc.sql("DELETE FROM member_access WHERE member_id = 'binh'").update();

        assertThat(service.members(pm, "PAY-1100").notMembers()).extracting(NotMember::memberId).contains("binh");
        assertThat(service.members(pm, "PAY-1100").candidates()).extracting(Member::id).contains("binh");

        service.add(pm, "PAY-1100", "binh");
        service.add(pm, "PAY-1100", "binh"); // idempotent

        assertThat(service.members(pm, "PAY-1100").notMembers()).extracting(NotMember::memberId).doesNotContain("binh");
        assertThat(service.members(pm, "PAY-1100").members()).extracting(r -> r.member().id()).contains("binh");
        String email = members.findById("binh").orElseThrow().email();
        List<AccessRequest> pending = access.requests(RequestStatus.PENDING).stream().filter(r -> r.email().equals(email)).toList();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).memberId()).isEqualTo("binh");
        assertThat(pending.get(0).note()).isEqualTo("Added to PAY-1100 by pmv");
    }

    @Test
    void notMembersAreOrderedByOpenSubtasks() {
        ankenMembers.remove("PAY-1100", "binh");
        ankenMembers.remove("PAY-1100", "chi");
        List<NotMember> out = service.members(pm, "PAY-1100").notMembers();
        assertThat(out).extracting(NotMember::memberId).contains("binh", "chi");
        assertThat(out).isSortedAccordingTo((a, b) -> Integer.compare(b.openSubtasks(), a.openSubtasks()));
    }

    @Test
    void pimCannotCreateAPm() {
        assertThatThrownBy(() -> service.addPerson(pim, new NewPerson("x@acme.vn", "X", Role.PM, List.of("PAY-1100"))))
                .isInstanceOf(Forbidden.class).hasMessage("Only the admin can set PM or PIM");
        assertThatThrownBy(() -> service.setRole(pim, "an", Role.PIM)).isInstanceOf(Forbidden.class);
    }

    @Test
    void addPersonCreatesMemberAndRequest() {
        Member m = service.addPerson(pim, new NewPerson("New.Person@acme.vn", "New Person", Role.BA, List.of("PAY-1100")));
        assertThat(m.email()).isEqualTo("new.person@acme.vn");
        assertThat(ankenMembers.exists("PAY-1100", m.id())).isTrue();
        assertThat(access.requests(RequestStatus.PENDING)).anyMatch(r -> m.id().equals(r.memberId()));
        assertThatThrownBy(() -> service.addPerson(pim, new NewPerson("new.person@acme.vn", "Again", Role.BA, List.of("PAY-1100"))))
                .isInstanceOf(Conflict.class);
    }

    @Test
    void setRoleChecksMembershipAndJm() {
        assertThat(service.setRole(pim, "an", Role.BAL).role()).isEqualTo(Role.BAL);
        assertThat(service.setRole(pim, "an", null).role()).isNull();
        assertThatThrownBy(() -> service.setRole(pim, "an", Role.JM)).isInstanceOf(Invalid.class)
                .hasMessage("A JM cannot sign in");
        assertThatThrownBy(() -> service.setRole(pim, "nobody", Role.BA)).isInstanceOf(AppError.NotFound.class);
    }

    @Test
    void cardNamesThePim() {
        Member an = members.findById("an").orElseThrow();
        members.save(new Member(an.id(), an.name(), an.fullName(), Role.PIM, an.country(), an.color(), an.email(), an.permission(),
                an.external(), an.jiraAccountId()));
        ankenMembers.add("PAY-1100", "an", "test", Instant.now().toString());
        assertThat(anken.overview(ALL)).filteredOn(c -> c.key().equals("PAY-1100")).singleElement()
                .satisfies(c -> assertThat(c.pimIds()).contains("an").isSorted());
    }

    @Test
    void functionPickersRejectNonMembers() {
        members.save(new Member("zoe", "Zoe", "Zoe Outsider", Role.BA, "VN", "#123456"));
        AnkenService.FunctionDraft d = ServiceReadsTest.draft("PAY-1102", "Installment", 8);
        AnkenService.FunctionDraft bad = new AnkenService.FunctionDraft(d.parentKey(), d.name(), "zoe", d.bal(), d.jm(),
                d.createHours(), d.overrides());
        assertThatThrownBy(() -> anken.previewFunction(ALL, "PAY-1100", bad)).isInstanceOf(Invalid.class)
                .satisfies(e -> assertThat(((Invalid) e).field()).isEqualTo("ba"));
        assertThat(anken.matrix(ALL, "PAY-1100", null, null, null).memberIds()).isEqualTo(
                ankenMembers.findByEpic("PAY-1100").stream().map(tracker.domain.AnkenMember::memberId).sorted().toList());
    }
}
