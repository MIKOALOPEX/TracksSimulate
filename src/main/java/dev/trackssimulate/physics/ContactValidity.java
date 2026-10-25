package dev.trackssimulate.physics;

import dev.trackssimulate.trackpath.BeltSurface.V;

/** A ray that starts inside a solid has no entry face; its reported opposing
 * ray direction is not a terrain contact normal. Cached faces are finite. */
public final class ContactValidity {
    public record Box(V min,V max) {
        public boolean contains(V p,double e){return p.x()>=min.x()-e&&p.x()<=max.x()+e
            &&p.y()>=min.y()-e&&p.y()<=max.y()+e&&p.z()>=min.z()-e&&p.z()<=max.z()+e;}
    }
    public static boolean entry(boolean inside,V outward,V normal){return !inside&&outward.dot(normal)<-.15;}
    public static boolean face(Box box,V hit,V normal) {
        double face=normal.x()>0?box.max.x():normal.x()<0?box.min.x():normal.y()>0?box.max.y():normal.y()<0?box.min.y():normal.z()>0?box.max.z():box.min.z();
        double value=normal.x()!=0?hit.x():normal.y()!=0?hit.y():hit.z();
        return Math.abs(face-value)<1e-5&&box.contains(hit,1e-5);
    }
    public static boolean covers(Box box,V point,V hit,V normal) {
        return box.contains(point.sub(normal.mul(point.sub(hit).dot(normal))),.002);
    }
    private ContactValidity(){}
}
