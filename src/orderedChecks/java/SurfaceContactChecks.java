import dev.trackssimulate.physics.*;
import dev.trackssimulate.trackpath.*;
import dev.trackssimulate.trackpath.BeltSurface.V;
import java.util.*;

/** Offline checks of production sampling and coupled impulses, not game acceptance. */
public class SurfaceContactChecks {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static void near(double a,double b,double eps,String text){check(Math.abs(a-b)<=eps,text+": "+a+" != "+b);}
    static SuspensionContactSolver.Row row(SuspensionContactSolver.Body b,double mass,V n,V arm,double speed,double target) {
        return new SuspensionContactSolver.Row(n.x(),n.y(),n.z(),arm.x(),arm.y(),arm.z(),1/mass,
            new double[]{1/mass,0,0,0,1/mass,0,0,0,1/mass},speed,target,0).bind(b);
    }
    static final V ZERO=new V(0,0,0),UP=new V(0,1,0),X=new V(1,0,0),Z=new V(0,0,1);
    static SuspensionContactSolver.Contact impact(SuspensionContactSolver.Body a,SuspensionContactSolver.Body b,double ma,double mb,int axis,double speed) {
        V n=axis==0?X:axis==1?UP:Z, t=axis==0?UP:X,side=n.cross(t);
        var normal=row(a,ma,n,ZERO,speed,0);normal.react(row(b,mb,n.mul(-1),ZERO,0,0));
        var f=row(a,ma,t,ZERO,0,0);f.react(row(b,mb,t.mul(-1),ZERO,0,0));
        var s=row(a,ma,side,ZERO,0,0);s.react(row(b,mb,side.mul(-1),ZERO,0,0));
        return new SuspensionContactSolver.Contact(normal,null,f,s,1e12,0,0);
    }
    public static void main(String[] args) {
        for(int axis=0;axis<3;axis++)for(double ma:new double[]{1,100,10000})for(double mb:new double[]{1,100,10000}) {
            var a=new SuspensionContactSolver.Body();var b=new SuspensionContactSolver.Body();
            var c=impact(a,b,ma,mb,axis,-4);SuspensionContactSolver.solve(List.of(c));
            double expected=4/(1/ma+1/mb);
            near(c.normal().impulse,expected,1e-7,"two-body reduced mass");
            near(ma*a.delta[axis]+mb*b.delta[axis],0,1e-7,"equal opposite momentum");
            near(-4+a.delta[axis],b.delta[axis],1e-8,"zero restitution relative velocity");
            check(.5*ma*Math.pow(-4+a.delta[axis],2)+.5*mb*Math.pow(b.delta[axis],2)<=8*ma+1e-6,"passive impact cannot create energy");
        }
        // Different vehicles in one batch cannot leak velocity through the old single-body accumulator.
        var a=new SuspensionContactSolver.Body();var b=new SuspensionContactSolver.Body();
        var c=new SuspensionContactSolver.Body();var d=new SuspensionContactSolver.Body();
        var ab=impact(a,b,10,10,0,-2);var cd=impact(c,d,10,10,0,-8);
        SuspensionContactSolver.solve(List.of(ab,cd));near(ab.normal().impulse,10,1e-8,"independent island A");near(cd.normal().impulse,40,1e-8,"independent island B");
        // A three-body touching chain shares the middle body's response across BOTH pairs.
        a=new SuspensionContactSolver.Body();b=new SuspensionContactSolver.Body();c=new SuspensionContactSolver.Body();
        ab=impact(a,b,10,10,0,-3);var bc=impact(b,c,10,10,0,0);
        SuspensionContactSolver.solve(List.of(ab,bc));
        near(-3+a.delta[0],b.delta[0],1e-6,"chain relative A/B");near(b.delta[0],c.delta[0],1e-6,"chain relative B/C");
        near(a.delta[0]+b.delta[0]+c.delta[0],0,1e-7,"chain momentum");
        // Duplicated coplanar samples must not multiply support.
        for(int count:new int[]{1,4,64}) {
            a=new SuspensionContactSolver.Body();b=new SuspensionContactSolver.Body();var list=new ArrayList<SuspensionContactSolver.Contact>();
            for(int i=0;i<count;i++)list.add(impact(a,b,1000,300,1,-1));
            SuspensionContactSolver.solve(list);near(list.stream().mapToDouble(x->x.normal().impulse).sum(),1/(1.0/1000+1.0/300),1e-7,"sample count independent support");
        }
        near(SurfaceProbes.normalTarget(-.1,.05),-2,1e-10,"separated surface permits approach");
        near(SurfaceProbes.normalTarget(.001,.05),0,0,"resting slop adds no bounce");
        near(SurfaceProbes.normalTarget(10,.001),1,0,"penetration recovery bounded");
        for(boolean axisX:new boolean[]{false,true})for(double radius:new double[]{.125,.5,3})for(int winding:new int[]{-1,1}) {
            V center=new V(9,3,-7);var wheel=new SurfaceProbes.Wheel(center,radius,1,winding);
            var plan=SurfaceProbes.plan(List.of(wheel),null,axisX,512);
            for(V dir:List.of(X,X.mul(-1),UP,UP.mul(-1),Z,Z.mul(-1)))
                check(plan.probes().stream().anyMatch(p->p.outward().dot(dir)>.999),"wheel has all six directions");
            for(var p:plan.probes()) {
                near(p.outward().length(),1,1e-9,"unit cast direction");
                var offset=p.point().sub(center);double axial=axisX?offset.x():offset.z();
                double radial=axisX?Math.hypot(offset.y(),offset.z()):Math.hypot(offset.x(),offset.y());
                check(radial<=radius+1e-9&&Math.abs(axial)<=.5+1e-9,"probe follows configured centered cylinder");
                if(Math.abs(axial)>.499)near(p.tangent().length(),radial/radius,1e-8,"side-face velocity follows distance from axle");
                else near(p.outward().dot(p.tangent()),0,1e-8,"radial surface speed is tangent");
            }
            var bounded=SurfaceProbes.plan(List.of(wheel),null,axisX,8);
            check(bounded.probes().size()<=8&&bounded.reduced(),"probe budget is strict and reported");
            check(bounded.equals(SurfaceProbes.plan(List.of(wheel),null,axisX,8)),"stable spatial sampling independent of spin");
        }
        var nodes=List.of(new OrderedBeltPath.Wheel(new OrderedBeltPath.P(-4,0),1),new OrderedBeltPath.Wheel(new OrderedBeltPath.P(4,0),1));
        var path=OrderedBeltPath.plan(nodes);
        for(boolean axisX:new boolean[]{false,true}) {
            var shell=BeltSurface.build(path,List.of(0.,0.),List.of(1.,1.),axisX);
            var wheels=List.of(new SurfaceProbes.Wheel(axisX?new V(0,0,-4):new V(-4,0,0),1,1,1),
                new SurfaceProbes.Wheel(axisX?new V(0,0,4):new V(4,0,0),1,1,1));
            var plan=SurfaceProbes.plan(wheels,shell,axisX,4096);
            // Central span is well outside either wheel footprint: bridging must come from the BELT.
            for(V direction:List.of(UP,UP.mul(-1),axisX?X:Z,axisX?X.mul(-1):Z.mul(-1)))
                check(plan.probes().stream().anyMatch(p->!p.wheel()&&Math.abs(axisX?p.point().z():p.point().x())<1&&p.outward().dot(direction)>.99),"bridging span supports all faces");
            for(var probe:plan.probes())if(!probe.wheel()) {
                // Every proposed band point must actually lie on the shared rendered shell.
                var query=shell.intersect(probe.point().add(probe.outward().mul(.01)),probe.point().sub(probe.outward().mul(.01)),10000);
                check(query.complete()&&query.hit()!=null,"band contact belongs to visible geometry");
                near(probe.tangent().length(),1,1e-8,"band travels at shared linear speed");
            }
        }
        System.out.println("Surface contact checks passed: "+checks);
    }
}
