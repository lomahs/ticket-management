package tracker.application.ports;

import java.util.List;

import tracker.domain.Allocation;

/** Hours per day each member gives each anken, from an effective date. */
public interface AllocationRepository {
    record StampedAllocation(Allocation allocation, String setBy, String setAt) {
    }

    List<Allocation> findAll();

    List<Allocation> findByMember(String memberId);

    /** The rows of one member with who wrote them and when. */
    List<StampedAllocation> findStampedByMember(String memberId);

    /** An upsert on (member, epic, effective date). */
    void save(Allocation a, String setBy, String setAt);
}
