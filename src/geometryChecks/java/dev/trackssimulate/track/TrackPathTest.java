package dev.trackssimulate.track;

import java.util.*;

/** Standalone deterministic geometry checks, without starting Minecraft. */
public final class TrackPathTest {
    private static int checks;
    private static TrackPath.Wheel w(double x,double y,double r) { return new TrackPath.Wheel(new TrackPath.Point(x,y),r); }
    private static void check(boolean condition,String message) { checks++;if(!condition) throw new AssertionError(message); }
    private static void rejects(List<TrackPath.Wheel> wheels,String message) {
        try { TrackPath.build(wheels);throw new AssertionError(message); }
        catch(IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        extendedLengths();
        roadWheelRows();
        var pair=TrackPath.build(List.of(w(0,0,.5),w(4,0,.5)));
        check(Math.abs(pair.length()-(8+Math.PI))<.01,"Two-wheel capsule perimeter");
        check(pair.points().stream().allMatch(p->Math.abs(p.y())<=.500001),"Capsule bounds");
        var tri=List.of(w(0,0,.6),w(4,0,.6),w(2,3,.6));
        var forward=TrackPath.build(tri);
        var reverse=TrackPath.build(List.of(tri.get(0),tri.get(2),tri.get(1)));
        check(forward.winding()==-reverse.winding(),"Selection order controls direction");
        check(Math.abs(forward.length()-reverse.length())<1e-8,"Reversing order preserves path length");
        check(Math.abs(forward.length()-(4+2*Math.sqrt(13)+2*Math.PI*.6))<.02,"Triangle rounded perimeter");
        var unequal=TrackPath.build(List.of(w(0,0,.6),w(4,0,.2)));
        double tangent=Math.sqrt(16-.16),angle=Math.asin(.1);
        check(Math.abs(unequal.length()-(2*tangent+.6*(Math.PI+2*angle)+.2*(Math.PI-2*angle)))<.01,"Unequal radii tangent length");
        rejects(List.of(w(0,0,.6)),"Single wheel rejected");
        rejects(List.of(w(0,0,.6),w(0,0,.6)),"Coincident wheels rejected");
        check(TrackPath.build(List.of(w(0,0,.6),w(1,0,.6))).length()>0,"Overlapping wheels accepted");
        check(TrackPath.build(List.of(w(0,0,.5),w(4,4,.5),w(0,4,.5),w(4,0,.5))).wheelDirections().size()==4,"Crossing selection retains all nodes");
        check(TrackPath.build(List.of(w(0,0,.8),w(.1,0,.2),w(4,0,.5))).length()>0,"Contained wheels remain selectable");
        check(TrackPath.build(List.of(w(0,0,.5),w(0,0,.5),w(4,0,.5))).wheelDirections().size()==3,"Coincident nodes retained when loop has extent");
        for(int rotation=0;rotation<4;rotation++) {
            List<TrackPath.Wheel> layout=new ArrayList<>();
            double[][] coords={{0,0},{2,1},{4,0},{4,4},{0,4}};
            for(int i=0;i<coords.length;i++) {
                double x=coords[i][0],y=coords[i][1];
                for(int j=0;j<rotation;j++) { double t=x;x=-y;y=t; }
                // Request the inner roller's upper/left/lower/right quadrant.
                int hint=i==1?new int[]{1,4,3,2}[rotation]:0;
                layout.add(new TrackPath.Wheel(new TrackPath.Point(x,y),.4,hint,i==1?3:0));
            }
            TrackPath.Path routed=TrackPath.build(layout);
            check(routed.wheelDirections().get(1)==-routed.wheelDirections().get(0),"Reverse wrapping, rotated case "+rotation);
            TrackPath.Point center=layout.get(1).center();
            TrackPath.Point offset=new TrackPath.Point(0,.4);
            for(int j=0;j<rotation;j++) offset=new TrackPath.Point(-offset.y(),offset.x());
            TrackPath.Point requested=center.add(offset);
            check(routed.points().stream().anyMatch(p->p.sub(requested).length()<.04),"Requested quadrant visited "+rotation);
        }
        var bottom=TrackPath.build(List.of(w(0,0,.4),new TrackPath.Wheel(new TrackPath.Point(2,1),.4,3,3),w(4,0,.4),w(4,4,.4),w(0,4,.4)));
        check(!bottom.crossing(),"Non-crossing route takes priority over a crossing side hint");
        check(bottom.wheelDirections().get(1)==-bottom.wheelDirections().get(0),"Alternate wrapping repairs the crossing shortest candidate");
        var lower=TrackPath.build(List.of(w(0,0,.4),new TrackPath.Wheel(new TrackPath.Point(2,-1),.4,3),w(4,0,.4),w(4,4,.4),w(0,4,.4)));
        check(!lower.crossing()&&lower.points().stream().anyMatch(p->p.sub(new TrackPath.Point(2,-1.4)).length()<.04),"Non-crossing belt under middle wheel");
        var crossed=TrackPath.build(List.of(w(0,0,.5),w(4,4,.5),w(0,4,.5),w(4,0,.5)));
        check(crossed.crossing(),"Unresolved bow-tie must be reported red, not silently reordered");
        check(!pair.crossing()&&!forward.crossing()&&!reverse.crossing(),"Normal arc/line junctions are not crossings");
        check(!TrackCrossings.closed(points(0,0,2,0,4,0,4,4,0,4)),"Collinear subdivisions sharing endpoints are valid");
        check(TrackCrossings.closed(points(0,0,4,0,2,0,2,4,0,4)),"Overlapping backtracking is a crossing");
        check(TrackCrossings.closed(points(0,0,4,0,4,4,2,0,0,4)),"Non-neighbour endpoint-on-segment is a crossing");
        check(TrackCrossings.closed(points(0,0,4,4,0,4,4,0)),"Proper crossing detected");
        var sequence=List.of(w(0,0,.4),new TrackPath.Wheel(new TrackPath.Point(2,1),.4,3),w(4,0,.4),w(4,4,.4),w(0,4,.4));
        for(int count=2;count<=sequence.size();count++) {
            var prefix=sequence.subList(0,count);var recomputed=TrackPath.build(prefix);
            check(recomputed.wheelDirections().size()==count,"Every selection replans all nodes "+count);
            check(recomputed.equals(TrackPath.build(prefix)),"Preview/server planning is deterministic "+count);
        }
        rejects(List.of(w(0,0,.5),w(TrackPath.MAX_SPAN+1,0,.5)),"Span budget enforced");
        rejects(List.of(w(0,0,Double.NaN),w(4,0,.5)),"NaN rejected");
        check(forward.points().size()<TrackPath.MAX_RENDER_POINTS,"Geometry bounded");
        var shifted=List.of(w(0,-.7,.5),w(4,-.7,.5));
        var deflected=TrackPath.deform(shifted,pair);
        check(Math.abs(deflected.length()-pair.length())<1e-8,"Uniform suspension travel preserves belt length");
        check(deflected.points().size()==pair.points().size(),"Translation preserves tessellation");
        for(int i=0;i<pair.points().size();i++) if(deflected.points().get(i).sub(pair.points().get(i)).sub(new TrackPath.Point(0,-.7)).length()>1e-8)
            throw new AssertionError("Wheel and belt centres move together");
        checks++;
        var committed=TrackPath.build(sequence);
        for(int step=0;step<=10;step++) {
            List<TrackPath.Wheel> moving=new ArrayList<>(sequence);
            moving.set(1,new TrackPath.Wheel(new TrackPath.Point(2,1-step*.12),.4,3));
            var deformed=TrackPath.deform(moving,committed);
            check(deformed.wheelDirections().equals(committed.wheelDirections()),"Travel preserves reverse wrap at step "+step);
            check(deformed.winding()==committed.winding()&&Double.isFinite(deformed.length()),"Finite deformed loop at step "+step);
        }
        check(TrackPath.withDirections(sequence,committed.wheelDirections()).points().equals(committed.points()),"Saved wrap topology restores exactly");
        try { TrackPath.withDirections(sequence,List.of(1));throw new AssertionError("Direction count mismatch"); } catch(IllegalArgumentException expected) { checks++; }
        try { TrackPath.withDirections(List.of(w(0,0,.5),w(4,0,.5)),List.of(0,1));throw new AssertionError("Invalid direction"); } catch(IllegalArgumentException expected) { checks++; }
        System.out.println("TrackPath: "+checks+" checks passed");
    }
    private static List<TrackPath.Point> points(double... coordinates) {
        List<TrackPath.Point> points=new ArrayList<>();
        for(int i=0;i<coordinates.length;i+=2) points.add(new TrackPath.Point(coordinates[i],coordinates[i+1]));
        return points;
    }
    private static void extendedLengths() {
        var pair=List.of(w(0,0,.5),w(64,0,.5));
        var longPair=TrackPath.build(pair);
        check(longPair.length()>128,"64-block span and belt longer than old total limit accepted");
        rejects(List.of(w(0,0,.5),w(64.01,0,.5)),"New adjacent span limit enforced");
        var nearLimit=new ArrayList<TrackPath.Wheel>();
        for(var p:points(0,0,63,0,63,63,0,63))nearLimit.add(new TrackPath.Wheel(p,.5,0,0,false,true));
        for(boolean reverse:new boolean[]{false,true}) {
            if(reverse)Collections.reverse(nearLimit);
            var path=TrackPath.build(nearLimit);
            check(path.length()>255&&path.length()<256&&!path.crossing(),"Near 256-block belt accepted in either direction");
            check(TrackPath.withDirections(nearLimit,path.wheelDirections()).points().equals(path.points()),"Long saved route restores");
            var spans=TrackPath.contactSpans(nearLimit,path.wheelDirections(),.45);
            double length=spans.stream().mapToDouble(s->s.end().sub(s.start()).length()).sum();
            check(spans.size()<=TrackPath.MAX_CONTACT_SPANS&&Math.abs(length-path.length())<.04,"Long physical path is complete and within segment budget");
        }
        var tooLong=new ArrayList<TrackPath.Wheel>();
        for(var p:points(0,0,64,0,64,64,0,64))tooLong.add(new TrackPath.Wheel(p,.5,0,0,false,true));
        rejects(tooLong,"Rounded perimeter above 256 rejected even though adjacent spans fit");
    }
    private static void roadWheelRows() {
        for(int count:new int[]{2,3,5,8}) for(boolean reverse:new boolean[]{false,true}) for(int hint:new int[]{0,3}) {
            List<TrackPath.Wheel> wheels=new ArrayList<>();
            wheels.add(w(-1,1,.64));
            for(int i=0;i<count;i++) wheels.add(new TrackPath.Wheel(new TrackPath.Point(i,0),.61771938,hint,3));
            wheels.add(w(count,1,.64));
            if(reverse) Collections.reverse(wheels);
            var path=TrackPath.build(wheels);
            check(!path.crossing(),"Raised guides and road row remain non-crossing");
            var spans=TrackPath.contactSpans(wheels,path.wheelDirections(),.45);
            check(spans.stream().filter(s->s.first()!=s.second()&&s.first()>0&&s.first()<wheels.size()-1&&s.second()>0&&s.second()<wheels.size()-1)
                .allMatch(s->s.start().y()<-.61&&s.end().y()<-.61),"Consecutive road wheels must carry the lower run, count="+count+", reverse="+reverse);
        }
        var manual=List.of(w(-1,1,.64),new TrackPath.Wheel(new TrackPath.Point(0,0),.61771938,1,3),
            new TrackPath.Wheel(new TrackPath.Point(1,0),.61771938,1,3),new TrackPath.Wheel(new TrackPath.Point(2,0),.61771938,1,3),w(3,1,.64));
        var manualPath=TrackPath.build(manual);
        check(!manualPath.crossing(),"Explicit upper wrap remains non-crossing");
        check(TrackPath.contactSpans(manual,manualPath.wheelDirections(),.45).stream().filter(s->s.first()==1&&s.second()==2)
            .allMatch(s->s.start().y()>.61&&s.end().y()>.61),"Explicit upper hint overrides road support preference");
        for(boolean reverse:new boolean[]{false,true}) {
            List<TrackPath.Wheel> row=new ArrayList<>();
            for(int i=0;i<5;i++) row.add(new TrackPath.Wheel(new TrackPath.Point(i,0),.61771938,0,3));
            if(reverse) Collections.reverse(row);
            var path=TrackPath.build(row);
            check(!path.crossing(),"Road-only loop is valid in both selection directions");
            check(TrackPath.contactSpans(row,path.wheelDirections(),.45).stream().filter(s->s.first()==1&&s.second()==2)
                .allMatch(s->s.start().y()<-.61&&s.end().y()<-.61),"Road-only middle wheel uses lower run");
        }
    }
}
