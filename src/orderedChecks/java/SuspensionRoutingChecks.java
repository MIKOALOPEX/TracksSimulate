import dev.trackssimulate.physics.*;
import java.util.List;

/** Production ownership + solver replay: catches a hard contact bypassing a spring
 * before its narrow terrain ray has acquired contact. No Minecraft game is started. */
public class SuspensionRoutingChecks {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void near(double a,double b,double e,String message){check(Math.abs(a-b)<=e,message+": "+a+" != "+b);}
    static SuspensionContactSolver.Row row(double mass,double velocity,double target,double softness){
        return new SuspensionContactSolver.Row(0,1,0,0,0,0,1/mass,new double[]{1,0,0,0,1,0,0,0,1},velocity,target,softness);
    }
    public static void main(String[] args){
        for(double t:new double[]{0,.01,.25,.5,.9,1}) {
            near(SuspensionContactRouting.mobility(false,false,t,1),0,0,"fixed wheel/span stays rigid");
            near(SuspensionContactRouting.mobility(true,true,t,1),1,1e-12,"road wheel/span always uses spring, including at droop");
            near(SuspensionContactRouting.mobility(true,false,t,1),1-t,1e-12,"mixed span retains endpoint ownership");
            near(SuspensionContactRouting.mobility(false,true,t,1),t,1e-12,"reversed topology equivalent");
            for(double ny:new double[]{-1,0,.49})near(SuspensionContactRouting.mobility(true,true,t,ny),0,0,"overhead and side contacts remain rigid");
        }
        near(SuspensionContactRouting.rawExtension(.75,.1,1,1),.65,1e-12,"surface hit missed by narrow ray still compresses wheel");
        check(SuspensionContactRouting.rawExtension(.75,-.04,1,1)>.75,"separated surface cannot hold vehicle above ground");
        near(SuspensionContactRouting.rawExtension(.75,.1,1,.5),.55,1e-12,"fixed-road midpoint compression accounts for unmoving end");
        near(SuspensionContactRouting.rawExtension(.75,.1,.5,1),.55,1e-12,"inclined plane projected along travel");
        // Replay the contact ownership in a vertical spring drop. Narrow rays deliberately
        // absent; shell support alone must settle at mg/k compression, not at full droop.
        for(double mass:new double[]{2,10,40})for(int samples:new int[]{1,32,512}) {
            double k=1000,dt=.025,v=0,compression=0,c=2*Math.sqrt(k*mass),lastImpulse=0;
            var limits=new SuspensionLimits(.75,.65);
            for(int step=0;step<800;step++) {
                v-=10*dt;
                double raw=.75;
                for(int s=0;s<samples;s++)raw=Math.min(raw,SuspensionContactRouting.rawExtension(.75,compression,1,1));
                // Collection reduces all support samples to one most-restrictive plane per wheel.
                double damping=c+k*dt;
                var spring=row(mass,v,k*(.75-limits.clamp(raw))/damping,1/(dt*damping));
                var stop=row(mass,v,limits.stopTarget(raw,1,dt),0);
                var contact=new SuspensionContactSolver.Contact(spring,stop,row(mass,0,0,1),row(mass,0,0,1),mass*80*dt,0,0);
                SuspensionContactSolver.solve(List.of(contact));lastImpulse=contact.supportImpulse();
                v+=lastImpulse/mass;compression-=v*dt;
            }
            near(compression,mass*10/k,1e-7,"surface-only suspension settles under load without rigid bypass");
            near(v,0,1e-7,"settled vertical velocity");
            near(lastImpulse,mass*10*dt,1e-7,"sample count does not multiply support");
        }
        // The known bypass pins compression to zero even while gravity is applied.
        var rigid=row(10,-.25,0,0);
        SuspensionContactSolver.solve(List.of(new SuspensionContactSolver.Contact(rigid,null,row(10,0,0,1),row(10,0,0,1),100,0,0)));
        near(-.25+rigid.impulse/10,0,1e-12,"old rigid bypass reproduced");
        var limits=new SuspensionLimits(.75,.45);
        check(limits.stopTarget(.2,1,.025)>0,"compression stop still supplies recovery");
        check(limits.stopTarget(.6,1,.025)<0,"available travel must permit compression");
        System.out.println("Suspension ownership and load replay checks passed: "+checks+". Offline solver only, not game acceptance.");
    }
}
