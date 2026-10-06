package dev.trackssimulate.physics.contact;

import java.util.ArrayList;
import java.util.List;

/** OBB/OBB separating-axis contact, including full thickness and width, not a downward ray. */
public record ContactBox(V3 center,V3 x,V3 y,V3 z,V3 half) {
    public static final int ALL_FACES=63,POS_X=1,NEG_X=2,POS_Y=4,NEG_Y=8,POS_Z=16,NEG_Z=32;
    public record Hit(V3 point,V3 normal,double penetration) {}
    public V3 extent() {
        return new V3(Math.abs(x.x())*half.x()+Math.abs(y.x())*half.y()+Math.abs(z.x())*half.z(),
            Math.abs(x.y())*half.x()+Math.abs(y.y())*half.y()+Math.abs(z.y())*half.z(),
            Math.abs(x.z())*half.x()+Math.abs(y.z())*half.y()+Math.abs(z.z())*half.z());
    }
    public double radius(V3 n) { return Math.abs(n.dot(x))*half.x()+Math.abs(n.dot(y))*half.y()+Math.abs(n.dot(z))*half.z(); }
    private V3 closest(V3 point) {
        V3 d=point.sub(center);
        return center.add(x.mul(clamp(d.dot(x),half.x()))).add(y.mul(clamp(d.dot(y),half.y()))).add(z.mul(clamp(d.dot(z),half.z())));
    }
    private static double clamp(double v,double limit) { return Math.max(-limit,Math.min(limit,v)); }
    public Hit contact(ContactBox other,double margin) {
        return contact(other,margin,false,ALL_FACES);
    }
    /** Closed belts have no exposed strip end caps. Keep all SAT separation axes, but never resolve against a tessellation seam. */
    public Hit surfaceContact(ContactBox other,double margin) { return surfaceContact(other,margin,ALL_FACES); }
    public Hit surfaceContact(ContactBox other,double margin,int exposedFaces) {return contact(other,margin,true,exposedFaces);}
    private static boolean exposed(double component,int positive,int negative,int mask) {
        return Math.abs(component)<1e-7||(mask&(component>0?positive:negative))!=0;
    }
    private boolean exposedNormal(V3 normal,int mask) {
        // An edge normal is external only when EVERY incident face in that direction is external.
        // A diagonal SAT axis must not turn buried voxel seams into imaginary walls.
        return exposed(normal.dot(x),POS_X,NEG_X,mask)&&exposed(normal.dot(y),POS_Y,NEG_Y,mask)&&exposed(normal.dot(z),POS_Z,NEG_Z,mask);
    }
    private Hit contact(ContactBox other,double margin,boolean beltSurface,int exposedFaces) {
        List<V3> axes=new ArrayList<>(15);axes.add(other.x);axes.add(other.y);axes.add(other.z);axes.add(x);axes.add(y);axes.add(z);
        for(V3 a:List.of(x,y,z)) for(V3 b:List.of(other.x,other.y,other.z)) axes.add(a.cross(b));
        V3 delta=center.sub(other.center),normal=V3.ZERO;double minimum=Double.POSITIVE_INFINITY,smallestOverlap=Double.POSITIVE_INFINITY;
        for(V3 axis:axes) {
            double size=axis.length();if(size<1e-7) continue;
            V3 n=axis.mul(1/size);double distance=delta.dot(n),overlap=radius(n)+other.radius(n)-Math.abs(distance);
            if(overlap < -margin) return null;
            smallestOverlap=Math.min(smallestOverlap,overlap);
            if(beltSurface&&Math.abs(n.dot(x))>.98) continue;
            V3 outward=n.mul(distance<0?-1:1);
            if(!other.exposedNormal(outward,exposedFaces))continue;
            if(overlap<minimum) { minimum=overlap;normal=outward; }
        }
        if(!Double.isFinite(minimum)) return null;
        if(smallestOverlap<0) minimum=Math.min(minimum,smallestOverlap);
        V3 face=center.sub(normal.mul(radius(normal)));
        V3 point=other.closest(face);
        return new Hit(point,normal,minimum);
    }
}
