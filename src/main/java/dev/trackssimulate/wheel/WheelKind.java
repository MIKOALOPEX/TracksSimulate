package dev.trackssimulate.wheel;

public enum WheelKind {
    DRIVE("drive_wheel",0.62118940,0.99375),
    ROAD("road_wheel",0.59271938,0.88125),
    RETURN("return_wheel",0.17221562,0.978125);
    public final String id;
    public final double radius,width;
    WheelKind(String id,double radius,double width) { this.id=id;this.radius=radius;this.width=width; }
}
