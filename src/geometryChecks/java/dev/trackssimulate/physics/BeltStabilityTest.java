package dev.trackssimulate.physics;

import dev.trackssimulate.physics.contact.*;
import java.util.*;

/** Free rotation is essential here: locked planar fixtures cannot expose contact torque instability. */
public final class BeltStabilityTest {
    private static int checks;
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static final class Body implements LoadRouting.RigidBody {
        final double mass;final V3 inertia;V3 position=V3.ZERO,linear=V3.ZERO,angular=V3.ZERO;
        Body(double mass,V3 half) {this.mass=mass;inertia=new V3(mass*(half.y()*half.y()+half.z()*half.z())/3,mass*(half.x()*half.x()+half.z()*half.z())/3,mass*(half.x()*half.x()+half.y()*half.y())/3);}
        V3 inverse(V3 v) {return new V3(v.x()/inertia.x(),v.y()/inertia.y(),v.z()/inertia.z());}
        double energy() {return .5*(mass*linear.dot(linear)+inertia.x()*angular.x()*angular.x()+inertia.y()*angular.y()*angular.y()+inertia.z()*angular.z()*angular.z());}
        public V3 center(){return position;}
        public double inverseMass(){return 1/mass;}
        public V3 velocity(V3 p){return linear.add(angular.cross(p.sub(position)));}
        public double inverseMass(V3 p,V3 n){return crossResponse(p,n,p,n);}
        public double crossResponse(V3 p,V3 n,V3 q,V3 m){return n.mul(1/mass).add(inverse(p.sub(position).cross(n)).cross(q.sub(position))).dot(m);}
        public void impulse(V3 p,V3 j){linear=linear.add(j.mul(1/mass));angular=angular.add(inverse(p.sub(position).cross(j)));}
    }
    public static void main(String[] args) throws Exception {
        Random random=new Random(73463);double worst=0;int example=-1;
        for(int trial=0;trial<2000;trial++) {
            Body body=new Body(trial%2==0?1000:100000,new V3(4,.25,1.5));
            body.linear=new V3(random.nextDouble()*8-4,random.nextDouble()*4-2,random.nextDouble()*8-4);
            body.angular=new V3(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5);
            var belt=new ContactSolver.Belt(random.nextDouble()*8-4,200);
            double energy=body.energy()+.5*belt.mass*belt.speed*belt.speed;
            List<ContactSolver.Contact> contacts=new ArrayList<>();
            for(int i=0;i<12;i++) {
                V3 p=new V3(random.nextDouble()*8-4,-.8,random.nextBoolean()?1.5:-1.5);
                V3 n=trial%3==0?V3.Y:trial%3==1?V3.Z:V3.X;
                contacts.add(new ContactSolver.Contact(body,null,p,n,V3.X,0));
            }
            ContactSolver.solve(contacts,belt,trial%2==0?1.5:10,1.0/120);
            double ratio=(body.energy()+.5*belt.mass*belt.speed*belt.speed)/energy;
            if(ratio>worst){worst=ratio;example=trial;}
        }
        System.out.println("Worst passive collision energy ratio="+worst+", trial="+example);
        check(worst<=1.00001,"Passive contact must not create kinetic energy: "+worst);
        multipleBelts();
        contradictoryContacts();
        contactSettings();
        lateralShear();
        // These are algebraic tests, not a simulated vehicle or evidence of game stability.
        for(double mass:new double[]{1000,10000,100000}) {
            Body body=new Body(mass,new V3(4,.25,1.5));
            for(double speed:new double[]{-1000,1000}) {
                var spring=new BeltSuspension(body,V3.Y,SuspensionSettings.DEFAULT,.5,speed,1.0/120);
                check(spring.extension()>=0&&spring.extension()<=1,"Bounded suspension coordinate with extreme initial velocity");
                check(body.linear.length()==0&&body.angular.length()==0,"Internal airborne suspension must not push the held chassis");
            }
        }
        for(boolean sideways:new boolean[]{false,true}) {
            double c=Math.sqrt(.5);Body body=new Body(10000,new V3(4,.25,1.5));body.linear=new V3(0,-9.81/120,0);
            V3 normal=sideways?new V3(0,c,c):new V3(c,c,0);
            V3 tangent=sideways?V3.X:new V3(-c,c,0);
            var lockedBelt=new ContactSolver.Belt(0,1e18);
            var contact=new ContactSolver.Contact(body,null,V3.ZERO,normal,tangent,0);
            ContactSolver.solve(List.of(contact),lockedBelt,1.2,1.8,1.0/120);
            check(body.linear.length()<1e-8,"Known 45-degree planar contact friction balance, lateral="+sideways);
        }
        var folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"),"physics-log-check-");
        var path=folder.resolve("physics.log");var log=new DiagnosticLog(path,64,3);
        for(int i=0;i<16;i++)log.append("sample="+i+" values=1234567890");
        check(java.nio.file.Files.readString(path).contains("sample=15"),"Diagnostic log preserves latest sample");
        for(int i=0;i<=3;i++)check(java.nio.file.Files.size(i==0?path:folder.resolve("physics.log."+i))<=64,"Diagnostic log rotation bounds file size");
        check(java.nio.file.Files.readString(folder.resolve("physics.log.1")).contains("sample=13"),"Diagnostic archive preserves previous samples");
        System.out.println("Contact algebra and diagnostic log: "+checks+" checks passed (not a vehicle/game acceptance test)");
    }
    private static void multipleBelts() {
        Random random=new Random(560403);double worst=0;
        for(double mass:new double[]{36,35036})for(int count:new int[]{2,3,4,8})for(int orientation=0;orientation<3;orientation++)for(int trial=0;trial<12;trial++) {
            Body body=new Body(mass,new V3(4,.3,1.5));
            Body other=new Body(mass*5,new V3(2,.4,1));
            body.linear=new V3(random.nextDouble()*4-2,-random.nextDouble(),random.nextDouble()*4-2);
            body.angular=new V3(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5);
            other.linear=new V3(.1,-.2,.3);other.angular=new V3(.1,.1,-.1);
            List<ContactSolver.Group> groups=new ArrayList<>();double before=body.energy()+other.energy();
            for(int loop=0;loop<count;loop++) {
                var belt=new ContactSolver.Belt((loop%2==0?8:-8),BeltSettings.DEFAULT.equivalentMass(mass,count));
                before+=.5*belt.mass*belt.speed*belt.speed;
                List<ContactSolver.Contact> contacts=new ArrayList<>();
                for(int point=0;point<8;point++) {
                    V3 p=new V3(point-3.5,-.8,-1.5+3.0*loop/(count-1));
                    // Upright opposing belts; horizontal loops; simultaneous ground and side contacts.
                    V3 normal=orientation==2&&point%2==0?V3.Z:V3.Y;
                    V3 tangent=orientation==1?new V3(Math.cos(point),0,Math.sin(point)):V3.X;
                    var contact=new ContactSolver.Contact(body,trial%2==0?null:other,p,normal,tangent,0);
                    contact.gripScale=orientation==0?1:.5;contacts.add(contact);
                }
                groups.add(new ContactSolver.Group(contacts,belt,10,10,.1,0,trial%3==0?0:.15));
            }
            ContactSolver.solveGroups(groups,1.0/120);
            double after=body.energy()+other.energy();
            for(var group:groups)after+=.5*group.belt().mass*group.belt().speed*group.belt().speed;
            double ratio=after/before;worst=Math.max(worst,ratio);
            check(Double.isFinite(ratio)&&ratio<=1.00001,"Multi-belt passive energy, mass="+mass+" loops="+count+" orientation="+orientation+" ratio="+ratio);
        }
        System.out.println("Multi-belt free-rotation contact energy ratio <= "+worst+" (no motor or positional recovery; algebra only)");
    }
    private static void contradictoryContacts() {
        // An impossible fixture: opposed faces demand separation at the same point.
        // Softness must bound each row instead of accumulating impulse with solver iterations.
        for(double mass:new double[]{36,35036}) {
            Body body=new Body(mass,new V3(4,.3,1.5));
            var a=new ContactSolver.Contact(body,null,V3.ZERO,V3.Y,V3.X,.2);
            var b=new ContactSolver.Contact(body,null,V3.ZERO,V3.Y.mul(-1),V3.X,.2);
            var belt=new ContactSolver.Belt(0,mass*.05);
            ContactSolver.solveGroups(List.of(new ContactSolver.Group(List.of(a,b),belt,10,10,.1,.12)),1.0/120);
            check(Double.isFinite(a.normalImpulse+b.normalImpulse),"Conflicting contact impulses finite");
            check(Math.max(a.normalImpulse,b.normalImpulse)<=mass*.12/.1+1e-7,"Compliant opposed-contact analytic impulse bound");
            check(body.linear.length()<=.12+1e-8,"Opposed contact recovery stays within the row recovery speed");
        }
    }
    private static void contactSettings() {
        var settings=BeltSettings.DEFAULT;
        for(int count:new int[]{1,2,3,4,8}) {
            check(Math.abs(count*settings.equivalentMass(36,count)-36*.05)<1e-9,"Extra loops do not multiply total belt inertia");
            check(Math.abs(count*settings.effectiveDriveForce(36,count)-36*settings.maxAcceleration())<1e-9,"Extra loops share the vehicle drive budget");
        }
        check(settings.effectiveDriveForce(100000,1)==settings.driveForce(),"Absolute force limit also remains active");
        // A nearby cube outside the visible belt must become a real contact when its envelope expands.
        double[] values=settings.values();values[8]=.2;values[9]=.15;
        var expanded=BeltSettings.from(values);
        for(V3 axle:new V3[]{V3.X,V3.Y,V3.Z}) {
            V3 tangent=axle==V3.X?V3.Z:V3.X,normal=tangent.cross(axle).unit();
            var ordinary=settings.contactBox(tangent.mul(-.2),tangent.mul(.2),axle,.4);
            var wide=expanded.contactBox(tangent.mul(-.2),tangent.mul(.2),axle,.4);
            var radialObstacle=new ContactBox(normal.mul(.2),V3.X,V3.Y,V3.Z,new V3(.02,.02,.02));
            var sideObstacle=new ContactBox(axle.mul(.5),V3.X,V3.Y,V3.Z,new V3(.02,.02,.02));
            check(ordinary.surfaceContact(radialObstacle,0)==null&&wide.surfaceContact(radialObstacle,0)!=null,"Radial envelope reaches obstacle for axle "+axle);
            check(ordinary.surfaceContact(sideObstacle,0)==null&&wide.surfaceContact(sideObstacle,0)!=null,"Side envelope reaches obstacle for axle "+axle);
        }
        check(BeltSettings.from(values).equals(expanded),"Extended GUI/network settings round trip");
        for(int index:new int[]{8,9,10,11,12,13,14,15,16,17}) {
            double[] invalid=settings.values();invalid[index]=Double.NaN;boolean rejected=false;
            try {BeltSettings.from(invalid);}catch(IllegalArgumentException ex){rejected=true;}
            check(rejected,"Non-finite contact settings rejected at server boundary, field="+index);
        }
    }
    private static void lateralShear() {
        // At COM, mass=1, Jn=1, mu=2, v_transition=.5 gives gamma=.25.
        // The implicit shear row has Js=-1/(1+.25)=-.8 and leaves v_side=.2.
        Body body=new Body(1,new V3(1,1,1));body.linear=new V3(0,-1,1);
        var contact=new ContactSolver.Contact(body,null,V3.ZERO,V3.Y,V3.X,0);
        ContactSolver.solveGroups(List.of(new ContactSolver.Group(List.of(contact),new ContactSolver.Belt(0,.05),1,2,0,0,.5)),.01);
        check(Math.abs(body.linear.z()-.2)<1e-8,"Implicit lateral shear agrees with analytic solution");
        check(body.energy()<1,"Shear regularization dissipates energy");
        for(double mass:new double[]{36,35036}) {
            Body chassis=new Body(mass,new V3(4,.3,1.5));chassis.linear=new V3(0,-9.81/120,0);
            var settings=BeltSettings.DEFAULT;List<ContactSolver.Group> groups=new ArrayList<>();
            for(int side:new int[]{-1,1}) {
                var belt=new ContactSolver.Belt(0,settings.equivalentMass(mass,2));
                belt.motor(side*8,settings.effectiveDriveForce(mass,2),1.0/120);
                List<ContactSolver.Contact> contacts=new ArrayList<>();
                for(int i=0;i<8;i++)contacts.add(new ContactSolver.Contact(chassis,null,new V3(i-3.5,-.5,side*1.5),V3.Y,V3.X,0));
                groups.add(new ContactSolver.Group(contacts,belt,settings.friction(),settings.lateralFriction(),settings.contactSoftness(),0,settings.lateralSlipSpeed()));
            }
            ContactSolver.solveGroups(groups,1.0/120);
            check(Math.abs(chassis.angular.y())>1e-5,"Opposed drive produces a yaw response at default lateral friction for mass="+mass);
            check(chassis.linear.y()<.02,"Planar opposed-drive fixture does not gain a vertical kick for mass="+mass);
        }
    }
}
