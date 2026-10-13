package tracker.domain;

import org.jspecify.annotations.Nullable;

public record Member(String id, String name, String fullName, @Nullable Role role, String country, String color, String email,
                     Permission permission, boolean external, @Nullable String jiraAccountId) {

    public Member(String id, String name, String fullName, Role role, String country, String color) {
        this(id, name, fullName, role, country, color, "", Permission.USER, false, null);
    }
}
