import dev.trackssimulate.physics.SuspensionContactSolver;
import java.util.*;

/** Calls the production coupled solver. No simulated claims about game performance. */
public class PassiveMotionChecks {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static void near(double actual,double expected,double tolerance,String text){check(Math.abs(actual-expected)<=tolerance,text+": "+actual+" != "+expected);}
    static SuspensionContactSolver.Row row(double mass,double x,double y,double z,double velocity) {
        return new SuspensionContactSolver.Row(x,y,z,0,0,0,1/mass,new double[]{1,0,0,0,1,0,0,0,1},velocity,0,0);
    }
    static SuspensionContactSolver.Contact contact(double mass,double velocity,double friction,SuspensionContactSolver.FreeBelt belt,int winding) {
        var f=row(mass,1,0,0,velocity);f.couple(belt,winding);
        return new SuspensionContactSolver.Contact(row(mass,0,1,0,-1),null,f,row(mass,0,0,1,0),mass*100,friction,1);
    }
    public static void main(String[] args) {
        for(double chassis:new double[]{1,100,10000})for(double rotor:new double[]{.01,1,64})for(int winding:new int[]{-1,1}) {
            var belt=new SuspensionContactSolver.FreeBelt(rotor,0);
            var c=contact(chassis,10,100,belt,winding);
            SuspensionContactSolver.solve(List.of(c));
            double body=10+c.forward().impulse/chassis,expected=10*chassis/(chassis+rotor);
            near(body,expected,1e-8,"body and finite rolling inertia momentum");
            near(belt.speed,winding*expected,1e-8,"winding changes material direction only");
            check(.5*chassis*body*body+.5*rotor*belt.speed*belt.speed<=.5*chassis*100+1e-6,"rolling cannot create energy");
        }
        for(int count:new int[]{1,2,8,32,64}) {
            var belt=new SuspensionContactSolver.FreeBelt(4,0);var contacts=new ArrayList<SuspensionContactSolver.Contact>();
            for(int i=0;i<count;i++)contacts.add(contact(100,10,100,belt,1));
            SuspensionContactSolver.solve(contacts);
            near(belt.speed,1000.0/104,1e-7,"shared inertia not multiplied by contact count");
            double sum=contacts.stream().mapToDouble(c->c.forward().impulse).sum();
            near(10+sum/100,belt.speed,1e-7,"all contacts see same body response");
        }
        var sliding=new SuspensionContactSolver.FreeBelt(4,0);var zero=contact(100,10,0,sliding,1);
        SuspensionContactSolver.solve(List.of(zero));near(sliding.speed,0,0,"zero friction cannot spin a resting belt");near(zero.forward().impulse,0,0,"zero friction cannot slow chassis");
        var coasting=new SuspensionContactSolver.FreeBelt(4,7);var slip=contact(100,-10,0,coasting,1);
        SuspensionContactSolver.solve(List.of(slip));near(coasting.speed,7,0,"zero friction preserves existing rolling motion");
        SuspensionContactSolver.solve(List.of());near(coasting.speed,7,0,"airborne no artificial rolling drag");
        var brake=new SuspensionContactSolver.FreeBelt(4,10);brake.brake(1);var braked=contact(100,10,100,brake,1);
        SuspensionContactSolver.solve(List.of(braked));check(Math.abs(brake.speed)<1e-6,"shared brake stops rolling");check(braked.forward().impulse<0,"brake slows chassis through contact");
        var forward=new SuspensionContactSolver.FreeBelt(4,3);var reverse=new SuspensionContactSolver.FreeBelt(4,-8);
        SuspensionContactSolver.solve(List.of(contact(100,0,0,forward,1),contact(100,0,0,reverse,1)));
        near(forward.speed,3,0,"separate loops keep independent state");near(reverse.speed,-8,0,"second loop independent");
        var lockedRow=new SuspensionContactSolver.Row(1,0,0,0,0,0,.01,new double[]{1,0,0,0,1,0,0,0,1},10,0,10);
        lockedRow.lockRolling();
        var locked=new SuspensionContactSolver.Contact(row(100,0,1,0,-1),null,lockedRow,row(100,0,0,1,0),10000,100,1);
        SuspensionContactSolver.solve(List.of(locked));near(10+lockedRow.impulse/100,0,1e-8,"full brake removes viscous softness for every member");
        System.out.println("Passive motion checks passed: "+checks);
    }
}
