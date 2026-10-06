package dev.trackssimulate.track;

import java.util.List;
import dev.trackssimulate.track.TrackPath.Point;

/** Centre-line intersections, including non-neighbour touches and overlapping runs. */
public final class TrackCrossings {
    private static final double EPS=1e-8;
    public static boolean closed(List<Point> points) {
        int n=points.size();
        for(int i=0;i<n;i++) for(int j=i+1;j<n;j++) {
            boolean neighbours=j==i+1||(i==0&&j==n-1);
            if(intersects(points.get(i),points.get((i+1)%n),points.get(j),points.get((j+1)%n),neighbours)) return true;
        }
        return false;
    }
    static boolean intersects(Point a,Point b,Point c,Point d,boolean neighbours) {
        if(Math.max(a.x(),b.x())+EPS<Math.min(c.x(),d.x())||Math.max(c.x(),d.x())+EPS<Math.min(a.x(),b.x())
            ||Math.max(a.y(),b.y())+EPS<Math.min(c.y(),d.y())||Math.max(c.y(),d.y())+EPS<Math.min(a.y(),b.y())) return false;
        double dx=b.x()-a.x(),dy=b.y()-a.y(),ex=d.x()-c.x(),ey=d.y()-c.y();
        if(dx*dx+dy*dy<EPS*EPS||ex*ex+ey*ey<EPS*EPS) return false;
        double ac=dx*(c.y()-a.y())-dy*(c.x()-a.x()),ad=dx*(d.y()-a.y())-dy*(d.x()-a.x());
        double ca=ex*(a.y()-c.y())-ey*(a.x()-c.x()),cb=ex*(b.y()-c.y())-ey*(b.x()-c.x());
        if(Math.abs(ac)<EPS&&Math.abs(ad)<EPS) {
            boolean x=Math.abs(dx)>=Math.abs(dy);
            double a0=x?a.x():a.y(),a1=x?b.x():b.y(),b0=x?c.x():c.y(),b1=x?d.x():d.y();
            double overlap=Math.min(Math.max(a0,a1),Math.max(b0,b1))-Math.max(Math.min(a0,a1),Math.min(b0,b1));
            return neighbours?overlap>EPS:overlap>=-EPS;
        }
        if(neighbours) return false; // Consecutive non-collinear segments only meet at their common vertex.
        return ((ac>=-EPS&&ad<=EPS)||(ad>=-EPS&&ac<=EPS))&&((ca>=-EPS&&cb<=EPS)||(cb>=-EPS&&ca<=EPS));
    }
    private TrackCrossings() {}
}
