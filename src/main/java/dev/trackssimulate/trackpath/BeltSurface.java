package dev.trackssimulate.trackpath;

import java.util.*;

/** Detached, shared render/query shell in assembly-local coordinates.
 * Segment identity is topological; sample index is NEVER a material/plate id.
 * Querying this mesh does not register a collider with Minecraft or Sable. */
public final class BeltSurface {
    public static final double HALF_THICKNESS=.035;
    public record V(double x,double y,double z) {
        public V add(V b){return new V(x+b.x,y+b.y,z+b.z);}
        public V sub(V b){return new V(x-b.x,y-b.y,z-b.z);}
        public V mul(double s){return new V(x*s,y*s,z*s);}
        public double dot(V b){return x*b.x+y*b.y+z*b.z;}
        public V cross(V b){return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);}
        public double length(){return Math.sqrt(dot(this));}
        public V unit(){double n=length();return n>1e-12?mul(1/n):new V(0,0,0);}
    }
    public record Key(int from,int to,boolean arc) {}
    public record Span(Key key,double t0,double t1,V a,V b,V normalA,V normalB,
                       double start,double end,double widthA,double widthB) {}
    public record Hit(Key key,double parameter,double distanceAlong,double fraction,V position,V normal) {}
    public record Query(Hit hit,boolean complete,int testedSpans) {}
    private final List<Span> spans;
    private final V axle;
    private final double length;
    private final boolean valid;
    private final String error;
    public List<Span> spans(){return spans;}
    public V axle(){return axle;}
    public double length(){return length;}
    public boolean valid(){return valid;}
    public String error(){return error;}
    private BeltSurface(List<Span> spans,V axle,double length,boolean valid,String error) {
        this.spans=List.copyOf(spans);this.axle=axle;this.length=length;this.valid=valid;this.error=error;
    }
    private static V vector(OrderedBeltPath.P p,boolean axisX){return axisX?new V(0,p.y(),p.x()):new V(p.x(),p.y(),0);}
    private static OrderedBeltPath.P normal(OrderedBeltPath.Segment s,double t) {
        if(s.arc()){var r=s.at(t).sub(s.center());return r.mul(Math.signum(s.sweep())/r.length());}
        var d=s.b().sub(s.a());return d.length()<1e-9?new OrderedBeltPath.P(0,0):new OrderedBeltPath.P(d.y(),-d.x()).mul(1/d.length());
    }
    public static BeltSurface build(OrderedBeltPath.Result path,List<Double> axial,List<Double> widths,boolean axisX) {
        if(axial.size()!=widths.size()||axial.stream().anyMatch(v->!Double.isFinite(v))
            ||widths.stream().anyMatch(v->!Double.isFinite(v)||v<=0))throw new IllegalArgumentException("Invalid surface dimensions");
        V axle=axisX?new V(1,0,0):new V(0,0,1);
        var pieces=new ArrayList<Span>();double distance=0;
        for(var segment:path.segments()) {
            double z0=axial.get(segment.from()),z1=axial.get(segment.to());
            double segmentLength=Math.hypot(segment.length(),z1-z0);
            int count=Math.max(1,(int)Math.ceil(segment.arc()?Math.abs(segment.sweep())/.12:segmentLength*2));
            if(count>8192)throw new IllegalArgumentException("Surface span exceeds budget");
            for(int i=0;i<count;i++) {
                double t=(double)i/count,u=(double)(i+1)/count;
                pieces.add(new Span(new Key(segment.from(),segment.to(),segment.arc()),t,u,
                    vector(segment.at(t),axisX).add(axle.mul(lerp(z0,z1,t))),vector(segment.at(u),axisX).add(axle.mul(lerp(z0,z1,u))),
                    vector(normal(segment,t),axisX),vector(normal(segment,u),axisX),distance+segmentLength*t,distance+segmentLength*u,
                    lerp(widths.get(segment.from()),widths.get(segment.to()),t),lerp(widths.get(segment.from()),widths.get(segment.to()),u)));
            }
            distance+=segmentLength;
        }
        var joins=new V[pieces.size()];
        for(int i=0;i<pieces.size();i++) {
            var current=pieces.get(i);var previous=pieces.get((i+pieces.size()-1)%pieces.size());
            if(current.a.sub(previous.b).length()<1e-6) {
                double den=1+current.normalA.dot(previous.normalB);
                joins[i]=den>.1?current.normalA.add(previous.normalB).mul(1/den):current.normalA;
            }else joins[i]=current.normalA;
        }
        for(int i=0;i<pieces.size();i++) {
            var s=pieces.get(i);int next=(i+1)%pieces.size();
            var end=s.b.sub(pieces.get(next).a).length()<1e-6?joins[next]:s.normalB;
            pieces.set(i,new Span(s.key,s.t0,s.t1,s.a,s.b,joins[i],end,s.start,s.end,s.widthA,s.widthB));
        }
        return new BeltSurface(pieces,axle,distance,path.valid(),path.error());
    }
    public static double lerp(double a,double b,double t){return a+(b-a)*t;}
    /** Exact faces of the shared tessellated shell, including thickness and varying width. */
    public List<V[]> faces(Span s) {
        V ha=axle.mul(s.widthA/2),hb=axle.mul(s.widthB/2),pa=s.normalA.mul(HALF_THICKNESS),pb=s.normalB.mul(HALF_THICKNESS);
        V a=s.a.add(pa),b=s.b.add(pb),c=s.a.sub(pa),d=s.b.sub(pb);
        return List.of(new V[]{a.sub(ha),a.add(ha),b.add(hb),b.sub(hb)},
            new V[]{c.add(ha),c.sub(ha),d.sub(hb),d.add(hb)},
            new V[]{c.sub(ha),a.sub(ha),b.sub(hb),d.sub(hb)},
            new V[]{a.add(ha),c.add(ha),d.add(hb),b.add(hb)});
    }
    /** Finite segment query; an incomplete budget result is NOT a reliable closest hit or miss.
     * Invalid runtime geometry is drawable for diagnostics but cannot produce authoritative hits. */
    public Query intersect(V from,V to,int budget) {
        if(budget<0||!finite(from)||!finite(to))throw new IllegalArgumentException("Invalid query");
        if(!valid)return new Query(null,false,0);
        V direction=to.sub(from);Hit best=null;int tested=0;
        for(var span:spans) {
            if(tested>=budget)return new Query(best,false,tested);
            tested++;
            for(var face:faces(span))for(int tri=0;tri<2;tri++) {
                V a=face[0],b=face[tri+1],c=face[tri+2];
                double f=triangle(from,direction,a,b,c);
                if(Double.isNaN(f)||best!=null&&f>=best.fraction)continue;
                V position=from.add(direction.mul(f)),d=span.b.sub(span.a);
                double u=d.dot(d)>1e-15?Math.max(0,Math.min(1,position.sub(span.a).dot(d)/d.dot(d))):0;
                best=new Hit(span.key,lerp(span.t0,span.t1,u),lerp(span.start,span.end,u),f,position,b.sub(a).cross(c.sub(a)).unit());
            }
        }
        return new Query(best,true,tested);
    }
    private static boolean finite(V v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private static double triangle(V origin,V direction,V a,V b,V c) {
        V e=b.sub(a),f=c.sub(a),h=direction.cross(f);double det=e.dot(h);
        if(Math.abs(det)<1e-12)return Double.NaN;
        V s=origin.sub(a);double u=s.dot(h)/det;if(u< -1e-9||u>1+1e-9)return Double.NaN;
        V q=s.cross(e);double v=direction.dot(q)/det;if(v< -1e-9||u+v>1+1e-9)return Double.NaN;
        double t=f.dot(q)/det;return t>=0&&t<=1?t:Double.NaN;
    }
}
