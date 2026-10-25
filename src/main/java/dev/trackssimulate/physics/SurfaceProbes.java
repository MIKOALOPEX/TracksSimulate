package dev.trackssimulate.physics;

import dev.trackssimulate.trackpath.BeltSurface;
import dev.trackssimulate.trackpath.BeltSurface.V;
import java.util.*;

/** Deterministic contact coverage, independent of texture phase and wheel spin. */
public final class SurfaceProbes {
    public record Wheel(V center,double radius,double width,int winding) {}
    public record Probe(V point,V outward,V tangent,int from,int to,double blend,boolean wheel,double backtrack) {}
    public record Plan(List<Probe> probes,int requested) { public boolean reduced(){return probes.size()<requested;} }
    private static V radial(boolean axisX,double angle){return axisX?new V(0,Math.sin(angle),Math.cos(angle)):new V(Math.cos(angle),Math.sin(angle),0);}
    public static Plan plan(List<Wheel> wheels,BeltSurface surface,boolean axisX,int budget) {
        if(budget<1)throw new IllegalArgumentException("Invalid probe budget");
        V axle=axisX?new V(1,0,0):new V(0,0,1);
        var essential=new ArrayList<Probe>();var extra=new ArrayList<Probe>();
        for(int i=0;i<wheels.size();i++) {
            var w=wheels.get(i);
            for(int j=0;j<8;j++) {
                double angle=j*Math.PI/4;var n=radial(axisX,angle);var t=radial(axisX,angle+Math.PI/2).mul(w.winding);
                var p=w.center.add(n.mul(w.radius));var probe=new Probe(p,n,t,i,i,0,true,w.radius+.04);
                ((j&1)==0?essential:extra).add(probe);
                for(int side:new int[]{-1,1})extra.add(new Probe(p.add(axle.mul(side*w.width*.4)),n,t,i,i,0,true,w.radius+.04));
            }
            for(int side:new int[]{-1,1}) {
                var n=axle.mul(side);var p=w.center.add(n.mul(w.width/2));
                essential.add(new Probe(p,n,new V(0,0,0),i,i,0,true,w.width/2+.04));
                for(int j=0;j<4;j++)extra.add(new Probe(p.add(radial(axisX,j*Math.PI/2).mul(w.radius*.65)),n,
                    radial(axisX,j*Math.PI/2+Math.PI/2).mul(w.winding*.65),i,i,0,true,w.width/2+.04));
            }
        }
        var band=new ArrayList<Probe>();
        if(surface!=null&&surface.valid()) {
            double next=.25;
            for(var s:surface.spans()) {
                // Global distance grid prevents arc tessellation density from multiplying contacts.
                while(next<=s.end()+1e-9) {
                    double u=Math.max(0,Math.min(1,(next-s.start())/Math.max(1e-12,s.end()-s.start())));
                    var p=s.a().add(s.b().sub(s.a()).mul(u));var n=s.normalA().add(s.normalB().sub(s.normalA()).mul(u)).unit();
                    var t=s.b().sub(s.a()).unit();double width=BeltSurface.lerp(s.widthA(),s.widthB(),u);
                    int from=s.key().from(),to=s.key().to();double blend=BeltSurface.lerp(s.t0(),s.t1(),u);
                    for(int side:new int[]{-1,1}) {
                        band.add(new Probe(p.add(n.mul(BeltSurface.HALF_THICKNESS)).add(axle.mul(side*width*.4)),n,t,from,to,blend,false,.4));
                        band.add(new Probe(p.add(axle.mul(side*width/2)),axle.mul(side),t,from,to,blend,false,.4));
                    }
                    band.add(new Probe(p.sub(n.mul(BeltSurface.HALF_THICKNESS)),n.mul(-1),t,from,to,blend,false,.4));
                    next+=.5;
                }
            }
        }
        int requested=essential.size()+band.size()+extra.size();
        var out=new ArrayList<Probe>();
        take(essential,out,Math.min(essential.size(),budget/2));
        take(band,out,Math.min(band.size(),budget-out.size()));
        take(extra,out,Math.min(extra.size(),budget-out.size()));
        return new Plan(List.copyOf(out),requested);
    }
    private static void take(List<Probe> source,List<Probe> out,int count) {
        for(int i=0;i<count;i++)out.add(source.get((int)((long)i*source.size()/count)));
    }
    public static double normalTarget(double penetration,double dt) {
        if(!(dt>0)||!Double.isFinite(penetration))throw new IllegalArgumentException("Invalid contact separation");
        return penetration>0?Math.min(1,Math.max(0,penetration-.005)*.15/dt):penetration/dt;
    }
    private SurfaceProbes() {}
}
