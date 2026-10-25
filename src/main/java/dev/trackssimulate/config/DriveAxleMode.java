package dev.trackssimulate.config;

/** Stable saved/editor IDs. Left/right are relative to the wheel's facing,
 * not world axes: left = mounting/back face, right = wheel/front face. */
public enum DriveAxleMode {
    LEFT(0, true, false), FULL(1, true, true), RIGHT(2, false, true), NONE(3, false, false);

    public final int id;
    public final boolean back, front;
    DriveAxleMode(int id, boolean back, boolean front) {
        this.id=id;this.back=back;this.front=front;
    }
    public static DriveAxleMode fromSaved(int id, boolean legacyThroughAxle) {
        // Old worlds did not store axle_mode and load its default (0).
        if(id==0&&legacyThroughAxle)return FULL;
        for(var mode:values())if(mode.id==id)return mode;
        return LEFT;
    }
    public String translationKey(){return "axle.trackssimulate."+name().toLowerCase(java.util.Locale.ROOT);}
}
