package dev.trackssimulate.physics;

/** Signed wheel-centre travel measured downwards from the configured mount.
 * Physics and rendering use the same limits; terrain beyond full droop cannot
 * attract the vehicle. A compression stop is a velocity constraint, not merely
 * a mesh clamp. Compression is stroke FROM full extension, not an upward
 * coordinate from the mount. Downward offset is the negative of GUI height. */
public record SuspensionLimits(double extension, double compression, double downwardOffset) {
    public SuspensionLimits {
        if(!Double.isFinite(extension)||!Double.isFinite(compression)||!Double.isFinite(downwardOffset)
            ||extension<=0||compression<0||compression>extension+downwardOffset+1e-9)
            throw new IllegalArgumentException("Invalid suspension limits");
    }
    public SuspensionLimits(double extension,double compression){this(extension,compression,0);}
    public double minimumExtension(){return extension-compression;}
    public double maximumDrop(){return downwardOffset+extension;}
    public double minimumDrop(){return downwardOffset+minimumExtension();}
    /** Compatibility is only used while reading old/invalid saved data, never
     * silently to accept a player's invalid multi-wheel edit. */
    public static SuspensionLimits restore(double extension,double compression,double down,boolean legacy) {
        double restoredExtension=Math.max(extension,-down);
        double stroke=legacy?restoredExtension+compression:compression;
        return new SuspensionLimits(restoredExtension,Math.max(0,Math.min(stroke,restoredExtension+down)),down);
    }
    public double clamp(double raw){return Math.max(minimumExtension(),Math.min(extension,raw));}
    public boolean reachable(double raw){return Double.isFinite(raw)&&raw<=extension;}
    public double penetration(double raw){return Math.max(0,minimumExtension()-raw);}
    public double bumpStart(double clearanceMultiplier){return minimumExtension()+Math.min(.15*clearanceMultiplier,compression*.9);}
    public double stopTarget(double raw,double normalY,double dt) {
        if(!(dt>0)||!Double.isFinite(dt)||!Double.isFinite(raw)||!Double.isFinite(normalY)||normalY<=0)
            throw new IllegalArgumentException("Invalid stop contact");
        double gap=(raw-minimumExtension())*normalY;
        // Permit approach while there is travel. At/beyond the stop, remove
        // inward velocity and recover overlap gradually, without restitution.
        return gap>=0?-gap/dt:Math.min(2,-gap*.2/dt);
    }
}
