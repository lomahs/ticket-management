package tracker.application.ports;

@FunctionalInterface
public interface UnitOfWorkFactory {
    UnitOfWork begin();
}
