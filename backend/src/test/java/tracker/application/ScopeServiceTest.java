package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tracker.DemoTest;
import tracker.application.AppError.NotFound;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.service.AccessService;
import tracker.application.service.ScopeService;
import tracker.domain.AccessStatus;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Role;
import tracker.domain.Viewer;

@DemoTest
class ScopeServiceTest {
    @Autowired
    ScopeService scopes;
    @Autowired
    AnkenMemberRepository ankenMembers;

    private static Viewer ba(Set<String> epics) {
        return new Viewer("ba@acme.vn", "Ba", "ba", Role.BA, false, AccessStatus.ACTIVE, java.util.Map.of(Feature.MATRIX, Level.READ),
                false, epics);
    }

    @Test
    void allMyAnkenIsTheUnionOfMemberships() {
        Viewer v = ba(Set.of("PAY-1100", "PAY-1300"));
        assertThat(scopes.scope(v, null).epics()).containsExactlyInAnyOrder("PAY-1100", "PAY-1300");
        assertThat(scopes.scope(v, " ").epics()).containsExactlyInAnyOrder("PAY-1100", "PAY-1300");
        assertThat(scopes.scope(v, "PAY-1300").epics()).containsExactly("PAY-1300");
    }

    @Test
    void aNonVisibleAnkenIs404() {
        Viewer v = ba(Set.of("PAY-1100"));
        assertThatThrownBy(() -> scopes.scope(v, "CRM-400")).isInstanceOf(NotFound.class)
                .hasMessage("Anken CRM-400 is not tracked");
        assertThatThrownBy(() -> scopes.requireVisible(v, "CRM-400")).isInstanceOf(NotFound.class)
                .hasMessage("Anken CRM-400 is not tracked");
        scopes.requireVisible(v, "PAY-1100");
    }

    @Test
    void adminSeesEveryTrackedEpic() {
        Viewer admin = new Viewer("a@acme.vn", "A", "a", Role.PM, true, AccessStatus.ACTIVE, java.util.Map.of(), true, Set.of());
        assertThat(scopes.visible(admin)).containsExactly("PAY-1100", "PAY-1300", "PAY-1420", "PAY-0980", "CRM-400", "OPS-200");
        assertThat(scopes.scope(admin, null).epics()).hasSize(6);
        assertThat(scopes.scope(admin, "OPS-200").contains("OPS-200")).isTrue();
    }
}
