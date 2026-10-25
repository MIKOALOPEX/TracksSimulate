package dev.trackssimulate.trackpath;

import java.util.*;

/** Shared tangent/arc geometry. Role intent is solved separately by BeltTopologyPlanner.
 * Rendering, previews and server validation consume the same directed path. */
public final class OrderedBeltPath {
    public static final int MAX_NODES=64;
    public static final int ROUTING_VERSION=5;
    public record P(double x,double y) {
        public P add(P p){return new P(x+p.x,y+p.y);}
        public P sub(P p){return new P(x-p.x,y-p.y);}
        public P mul(double s){return new P(x*s,y*s);}
        public double dot(P p){return x*p.x+y*p.y;}
        public double cross(P p){return x*p.y-y*p.x;}
        public double length(){return Math.hypot(x,y);}
    }
    public enum Role { ROAD, DRIVE, GUIDE }
    public record Wheel(P center,double radius,Role role) {
        /** Geometry-only fixtures default to a load-carrying wheel. Production supplies roles explicitly. */
        public Wheel(P center,double radius){this(center,radius,Role.ROAD);}
    }
    public record Segment(int from,int to,P a,P b,P center,double sweep) {
        public boolean arc(){return center!=null;}
        public double length(){return arc()?a.sub(center).length()*Math.abs(sweep):b.sub(a).length();}
        public P at(double t){
            if(!arc())return a.add(b.sub(a).mul(t));
            P r=a.sub(center);double q=sweep*t;
            return center.add(new P(r.x*Math.cos(q)-r.y*Math.sin(q),r.x*Math.sin(q)+r.y*Math.cos(q)));
        }
    }
    public record Result(List<Segment> segments,List<Integer> sides,double length,boolean crossing,double tangentError,String error) {
        public boolean valid(){return error.isEmpty()&&!crossing;}
    }
    record Edge(P a,P b) {}
    private static final double TAU=2*Math.PI,EPS=1e-8;

    /** Choose topology from installation, then evaluate the current suspension
     * pose. Transient travel must not become permanent wrap intent on rebuild. */
    public static Result planInstalled(List<Wheel> installation,List<Wheel> pose) {
        if(installation.size()!=pose.size())return fail("安装节点与当前姿态不匹配");
        var topology=plan(installation);
        return topology.valid()?resolve(pose,topology.sides()):topology;
    }

