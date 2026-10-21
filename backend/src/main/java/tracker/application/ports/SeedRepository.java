package tracker.application.ports;

/** One-time data seeds that have already run. */
public interface SeedRepository {
    boolean done(String name);

    void markDone(String name, String at);
}
