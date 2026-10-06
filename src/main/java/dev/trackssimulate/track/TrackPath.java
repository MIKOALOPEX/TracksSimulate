package dev.trackssimulate.track;

import java.util.ArrayList;
import java.util.List;

/** Ordered signed tangents and per-wheel wrapping. No sorting or hull replacement. */
public final class TrackPath {
    public record Point(double x, double y) {
        public Point add(Point b) { return new Point(x+b.x, y+b.y); }
        public Point sub(Point b) { return new Point(x-b.x, y-b.y); }
        public Point mul(double s) { return new Point(x*s,y*s); }
        public double length() { return Math.hypot(x,y); }
        public double cross(Point b) { return x*b.y-y*b.x; }
    }
    /** Side: 0 unspecified, 1 top, 2 right, 3 bottom, 4 left in the axle plane.
     * A click hint overrides the wheel's automatic preference; crossing avoidance still takes priority.
     */
    public record Wheel(Point center,double radius,int hint,int preferredSide,boolean roadSupport,boolean guide) {
        public Wheel(Point center,double radius,int hint,int preferredSide,boolean roadSupport) {this(center,radius,hint,preferredSide,roadSupport,false);}
        public Wheel(Point center,double radius,int hint,int preferredSide) { this(center,radius,hint,preferredSide,false); }
        public Wheel(Point center,double radius,int hint) { this(center,radius,hint,0,false); }
        public Wheel(Point center,double radius) { this(center,radius,0,0,false); }
    }
    public record Path(List<Point> points,double length,int winding,List<Integer> wheelDirections,boolean crossing,List<Integer> contactWheels) {}
    public record ContactSpan(Point start,Point end,int first,int second,double secondWeight) {}
    /** Two-point strips with explicit endpoint load routing; the rendered tessellation is not used as a mass count. */
    public static List<ContactSpan> contactSpans(List<Wheel> wheels,List<Integer> directions,double spacing) {
        validateDirections(wheels,directions);
        SupportLayout layout=supportLayout(wheels);
        List<Integer> selected=layout.directions(directions);
        return contactSpansAll(layout.wheels,selected,spacing).stream().map(s->new ContactSpan(s.start,s.end,
            layout.indices.get(s.first),layout.indices.get(s.second),s.secondWeight)).toList();
    }
    private static List<ContactSpan> contactSpansAll(List<Wheel> wheels,List<Integer> directions,double spacing) {
        validate(wheels);
        if(spacing<.1||spacing>1||directions.size()!=wheels.size()||directions.stream().anyMatch(d->d!=1&&d!=-1))
            throw new IllegalArgumentException("Invalid contact layout");
        List<ContactSpan> result=new ArrayList<>();int n=wheels.size();
        for(int i=0;i<n;i++) {
            int previous=(i+n-1)%n,next=(i+1)%n,s=directions.get(i);Wheel wheel=wheels.get(i);
            Edge in=edge(wheels.get(previous),wheel,directions.get(previous),s),out=edge(wheel,wheels.get(next),s,directions.get(next));
            Point a=in.end.sub(wheel.center),b=out.start.sub(wheel.center);
            double angle=Math.atan2(a.y,a.x),sweep=mod(s*(Math.atan2(b.y,b.x)-angle),TAU);
            int steps=Math.max(1,(int)Math.ceil(sweep/Math.min(Math.toRadians(12),spacing/wheel.radius)));
            Point from=in.end;
            for(int j=1;j<=steps;j++) {
                double t=angle+s*sweep*j/steps;
                Point to=j==steps?out.start:wheel.center.add(new Point(Math.cos(t),Math.sin(t)).mul(wheel.radius));
                if(to.sub(from).length()>1e-6) result.add(new ContactSpan(from,to,i,i,0));from=to;
            }
            steps=Math.max(1,(int)Math.ceil(out.end.sub(out.start).length()/spacing));from=out.start;
            for(int j=1;j<=steps;j++) {
                Point to=out.start.add(out.end.sub(out.start).mul((double)j/steps));
                if(to.sub(from).length()>1e-6) result.add(new ContactSpan(from,to,i,next,(j-.5)/steps));from=to;
            }
        }
        if(result.size()>MAX_CONTACT_SPANS) throw new IllegalArgumentException("物理履带带段超过 "+MAX_CONTACT_SPANS+" 上限");
        return result;
    }
    public static final int MAX_WHEELS=32;
    public static final double MAX_SPAN=64;
    public static final double MAX_LENGTH=256;
    public static final int MAX_SELECTION_RADIUS=128;
    public static final int MAX_RENDER_POINTS=4096,MAX_CONTACT_SPANS=1536;
    private static final double TAU=Math.PI*2;
    private record Edge(Point start,Point end,double cost) {}
    private record Choice(double cost,List<Integer> signs) {}
    public static Path build(List<Wheel> wheels) {
        validate(wheels);
        SupportLayout layout=supportLayout(wheels);
        return layout.expand(buildAll(layout.wheels,layout.locked),null);
    }
    private static Path buildAll(List<Wheel> wheels,int[] locked) {
        double area=validate(wheels);
        int n=wheels.size();
        // Each wheel has its own wrapping direction; retain every selected node.
        Edge[][][] edges=edges(wheels);
        Choice best=null;int preferred=area<0?1:0;
        for(int seed=0;seed<4;seed++) {
            int first=(seed/2)^preferred,second=(seed%2)^preferred;
            if(!allowed(locked,0,first)||!allowed(locked,1,second)) continue;
            Choice[] states=new Choice[4];
            states[first*2+second]=new Choice(edges[0][first][second].cost,List.of(first,second));
            for(int i=1;i<n-1;i++) {
                Choice[] next=new Choice[4];
                for(int key=0;key<4;key++) if(states[key]!=null) for(int c=0;c<2;c++) {
                    if(!allowed(locked,i+1,c)) continue;
                    int a=key/2,b=key%2;
                    double cost=states[key].cost+edges[i][b][c].cost+arcCost(wheels.get(i),edges[i-1][a][b],edges[i][b][c],sign(b));
                    int target=b*2+c;
                    if(next[target]==null||cost<next[target].cost-1e-9) {
                        List<Integer> signs=new ArrayList<>(states[key].signs);signs.add(c);next[target]=new Choice(cost,signs);
                    }
                }
                states=next;
            }
            for(int key=0;key<4;key++) if(states[key]!=null) {
                int a=key/2,b=key%2;
                double cost=states[key].cost+edges[n-1][b][first].cost
                    +arcCost(wheels.get(n-1),edges[n-2][a][b],edges[n-1][b][first],sign(b))
                    +arcCost(wheels.getFirst(),edges[n-1][b][first],edges[0][first][second],sign(first));
                if(best==null||cost<best.cost-1e-9) best=new Choice(cost,states[key].signs);
            }
        }
        Path initial=materialize(wheels,edges,best.signs,area);
        if(!initial.crossing()) return initial;
        Search search=new Search(wheels,edges,best.signs,area,locked);
        Path alternative=search.run();
        return alternative==null?initial:alternative;
    }
    private static double validate(List<Wheel> wheels) {
        int n=wheels.size();
        if(n<2||n>MAX_WHEELS) throw new IllegalArgumentException("需要 2–32 个轮子");
        double area=0;
        for(int i=0;i<n;i++) {
            Wheel w=wheels.get(i);
            if(!Double.isFinite(w.radius)||w.radius<=0||w.radius>2||!Double.isFinite(w.center.x)||!Double.isFinite(w.center.y)||w.hint<0||w.hint>4||w.preferredSide<0||w.preferredSide>4)
                throw new IllegalArgumentException("轮子参数无效");
            if(w.center.sub(wheels.get((i+1)%n).center).length()>MAX_SPAN) throw new IllegalArgumentException("相邻轮距离超过 "+(int)MAX_SPAN+" 格");
            area+=w.center.cross(wheels.get((i+1)%n).center);
        }
        return area;
    }
    private static Edge[][][] edges(List<Wheel> wheels) {
        int n=wheels.size();Edge[][][] edges=new Edge[n][2][2];
        for(int i=0;i<n;i++) for(int a=0;a<2;a++) for(int b=0;b<2;b++) edges[i][a][b]=edge(wheels.get(i),wheels.get((i+1)%n),sign(a),sign(b));
        return edges;
    }
    public static Path withDirections(List<Wheel> wheels,List<Integer> directions) {
        validateDirections(wheels,directions);
        SupportLayout layout=supportLayout(wheels);
        List<Wheel> active=layout.wheels;
        return layout.expand(materialize(active,edges(active),layout.directions(directions).stream().map(d->d==1?0:1).toList(),validate(wheels)),directions);
    }
    /** Keep committed topology during suspension travel. No global route search in the render loop. */
    public static Path deform(List<Wheel> wheels,Path topology) {
        validate(wheels);
        if(wheels.size()!=topology.wheelDirections.size()) throw new IllegalArgumentException("轮子数与闭环拓扑不符");
        SupportLayout layout=supportLayout(wheels);
        return layout.expand(materialize(layout.wheels,edges(layout.wheels),layout.directions(topology.wheelDirections).stream().map(d->d==1?0:1).toList(),topology.winding,false,topology.crossing),topology.wheelDirections);
    }
    private static List<Point> piece(List<Wheel> wheels,Edge[][][] edges,int i,int prev,int current,int next,boolean fine) {
        int n=wheels.size(),s=sign(current);Wheel w=wheels.get(i);
        Edge in=edges[(i+n-1)%n][prev][current],out=edges[i][current][next];
        Point incoming=in.end.sub(w.center),outgoing=out.start.sub(w.center);
        double start=Math.atan2(incoming.y,incoming.x),sweep=mod(s*(Math.atan2(outgoing.y,outgoing.x)-start),TAU);
        int steps=Math.max(1,(int)Math.ceil(sweep/Math.toRadians(10)));
        List<Point> points=new ArrayList<>();
        for(int j=0;j<=steps;j++) { double angle=start+s*sweep*j/steps;append(points,w.center.add(new Point(Math.cos(angle),Math.sin(angle)).mul(w.radius))); }
        steps=fine?Math.max(1,(int)Math.ceil(out.end.sub(out.start).length()/.25)):1;
        for(int j=1;j<=steps;j++) append(points,out.start.add(out.end.sub(out.start).mul((double)j/steps)));
        return points;
    }
    private static Path materialize(List<Wheel> wheels,Edge[][][] edges,List<Integer> choices,double area) {
        return materialize(wheels,edges,choices,area,true,false);
    }
    private static Path materialize(List<Wheel> wheels,Edge[][][] edges,List<Integer> choices,double area,boolean checkCrossing,boolean previousCrossing) {
        List<Point> points=new ArrayList<>(),coarse=new ArrayList<>();List<Integer> directions=new ArrayList<>();int n=wheels.size();
        for(int i=0;i<n;i++) {
            int prev=choices.get((i+n-1)%n),current=choices.get(i),next=choices.get((i+1)%n);
            directions.add(sign(current));
            for(Point p:piece(wheels,edges,i,prev,current,next,true)) append(points,p);
            if(checkCrossing) for(Point p:piece(wheels,edges,i,prev,current,next,false)) append(coarse,p);
        }
        trimClosure(points);trimClosure(coarse);
        if(points.size()>MAX_RENDER_POINTS) throw new IllegalArgumentException("履带段数超过原型上限");
        double length=0;
        for(int i=0;i<points.size();i++) length+=points.get(i).sub(points.get((i+1)%points.size())).length();
        if(length<1e-6||length>MAX_LENGTH) throw new IllegalArgumentException("履带总长必须大于零且不超过 "+(int)MAX_LENGTH+" 格");
        return new Path(List.copyOf(points),length,area<0?-1:1,List.copyOf(directions),checkCrossing?TrackCrossings.closed(coarse):previousCrossing,
            java.util.stream.IntStream.range(0,n).boxed().toList());
    }
    private static void trimClosure(List<Point> points) {
        if(points.size()>1&&points.getFirst().sub(points.getLast()).length()<1e-8) points.removeLast();
    }
    private static final class Search {
        final List<Wheel> wheels;final Edge[][][] edges;final List<Integer> preferred;final double area;
        final int[] choices,locked;int visits,comparisons;Path result;
        // Deterministic work limit: previews and server commits always choose the same path.
        static final int MAX_VISITS=16384,MAX_COMPARISONS=1000000;
        Search(List<Wheel> wheels,Edge[][][] edges,List<Integer> preferred,double area,int[] locked) {
            this.wheels=wheels;this.edges=edges;this.preferred=preferred;this.area=area;this.locked=locked;choices=new int[wheels.size()];
        }
        Path run() {
            for(int a=0;a<2&&!exhausted();a++) for(int b=0;b<2&&!exhausted();b++) {
                choices[0]=preferred.get(0)^a;choices[1]=preferred.get(1)^b;
                if(!allowed(locked,0,choices[0])||!allowed(locked,1,choices[1])) continue;
                if(visit(2,new ArrayList<>())) return result;
            }
            return null;
        }
        boolean exhausted() { return visits>=MAX_VISITS||comparisons>=MAX_COMPARISONS; }
        boolean visit(int depth,List<Point> fixed) {
            if(exhausted()) return false;visits++;
            int n=choices.length;
            if(depth==n) {
                List<Point> closed=new ArrayList<>(fixed);
                if(!add(closed,piece(wheels,edges,n-1,choices[n-2],choices[n-1],choices[0],false),false)) return false;
                if(!add(closed,piece(wheels,edges,0,choices[n-1],choices[0],choices[1],false),true)) return false;
                List<Integer> selection=new ArrayList<>();for(int choice:choices) selection.add(choice);
                try { Path path=materialize(wheels,edges,selection,area);if(!path.crossing()) { result=path;return true; } }
                catch(IllegalArgumentException ignored) {}
                return false;
            }
            for(int option=0;option<2&&!exhausted();option++) {
                choices[depth]=preferred.get(depth)^option;
                if(!allowed(locked,depth,choices[depth])) continue;
                List<Point> extended=new ArrayList<>(fixed);
                if(add(extended,piece(wheels,edges,depth-1,choices[depth-2],choices[depth-1],choices[depth],false),false)&&visit(depth+1,extended)) return true;
            }
            return false;
        }
        boolean add(List<Point> points,List<Point> addition,boolean closes) {
            for(int i=0;i<addition.size();i++) {
                Point end=addition.get(i);
                if(points.isEmpty()) { points.add(end);continue; }
                Point start=points.getLast();if(end.sub(start).length()<1e-8) continue;
                for(int j=0;j<points.size()-1;j++) {
                    if(++comparisons>MAX_COMPARISONS) return false;
                    boolean neighbours=j==points.size()-2||(closes&&i==addition.size()-1&&j==0&&end.sub(points.getFirst()).length()<1e-8);
                    if(TrackCrossings.intersects(points.get(j),points.get(j+1),start,end,neighbours)) return false;
                }
                points.add(end);
            }
            return true;
        }
    }
    private static Edge edge(Wheel a,Wheel b,int sa,int sb) {
        Point d=b.center.sub(a.center);double distance=d.length(),delta=sa*a.radius-sb*b.radius;
        Point u=distance<1e-8?new Point(1,0):d.mul(1/distance);
        boolean degenerate=distance<=Math.abs(delta)+1e-8;
        // For containment/coincident geometry use a deterministic straight transition,
        // not a claim that intersecting circles have a physically valid common tangent.
        double c=degenerate?0:delta/distance;
        Point normal=u.mul(c).add(new Point(u.y,-u.x).mul(Math.sqrt(Math.max(0,1-c*c))));
        Point start=a.center.add(normal.mul(sa*a.radius)),end=b.center.add(normal.mul(sb*b.radius));
        return new Edge(start,end,end.sub(start).length()+(degenerate?10000:0));
    }
    private static double arcCost(Wheel w,Edge in,Edge out,int s) {
        Point a=in.end.sub(w.center),b=out.start.sub(w.center);
        double start=Math.atan2(a.y,a.x),sweep=mod(s*(Math.atan2(b.y,b.x)-start),TAU);
        // A collinear road row must not flip above its wheels merely to shorten the two end transitions.
        // Retain the weak preference as a tie-break even for ambiguous left/right click hints.
        return w.radius*sweep+1000*sideDistance(w.hint,start,sweep,s)+10*sideDistance(w.preferredSide,start,sweep,s);
    }
    private static double sideDistance(int side,double start,double sweep,int s) {
        if(side==0) return 0;
        double angle=switch(side) { case 1->Math.PI/2;case 2->0;case 3->-Math.PI/2;default->Math.PI; };
        double along=mod(s*(angle-start),TAU);
        return along>sweep+1e-7?Math.min(along-sweep,TAU-along):0;
    }
    private static boolean allowed(int[] locked,int index,int choice) { return locked[index]==0||locked[index]==sign(choice); }
    private static void validateDirections(List<Wheel> wheels,List<Integer> directions) {
        validate(wheels);
        if(directions.size()!=wheels.size()||directions.stream().anyMatch(d->d!=1&&d!=-1)) throw new IllegalArgumentException("包覆方向无效");
    }
    private record SupportLayout(List<Wheel> wheels,List<Integer> indices,int[] locked,int[] originalLocks,int originalCount) {
        List<Integer> directions(List<Integer> saved) {
            List<Integer> result=new ArrayList<>();
            for(int i=0;i<indices.size();i++) result.add(locked[i]!=0?locked[i]:saved.get(indices.get(i)));
            return result;
        }
        Path expand(Path active,List<Integer> saved) {
            List<Integer> all=new ArrayList<>();
            for(int i=0;i<originalCount;i++) all.add(originalLocks[i]!=0?originalLocks[i]:saved==null?active.winding:saved.get(i));
            for(int i=0;i<indices.size();i++) all.set(indices.get(i),active.wheelDirections.get(i));
            return new Path(active.points,active.length,active.winding,List.copyOf(all),active.crossing,indices);
        }
    }
    /** Ordered unilateral support: a road wheel can push the lower run, but cannot pull it upward.
     * This removes contact, never group membership or the player's ordering. Non-road routing nodes remain mandatory.
     * Rebuild from all members on every deformation so an extending wheel can re-enter contact.
     */
    private static SupportLayout supportLayout(List<Wheel> original) {
        int n=original.size();List<Integer> active=new ArrayList<>();int[] locked=new int[n];
        double area=0;
        for(int i=0;i<n;i++)area+=original.get(i).center.cross(original.get((i+1)%n).center);
        for(int i=0;i<n;i++) {
            active.add(i);
            if(original.get(i).roadSupport) locked[i]=lowerDirection(original.get((i+n-1)%n),original.get(i),original.get((i+1)%n));
            else if(original.get(i).guide&&Math.abs(area)>1e-7&&outerCorner(original,i,area<0?-1:1))locked[i]=area<0?-1:1;
        }
        boolean changed=true;
        while(changed&&active.size()>2) {
            changed=false;
            for(int k=0;k<active.size();k++) {
                int before=active.get((k+active.size()-1)%active.size()),index=active.get(k),after=active.get((k+1)%active.size());
                Wheel a=original.get(before),w=original.get(index),b=original.get(after);
                if(!w.roadSupport) continue;
                int s=lowerDirection(a,w,b);
                boolean contained=w.center.sub(a.center).length()+w.radius<a.radius-1e-7||w.center.sub(b.center).length()+w.radius<b.radius-1e-7;
                if(s==0&&contained&&Math.abs(b.center.x-a.center.x)>1e-7) s=b.center.x>a.center.x?1:-1;
                if(s==0||(locked[before]!=0&&locked[before]!=s)||(locked[after]!=0&&locked[after]!=s)) continue;
                double distance=b.center.sub(a.center).length();
                if(distance<=Math.abs(a.radius-b.radius)+1e-7) continue;
                Edge bridge=edge(a,b,s,s);Point delta=bridge.end.sub(bridge.start);double length=delta.length();
                if(length<1e-7) continue;
                Point tangent=delta.mul(1/length),inward=new Point(-tangent.y,tangent.x).mul(s),relative=w.center.sub(bridge.start);
                double along=relative.x*tangent.x+relative.y*tangent.y,clearance=relative.x*inward.x+relative.y*inward.y-w.radius;
                // Keep exact tangency as a live support, including equal-radius collinear wheel rows.
                if(contained||(along>=-1e-7&&along<=length+1e-7&&clearance>1e-7)) {
                    locked[before]=s;locked[after]=s;locked[index]=s;active.remove(k);changed=true;break;
                }
            }
        }
        List<Wheel> wheels=new ArrayList<>();int[] activeLocks=new int[active.size()];
        for(int i=0;i<active.size();i++) {
            Wheel w=original.get(active.get(i));
            // A road-wheel face click cannot turn a unilateral support into an upper routing pulley.
            wheels.add(w.roadSupport?new Wheel(w.center,w.radius,0,3,true):w);
            activeLocks[i]=locked[active.get(i)];
        }
        return new SupportLayout(List.copyOf(wheels),List.copyOf(active),activeLocks,locked,n);
    }
    /** An exposed convex routing corner must enclose its wheel, not minimize length by back-wrapping it.
     * Only constrain direction: keep the player's order and every mandatory routing node.
     * Interior/concave rollers remain free to use reverse wrapping and explicit side hints.
     */
    private static boolean outerCorner(List<Wheel> wheels,int index,int winding) {
        int n=wheels.size();Point center=wheels.get(index).center;
        Point in=center.sub(wheels.get((index+n-1)%n).center),out=wheels.get((index+1)%n).center.sub(center);
        if(in.length()<1e-7||out.length()<1e-7||in.cross(out)*winding<=1e-7)return false;
        in=in.mul(1/in.length());out=out.mul(1/out.length());
        Point outward=new Point(in.y+out.y,-in.x-out.x).mul(winding);
        for(Wheel wheel:wheels) {
            Point relative=wheel.center.sub(center);
            if(relative.x*outward.x+relative.y*outward.y>1e-7)return false;
        }
        return true;
    }
    private static int lowerDirection(Wheel before,Wheel wheel,Wheel after) {
        double incoming=wheel.center.x-before.center.x,outgoing=after.center.x-wheel.center.x;
        return incoming>1e-7&&outgoing>1e-7?1:incoming< -1e-7&&outgoing< -1e-7?-1:0;
    }
    private static int sign(int index) { return index==0?1:-1; }
    private static double mod(double v,double m) { return (v%m+m)%m; }
    private static void append(List<Point> p,Point v) { if(p.isEmpty()||v.sub(p.getLast()).length()>1e-8) p.add(v); }
    private TrackPath() {}
}
