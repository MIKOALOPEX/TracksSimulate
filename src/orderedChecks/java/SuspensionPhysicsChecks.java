import dev.trackssimulate.physics.SuspensionContactSolver;
import dev.trackssimulate.physics.SuspensionLimits;
import java.util.*;

/** Executes the production impulse solver. No mocked game, terrain or Sable. */
public final class SuspensionPhysicsChecks {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void close(double actual,double expected,double tolerance,String label){check(Math.abs(actual-expected)<=tolerance,label+": "+actual+" != "+expected);}
    private static SuspensionContactSolver.Row row(int axis,double mass,double velocity,double target,double softness) {
        return new SuspensionContactSolver.Row(axis==0?1:0,axis==1?1:0,axis==2?1:0,0,0,0,1/mass,
            new double[]{1/mass,0,0,0,1/mass,0,0,0,1/mass},velocity,target,softness);
    }
    private static SuspensionContactSolver.Contact contact(double mass,double normalV,double stopTarget,double cap,double forwardMu,double sideMu) {
        return new SuspensionContactSolver.Contact(row(1,mass,normalV,0,1e6),row(1,mass,normalV,stopTarget,0),
            row(0,mass,4,0,.001),row(2,mass,-3,0,.001),cap,forwardMu,sideMu);
    }
    public static void main(String[] args) {
        var limits=new SuspensionLimits(.75,.45);
        close(limits.clamp(-1),.3,1e-12,"compression stroke is measured from full droop");
        close(limits.clamp(2),.75,0,"wheel droop limit");
        check(limits.reachable(.75)&&!limits.reachable(1),"contact at extension boundary but never beyond it");
        check(limits.reachable(-.6),"overcompressed contact remains detectable for recovery");
        close(limits.penetration(.2),.1,1e-12,"stop measures actual overlap independently of rendered clamp");
        close(limits.stopTarget(.3,1,.05),0,1e-12,"at stop no restitution");
        close(limits.stopTarget(.2,1,.05),.4,1e-12,"bounded overlap correction");
        close(limits.stopTarget(-10,1,.01),2,1e-12,"deep overlap never asks for explosive recovery speed");
        close(limits.stopTarget(.6,1,.05),-6,1e-12,"unused travel permits inward motion");
        var example=new SuspensionLimits(.8,1,.5);
        close(example.minimumDrop(),.3,1e-12,"offset plus extension minus compression");
        close(example.maximumDrop(),1.3,1e-12,"offset plus extension");
        var boundary=new SuspensionLimits(.8,1.3,.5);
        close(boundary.minimumDrop(),0,1e-12,"compression may exceed extension up to total drop");
        check(new SuspensionLimits(.8,0,.5).minimumExtension()==.8,"zero stroke is a fixed wheel centre");
        boolean rejected=false;try{new SuspensionLimits(.8,1.31,.5);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"compression cannot exceed offset plus extension");
        var migrated=SuspensionLimits.restore(.75,.45,.75,true);
        close(migrated.minimumExtension(),-.45,1e-12,"legacy endpoint preserved when legal");
        close(SuspensionLimits.restore(.75,.45,0,true).minimumExtension(),0,1e-12,"illegal legacy endpoint tightened to new boundary");
        close(SuspensionLimits.restore(.6,.45,-.75,true).minimumDrop(),0,1e-12,"upward legacy offset repaired deterministically");
        for(double mass:new double[]{1,100,10000})for(double dt:new double[]{1./120,1./60,.05}) {
            var c=contact(mass,-3,limits.stopTarget(.3,1,dt),mass*10,0,0);
            SuspensionContactSolver.solve(List.of(c));
            close(-3+c.supportImpulse()/mass,0,1e-8,"compression stop removes inward speed across masses");
            close(c.forward().impulse,0,0,"zero longitudinal friction");
            close(c.side().impulse,0,0,"zero side friction");
            var free=contact(mass,-1,limits.stopTarget(.6,1,dt),mass*10,0,0);
            SuspensionContactSolver.solve(List.of(free));
            close(free.stop().impulse,0,0,"stop does not lock suspension during ordinary approach");
            var capped=contact(mass,-10,0,mass,1,1);
            SuspensionContactSolver.solve(List.of(capped));
            close(-10+capped.supportImpulse()/mass,0,1e-7,"stop still supports after spring budget is exhausted");
            check(capped.normal().impulse<=mass&&capped.stop().impulse>mass,"stop is not capped by spring strength");
        }
        // Test independently adjustable friction under known support, including
        // extreme anisotropy and zero axes. No fake assertions about driving FPS.
        for(double longitudinal:new double[]{0,.01,.5,1,4})for(double lateral:new double[]{0,.01,.5,1,4}) {
            var c=contact(100,-3,0,1000,longitudinal,lateral);
            SuspensionContactSolver.solve(List.of(c));
            double f=c.forward().impulse,s=c.side().impulse,n=c.supportImpulse();
            check(Double.isFinite(f)&&Double.isFinite(s)&&Double.isFinite(n),"finite anisotropic solve");
            check(f<=0&&s>=0,"friction opposes independent slip axes");
            if(longitudinal==0)close(f,0,0,"free rolling axis has no hidden longitudinal drag");
            if(lateral==0)close(s,0,0,"free side axis has no hidden side drag");
            double ellipse=(longitudinal>0?Math.pow(f/(longitudinal*n),2):0)+(lateral>0?Math.pow(s/(lateral*n),2):0);
            check(ellipse<=1+1e-10,"combined friction remains within ellipse");
        }
        // Off-centre stops must couple through chassis angular response rather
        // than multiplying an isolated effective-mass force once per wheel.
        var contacts=new ArrayList<SuspensionContactSolver.Contact>();
        for(int side:new int[]{-1,1}) {
            var normal=new SuspensionContactSolver.Row(0,1,0,side*2,0,0,.01,new double[]{.005,0,0,0,.005,0,0,0,.005},-2,0,1e6);
            var stop=new SuspensionContactSolver.Row(0,1,0,side*2,0,0,.01,new double[]{.005,0,0,0,.005,0,0,0,.005},-2,0,0);
            contacts.add(new SuspensionContactSolver.Contact(normal,stop,row(0,100,0,0,1),row(2,100,0,0,1),1000,0,0));
        }
        var result=SuspensionContactSolver.solve(contacts);
        close(contacts.get(0).supportImpulse()+contacts.get(1).supportImpulse(),200,1e-4,"two stops share vehicle load");
        close(result.maxDeltaAngularSpeed(),0,1e-5,"symmetric stops do not generate net roll");
        check(result.iterations()<=SuspensionContactSolver.MAX_ITERATIONS,"bounded iteration budget");
        double previousBrake=0;
        for(double mu:new double[]{0,.1,.5,1,2}) {
            var c=new SuspensionContactSolver.Contact(row(1,100,-1,0,1e6),row(1,100,-1,0,0),
                row(0,100,10,0,0),row(2,100,0,0,0),1,mu,0);
            SuspensionContactSolver.solve(List.of(c));
            double brake=-c.forward().impulse;
            check(brake>=previousBrake,"larger longitudinal friction yields stronger available braking");
            close(brake,mu*100,1e-5,"braking magnitude follows longitudinal friction budget");
            previousBrake=brake;
            if(mu==0)close(10+c.forward().impulse/100,10,0,"zero longitudinal friction preserves motion under full braking");
        }
        System.out.println("Suspension physics checks passed: "+checks+". Production math only; terrain, Sable integration and gameplay require user testing.");
    }
}
