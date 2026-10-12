package tracker.application;

import tracker.application.ports.Clock;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraWriter;
import tracker.application.ports.ReaderFactory;
import tracker.application.ports.SecretBox;
import tracker.application.ports.UnitOfWorkFactory;

/**
 * What every use case needs.
 *
 * @param writer    null when Jira cannot be written (phase 1 Jira mode)
 * @param readerFor url, email, token → reader, for testing unsaved credentials
 * @param mode      demo | jira
 */
public record Deps(UnitOfWorkFactory uow, Clock clock, JiraReader reader, JiraWriter writer, SecretBox secrets,
                   ReaderFactory readerFor, String mode) {

    public SyncFromJira sync() {
        return new SyncFromJira(uow, reader, clock);
    }
}
