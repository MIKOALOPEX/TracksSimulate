package dev.trackssimulate.config;

import java.math.BigDecimal;

/** Shared numeric contract for the debug editor and server-side writes. */
public enum TrackSetting {
    STRENGTH("strength", 5, 180, 5, 16, true),
    SPRING("spring", .1, 4, .05, 1, false),
    DAMPING("damping", .1, 4, .05, 1, false),
    BUMP_CLEARANCE("bump_clearance", .1, 4, .05, 1, false),
    BUMP_FORCE("bump_force", .1, 4, .05, 1, false),
    MAX_IMPULSE("max_impulse", .1, 4, .05, 1, false),
    DRIVE("drive", .1, 4, .1, 1, false),
    GRIP("grip", .1, 4, .05, 1, false),
    LATERAL("lateral", -1, 1, .125, 0, false),
    LONGITUDINAL("longitudinal", -1, 1, .125, 0, false),
    HEIGHT("height", -.75, .75, .125, 0, false),
    SCALE("scale", .25, 5, .05, 1, false),
    WIDTH("width", .25, 3, .05, 1, false),
    HIDDEN("hidden", 0, 1, 1, 0, true),
    WHEEL_HIDDEN("wheel_hidden", 0, 1, 1, 0, true),
    THROUGH_AXLE("through_axle", 0, 3, 1, 0, true),
    SPEED("speed", .1, 8, .1, 1, false),
    MAX_EXTENSION("max_extension", .05, 3, .05, .75, false),
    MAX_COMPRESSION("max_compression", 0, 3.75, .05, .45, false),
    CONTACT_SPREAD("contact_spread", .25, 1.25, .05, 1, false),
    CONTACT_MARGIN("contact_margin", 0, 1, .05, .25, false),
    FRICTION_FORWARD("friction_forward", 0, 4, .1, 1, false),
    FRICTION_SIDE("friction_side", 0, 4, .1, 1, false),
    CONNECTED_CONTACTS("connected_contacts",0,1,1,0,true);

    public boolean resetsWithSuspension() {
        return ordinal()<LATERAL.ordinal()||ordinal()>=MAX_EXTENSION.ordinal();
    }

    public final String key;
    public final double min, max, step, defaultValue;
    public final boolean integer;

    TrackSetting(String key, double min, double max, double step, double defaultValue, boolean integer) {
        this.key = key;
        this.min = min;
        this.max = max;
        this.step = step;
        this.defaultValue = defaultValue;
        this.integer = integer;
    }

    public boolean supports(boolean roadWheel,boolean driveWheel) {
        if(this==THROUGH_AXLE||this==SPEED)return driveWheel;
        if(this==CONNECTED_CONTACTS)return true;
        if(this==FRICTION_FORWARD||this==FRICTION_SIDE||this==GRIP||this==DRIVE)return true;
        return roadWheel || isOffset() || this==SCALE || this==WIDTH || this==WHEEL_HIDDEN;
    }
    public static double scaledRadius(double baseRadius,double scale) {
        if(!Double.isFinite(baseRadius)||baseRadius<=0||!SCALE.accepts(scale))throw new IllegalArgumentException("Invalid scale");
        return baseRadius*scale;
    }
    public boolean isOffset(){return this==LATERAL||this==LONGITUDINAL||this==HEIGHT;}

    public boolean accepts(double value) {
        return Double.isFinite(value) && value >= min && value <= max && (!integer || value == Math.rint(value));
    }

    public Double parse(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return accepts(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public double increment(double value, int direction) {
        // Preserve manually entered precision instead of snapping to the button's grid.
        double next = BigDecimal.valueOf(value).add(BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(direction))).doubleValue();
        return Math.max(min, Math.min(max, next));
    }

    public static TrackSetting byKey(String key) {
        for (TrackSetting setting : values()) if (setting.key.equals(key)) return setting;
        return null;
    }

    public static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
