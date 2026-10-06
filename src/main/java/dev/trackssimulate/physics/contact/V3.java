package dev.trackssimulate.physics.contact;

public record V3(double x,double y,double z) {
    public static final V3 ZERO=new V3(0,0,0),X=new V3(1,0,0),Y=new V3(0,1,0),Z=new V3(0,0,1);
    public V3 add(V3 v) { return new V3(x+v.x,y+v.y,z+v.z); }
    public V3 sub(V3 v) { return new V3(x-v.x,y-v.y,z-v.z); }
    public V3 mul(double s) { return new V3(x*s,y*s,z*s); }
    public double dot(V3 v) { return x*v.x+y*v.y+z*v.z; }
    public V3 cross(V3 v) { return new V3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x); }
    public double length() { return Math.sqrt(dot(this)); }
    public V3 unit() { double n=length();return n<1e-10?ZERO:mul(1/n); }
}
