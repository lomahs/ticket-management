package tracker.domain;

/** A member who works on an anken (epic), and who added them when. */
public record AnkenMember(String epicKey, String memberId, String addedAt, String addedBy) {
}
