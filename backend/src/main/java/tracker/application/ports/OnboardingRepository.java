package tracker.application.ports;

import java.util.List;
import java.util.Optional;

import tracker.domain.Onboarding;

/** The ankens being onboarded and how far each got. */
public interface OnboardingRepository {
    /** Most recently saved first. */
    List<Onboarding> findAll();

    Optional<Onboarding> find(String epic);

    /** An upsert on the epic. */
    void save(Onboarding o);

    void delete(String epic);
}
