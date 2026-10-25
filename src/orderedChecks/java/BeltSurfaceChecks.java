import dev.trackssimulate.trackpath.*;
import java.util.*;

public class BeltSurfaceChecks {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static void near(double a,double b,double eps,String text){check(Math.abs(a-b)<=eps,text+": "+a+" != "+b);}
    static BeltSurface.V v(double x,double y,double z){return new BeltSurface.V(x,y,z);}
    public static void main(String[] args) {
        var wheels=List.of(new OrderedBeltPath.Wheel(new OrderedBeltPath.P(-2,0),1),new OrderedBeltPath.Wheel(new OrderedBeltPath.P(2,0),1));
        var path=OrderedBeltPath.plan(wheels);check(path.valid(),"fixture loop valid");
        for(boolean axisX:new boolean[]{false,true})for(double width:new double[]{.25,1,3}) {
            var mesh=BeltSurface.build(path,List.of(0.,0.),List.of(width,width),axisX);
            near(mesh.length(),8+2*Math.PI,1e-9,"arc length includes complete wheel ends");
            var hit=mesh.intersect(v(0,-3,0),v(0,0,0),10000);
            check(hit.complete()&&hit.hit()!=null,"bottom shell hit");
            near(hit.hit().position().y(),-1.035,1e-6,"same visible thickness queried");
            check(hit.hit().distanceAlong()>=0&&hit.hit().distanceAlong()<=mesh.length(),"material coordinate inside loop");
            var side=mesh.intersect(axisX?v(-3,-1,0):v(0,-1,-3),axisX?v(3,-1,0):v(0,-1,3),10000);
            check(side.complete()&&side.hit()!=null,"side wall exists");
            near(axisX?side.hit().position().x():side.hit().position().z(),-width/2,1e-6,"width respected");
            check(mesh.intersect(v(0,-3,0),v(0,-2,0),10000).hit()==null,"finite segment does not extend to belt");
            check(!mesh.intersect(v(0,-3,0),v(0,0,0),0).complete(),"budget exhaustion never becomes trusted miss");
            var invalid=BeltSurface.build(new OrderedBeltPath.Result(path.segments(),path.sides(),path.length(),true,0,"crossed"),List.of(0.,0.),List.of(width,width),axisX);
            check(!invalid.intersect(v(0,-3,0),v(0,0,0),10000).complete(),"invalid geometry not authoritative");
            for(var span:mesh.spans())for(var face:mesh.faces(span)) {
                // Shoot through the centroid of a real mesh face from both sides.
                var center=face[0].add(face[1]).add(face[2]).add(face[3]).mul(.25);
                var n=face[1].sub(face[0]).cross(face[2].sub(face[0])).unit();
                var ray=mesh.intersect(center.add(n.mul(.01)),center.sub(n.mul(.01)),10000);
                check(ray.hit()!=null,"rendered face queryable from either winding");
            }
        }
        var tilted=BeltSurface.build(path,List.of(-1.,1.),List.of(.5,1.5),false);
        check(tilted.length()>path.length(),"axial offset contributes actual 3D run length");
        var oldKeys=tilted.spans().stream().map(BeltSurface.Span::key).distinct().toList();
        var moved=OrderedBeltPath.resolve(List.of(wheels.get(0),new OrderedBeltPath.Wheel(new OrderedBeltPath.P(2,.25),1)),path.sides());
        var updated=BeltSurface.build(moved,List.of(-1.,1.),List.of(.5,1.5),false);
        check(oldKeys.equals(updated.spans().stream().map(BeltSurface.Span::key).distinct().toList()),"segment ownership survives pose change");
        System.out.println("Belt surface checks passed: "+checks);
    }
}
