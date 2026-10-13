package tracker.adapters.web;

import java.util.List;

/** Keys of the Jira issues an operation created or changed. */
public record KeysOut(List<String> keys) {
}
