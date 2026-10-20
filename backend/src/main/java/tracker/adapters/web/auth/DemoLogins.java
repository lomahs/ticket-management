package tracker.adapters.web.auth;

import java.util.List;

/** The demo profile's users and their shared password, shown on the login page. */
public record DemoLogins(List<String> users, String password) {
}
