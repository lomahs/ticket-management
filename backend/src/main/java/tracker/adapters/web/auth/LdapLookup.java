package tracker.adapters.web.auth;

/**
 * How a login finds its directory entry.
 *
 * @param userFilter    the search filter, {@code {0}} is the username (escaped)
 * @param mailAttribute the attribute with the e-mail, the identity of the user
 * @param nameAttribute the attribute with the display name
 */
public record LdapLookup(String userFilter, String mailAttribute, String nameAttribute) {
}