    public static Result plan(List<Wheel> wheels) {
        int n=wheels.size();
        if(n<2||n>MAX_NODES)return fail("需要 2–64 个轮子");
        for(var w:wheels)if(!Double.isFinite(w.center.x)||!Double.isFinite(w.center.y)||!Double.isFinite(w.radius)||w.radius<=0||w.role==null)return fail("轮子几何无效");
        // A cyclic list has no privileged first click or traversal direction.
        int start=0;
        for(int i=1;i<n;i++)if(compare(wheels.get(i),wheels.get(start))<0)start=i;
        int step=n>2&&compare(wheels.get((start+1)%n),wheels.get((start+n-1)%n))>0?-1:1;
        var canonical=new ArrayList<Wheel>();var map=new ArrayList<Integer>();
        for(int i=0;i<n;i++){int index=Math.floorMod(start+step*i,n);map.add(index);canonical.add(wheels.get(index));}
        var result=BeltTopologyPlanner.plan(canonical);
        if(result.sides.isEmpty())return result;
        var sides=new ArrayList<>(Collections.nCopies(n,0));
        for(int i=0;i<n;i++)sides.set(map.get(i),step*result.sides.get(i));
        // Re-evaluate in the caller's node order: segment ownership and belt
        // motion retain player order; only candidate selection is canonical.
        return resolve(wheels,sides);
    }
    private static int compare(Wheel a,Wheel b) {
        int c=Double.compare(a.center.x,b.center.x);if(c!=0)return c;
        c=Double.compare(a.center.y,b.center.y);return c!=0?c:Double.compare(a.radius,b.radius);
    }
    /** Suspension changes geometry, never the committed winding choices. */
    public static Result resolve(List<Wheel> wheels,List<Integer> sides) {
        int n=wheels.size();
        if(n<2||n>MAX_NODES||sides.size()!=n||sides.stream().anyMatch(s->s!=1&&s!=-1))return fail("连接结构无效");
        // Opposite sides on a two-wheel loop are crossing internal tangents;
        // adjacent-segment endpoint exemptions must not legalize that bow tie.
        if(n==2&&!sides.get(0).equals(sides.get(1)))return fail("两轮闭环的内切连接相互交叉");
        for(var w:wheels)if(!Double.isFinite(w.center.x)||!Double.isFinite(w.center.y)||!Double.isFinite(w.radius)||w.radius<=0||w.role==null)return fail("轮子几何无效");
        var choices=sides.stream().map(s->s==1?0:1).toList();
        Edge[][][] edges=new Edge[n][2][2];
        for(int i=0;i<n;i++)edges[i][choices.get(i)][choices.get((i+1)%n)]=tangent(wheels.get(i),wheels.get((i+1)%n),sides.get(i),sides.get((i+1)%n));
        P[] entry=new P[n],exit=new P[n];double[] wraps=new double[n];boolean missing=false,invalidContact=false;
        for(int i=0;i<n;i++) {
            int prev=(i+n-1)%n,next=(i+1)%n,side=choices.get(i);
            Edge in=edges[prev][choices.get(prev)][side],out=edges[i][side][choices.get(next)];
            entry[i]=in==null?null:in.b;exit[i]=out==null?null:out.a;
            if(in==null||out==null){missing=true;continue;}
            P a=in.b.sub(wheels.get(i).center),b=out.a.sub(wheels.get(i).center);
            wraps[i]=positive(sign(side)*(Math.atan2(b.y,b.x)-Math.atan2(a.y,a.x)))*sign(side);
            // A shallow concave change can turn 0 into almost 2*pi. In that
            // case the adjacent planned runs already intersect outside this
            // wheel. Keep their intersection, not the redundant closed detour
            // around the wheel. The installation node and its influence remain;
            // no hull, role filter or fixed wheel-bottom pad is involved.
            if(Math.abs(wraps[i])>Math.PI) {
                P join=intersection(in,out);
                if(join!=null&&join.sub(wheels.get(i).center).length()>=wheels.get(i).radius-EPS) {
                    if(wheels.get(i).role==Role.ROAD) {entry[i]=exit[i]=join;wraps[i]=0;}
                    else invalidContact=true; // A selected guide/drive must retain actual circular contact.
                }
            }
        }
        var segments=new ArrayList<Segment>();double length=0,error=0;
        for(int i=0;i<n;i++) {
            int next=(i+1)%n;
            P center=wheels.get(i).center;
            if(Math.abs(wraps[i])>EPS)segments.add(new Segment(i,i,entry[i],exit[i],center,wraps[i]));
            if(exit[i]==null||entry[next]==null)continue;
            segments.add(new Segment(i,next,exit[i],entry[next],null,0));
            P dir=entry[next].sub(exit[i]);double dl=dir.length();
            if(dl>EPS&&Math.abs(wraps[i])>EPS)error=Math.max(error,Math.abs(dir.dot(exit[i].sub(center))/(dl*wheels.get(i).radius)));
        }
        boolean crossing=false;
        // Full directed polyline validation is only O(node count squared),
        // independent of visual tessellation. Arcs are checked analytically.
        for(int i=0;i<segments.size();i++) {
            var a=segments.get(i);length+=a.length();
            for(int j=i+1;j<segments.size();j++) {
                if(j==i+1 || i==0&&j==segments.size()-1)continue;
                if(crosses(a,segments.get(j)))crossing=true;
            }
        }
        String message=invalidContact?"导向/驱动轮缺少连续相切接触，当前绕向无效":missing?"部分轮对在当前姿态下没有有效切线":length>512?"履带长度超过 512 格":crossing?"当前路径存在交叉":"";
        return new Result(List.copyOf(segments),List.copyOf(sides),length,crossing,error,message);
    }
    static P intersection(Edge a,Edge b) {
        P u=a.b.sub(a.a),v=b.b.sub(b.a),d=b.a.sub(a.a);double den=u.cross(v);
        if(Math.abs(den)<1e-14)return null;
        double t=d.cross(v)/den,s=d.cross(u)/den;
        // Only remove the loop when both finite runs actually meet. This is
        // not an unbounded extension of lines or a minor-arc substitution.
        return t>=-EPS&&t<=1+EPS&&s>=-EPS&&s<=1+EPS?a.a.add(u.mul(t)):null;
    }
    static Edge tangent(Wheel a,Wheel b,int sa,int sb) {
        P delta=b.center.sub(a.center);double d=delta.length(),r=sa*a.radius-sb*b.radius;
        if(d<=Math.abs(r)+EPS)return null;
        P u=delta.mul(1/d);double c=r/d;
        P normal=u.mul(c).add(new P(u.y,-u.x).mul(Math.sqrt(Math.max(0,1-c*c))));
        return new Edge(a.center.add(normal.mul(sa*a.radius)),b.center.add(normal.mul(sb*b.radius)));
    }
    static boolean intersects(P a,P b,P c,P d) {
        P u=b.sub(a),v=d.sub(c);double den=u.cross(v);if(Math.abs(den)<EPS)return false;
        double t=c.sub(a).cross(v)/den,s=c.sub(a).cross(u)/den;
        return t>EPS&&t<1-EPS&&s>EPS&&s<1-EPS;
    }
    private static boolean onArc(Segment s,P p) {
        P a=s.a.sub(s.center),q=p.sub(s.center);
        double t=positive(Math.signum(s.sweep)*(Math.atan2(q.y,q.x)-Math.atan2(a.y,a.x)));
        return t>EPS&&t<Math.abs(s.sweep)-EPS;
    }
    private static boolean crosses(Segment a,Segment b) {
        if(!a.arc()&&!b.arc())return intersects(a.a,a.b,b.a,b.b);
        if(!a.arc())return crosses(b,a);
        if(!b.arc()) {
            P v=b.b.sub(b.a),o=b.a.sub(a.center);double aa=v.dot(v),bb=2*o.dot(v),cc=o.dot(o)-a.a.sub(a.center).dot(a.a.sub(a.center));
            double disc=bb*bb-4*aa*cc;if(aa<EPS||disc<=EPS)return false;
            for(double t:new double[]{(-bb-Math.sqrt(disc))/(2*aa),(-bb+Math.sqrt(disc))/(2*aa)})if(t>EPS&&t<1-EPS&&onArc(a,b.a.add(v.mul(t))))return true;
            return false;
        }
        P delta=b.center.sub(a.center);double d=delta.length(),r=a.a.sub(a.center).length(),s=b.a.sub(b.center).length();
        if(d<EPS||d>=r+s-EPS||d<=Math.abs(r-s)+EPS)return false;
        double x=(r*r-s*s+d*d)/(2*d),h=Math.sqrt(Math.max(0,r*r-x*x));P u=delta.mul(1/d),mid=a.center.add(u.mul(x)),side=new P(-u.y,u.x).mul(h);
        P p=mid.add(side),q=mid.sub(side);return onArc(a,p)&&onArc(b,p)||onArc(a,q)&&onArc(b,q);
    }
    private static int sign(int choice){return choice==0?1:-1;}
    static double positive(double a){double q=(a%TAU+TAU)%TAU;return q>TAU-EPS?0:q;}
    private static Result fail(String error){return new Result(List.of(),List.of(),0,false,0,error);}
    private OrderedBeltPath() {}
}
