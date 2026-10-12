package tracker.domain;

import java.util.EnumMap;
import java.util.Map;

public record LeaveTypeInfo(String label, boolean full, boolean tentative) {

    public static final Map<LeaveType, LeaveTypeInfo> LEAVE_TYPES = new EnumMap<>(Map.of(
            LeaveType.FULL, new LeaveTypeInfo("All day", true, false),
            LeaveType.HALF, new LeaveTypeInfo("Half day", false, false),
            LeaveType.FULL_TENTATIVE, new LeaveTypeInfo("All day (tentative)", true, true),
            LeaveType.HALF_TENTATIVE, new LeaveTypeInfo("Half day (tentative)", false, true)));

    public static LeaveTypeInfo of(LeaveType type) {
        return LEAVE_TYPES.get(type);
    }
}
