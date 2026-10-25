package dev.trackssimulate.physics;

/** Ownership is determined by the mechanism, never by whether a narrow ray happened to hit.
 * Belt spans use the same endpoint blend as their geometry. Suspension travel is local -Y;
 * lateral/overhead contacts and spans with only fixed endpoints remain rigid. */
public final class SuspensionContactRouting {
    public static double mobility(boolean first, boolean second, double blend, double normalY) {
        if(normalY<.5)return 0;
        return (first?1-blend:0)+(second?blend:0);
    }
    /** Equivalent wheel travel for a supporting plane. Both movable endpoints receive the
     * same displacement; fixed endpoints retain their mount position. This is the existing
     * massless-wheel suspension model, not a separate rigid body for each belt sample. */
    public static double rawExtension(double extension,double penetration,double normalY,double mobility) {
        if(!(mobility>1e-6)||normalY<.5)throw new IllegalArgumentException("No suspension motion at contact");
        return extension-penetration/(normalY*mobility);
    }
    private SuspensionContactRouting(){}
}
