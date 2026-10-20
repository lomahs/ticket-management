package tracker.domain;

/** Who logged in: the directory's mail (lowercased, the key of every user) and display name. */
public record Identity(String email, String name) {
    public Identity {
        email = email.strip().toLowerCase();
    }
}
