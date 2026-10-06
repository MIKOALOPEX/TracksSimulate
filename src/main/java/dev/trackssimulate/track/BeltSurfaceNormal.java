package dev.trackssimulate.track;

import dev.trackssimulate.physics.contact.V3;

/** Geometric belt normals, independent of vertex winding and the selected starting wheel. */
public final class BeltSurfaceNormal {
    public static V3 outward(double dx,double dy,int winding,int axis) {
        double length=Math.hypot(dx,dy);
        if(length<1e-8) return V3.ZERO;
        double u=dy*winding/length,v=-dx*winding/length;
        return switch(axis) {case 0->new V3(0,v,u);case 1->new V3(u,0,v);case 2->new V3(u,v,0);default->throw new IllegalArgumentException("axis");};
    }
    private BeltSurfaceNormal() {}
}
