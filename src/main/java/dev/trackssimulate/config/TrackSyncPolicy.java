package dev.trackssimulate.config;

import java.util.Objects;
import java.util.UUID;

/** Pure selection rules, shared with boundary regression checks. */
public final class TrackSyncPolicy {
    public static final int RANGE=8, MAX_TARGETS=64;
    private TrackSyncPolicy() {}
    public static boolean includes(OffsetScope scope, UUID sourceBelt, UUID candidateBelt,
            boolean sameWheel, boolean sameRole, boolean sameBody, double distanceSquared) {
        if(!sameBody)return false;
        if(scope==OffsetScope.WHEEL)return sameWheel;
        if(sourceBelt!=null)return Objects.equals(sourceBelt,candidateBelt)&&scope.includes(sameWheel,sameRole);
        return scope==OffsetScope.ROLE&&candidateBelt==null&&sameRole&&distanceSquared<=RANGE*RANGE;
    }
}
