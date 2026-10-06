package dev.trackssimulate.wheel;

/** Physical dimensions and local anchor translation. Visibility is deliberately separate. */
public record WheelGeometry(double radiusScale,double widthScale,double forward,double vertical,double axial) {
    public static final WheelGeometry DEFAULT=new WheelGeometry(1,1,0,0,0);
    public record Offset(double x,double y,double z) {}
    /** Width is shared by every member; radius and unrelated settings have independent scopes. */
    public WheelGeometry applyGroupDimensions(WheelGeometry edited,boolean copyLocal,boolean copyRadius) {
        return new WheelGeometry(copyRadius?edited.radiusScale:radiusScale,edited.widthScale,
            copyLocal?edited.forward:forward,copyLocal?edited.vertical:vertical,copyLocal?edited.axial:axial);
    }
    public WheelGeometry {
        if(!Double.isFinite(radiusScale)||!Double.isFinite(widthScale)||radiusScale<.25||radiusScale>3||widthScale<.25||widthScale>3
            ||!Double.isFinite(forward)||!Double.isFinite(vertical)||!Double.isFinite(axial)
            ||Math.abs(forward)>4||Math.abs(vertical)>4||Math.abs(axial)>4)
            throw new IllegalArgumentException("轮径/轮宽倍率为 0.25–3，三向偏移为 -4–4 格");
    }
    public Offset offset(int axis) {
        return switch(axis) {
            case 0->new Offset(axial,vertical,forward);
            case 1->new Offset(forward,axial,vertical);
            case 2->new Offset(forward,vertical,axial);
            default->throw new IllegalArgumentException("Unknown axle");
        };
    }
    public Offset halfExtents(int axis,double baseRadius,double baseWidth) {
        double r=baseRadius*radiusScale,w=baseWidth*widthScale*.5;
        return switch(axis) {
            case 0->new Offset(w,r,r);case 1->new Offset(r,w,r);case 2->new Offset(r,r,w);
            default->throw new IllegalArgumentException("Unknown axle");
        };
    }
}
