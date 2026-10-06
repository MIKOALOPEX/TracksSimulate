package dev.trackssimulate.physics;

import dev.trackssimulate.physics.contact.*;
import dev.trackssimulate.track.TrackPath;
import java.util.*;

public final class ContactPhysicsTest {
    private static int checks;
    private static void check(boolean pass,String label) { if(!pass)throw new AssertionError(label);checks++; }
    private static void near(double a,double b,double tolerance,String label) { check(Math.abs(a-b)<tolerance,label+": "+a+" expected "+b); }
    private static final class Body implements ContactSolver.Body {
        V3 velocity;final double mass;
        Body(V3 velocity,double mass) { this.velocity=velocity;this.mass=mass; }
        public V3 velocity(V3 p) { return velocity; }
        public double inverseMass(V3 p,V3 n) { return 1/mass; }
        public void impulse(V3 p,V3 j) { velocity=velocity.add(j.mul(1/mass)); }
    }
    private static ContactBox box(V3 c,V3 h) { return new ContactBox(c,V3.X,V3.Y,V3.Z,h); }
    public static void main(String[] args) {
        driveMultiplier();
        ContactBox ground=box(new V3(0,-.5,0),new V3(10,.5,10));
        var strip=box(new V3(0,.02,0),new V3(.2,.04,.4));var hit=strip.contact(ground,0);
        check(hit!=null&&hit.normal().y()>.999,"bottom face contact");near(hit.penetration(),.02,1e-8,"penetration");
        check(box(new V3(0,.2,0),new V3(.2,.04,.4)).contact(ground,.03)==null,"separated belt no contact");
        var speculative=box(new V3(0,.05,0),new V3(.2,.04,.4)).contact(ground,.03);
        near(speculative.penetration(),-.01,1e-8,"speculative gap");
        check(strip.contact(box(new V3(.25,0,0),new V3(.1,2,2)),0).normal().x()<-.999,"front contact");
        check(strip.contact(box(new V3(0,.12,0),new V3(2,.08,2)),.01).normal().y()<-.999,"upper face contact");
        check(strip.contact(box(new V3(0,0,.45),new V3(2,2,.1)),0).normal().z()<-.999,"full width side contact");
        double c=Math.sqrt(.5);var tilted=new ContactBox(new V3(0,.02,0),new V3(c,c,0),V3.Z,new V3(c,-c,0),new V3(.2,.4,.04));
        check(tilted.contact(ground,0)!=null,"rotated strip contact");
        ContactBox step=box(new V3(.3,-.475,0),new V3(.1,.5,2));
        check(strip.surfaceContact(step,0).normal().y()>.999,"connected strip end cap must not snag a step edge");
        var approaching=box(new V3(-.02,.02,0),new V3(.2,.04,.4)).surfaceContact(step,.03);
        check(approaching!=null&&approaching.penetration()<0,"separated strip end cannot generate phantom support");
        Body a=new Body(new V3(0,-1,0),100);var belt=new ContactSolver.Belt(4,20);
        ContactSolver.solve(List.of(),belt,.8,.01);near(a.velocity.x(),0,1e-9,"motor alone cannot propel airborne chassis");
        var contact=new ContactSolver.Contact(a,null,V3.ZERO,V3.Y,V3.X,0);
        ContactSolver.solve(List.of(contact),belt,.5,.01);
        near(a.velocity.y(),0,1e-9,"normal impulse supports body");check(a.velocity.x()<0&&belt.speed<4,"powered belt traction opposes material motion");
        check(Math.hypot(contact.longImpulse,contact.sideImpulse)<=.5*contact.normalImpulse+1e-8,"Coulomb disk");
        a=new Body(new V3(3,-1,0),100);belt=new ContactSolver.Belt(0,20);
        contact=new ContactSolver.Contact(a,null,V3.ZERO,V3.Y,V3.X,0);ContactSolver.solve(List.of(contact),belt,2,.01);
        near(a.velocity.x()+belt.speed,0,1e-8,"free rolling no-slip speed");check(a.velocity.x()>2&&belt.speed<0,"free rolling preserves forward motion");
        a=new Body(new V3(3,-1,2),100);belt=new ContactSolver.Belt(0,20);
        contact=new ContactSolver.Contact(a,null,V3.ZERO,V3.Y,V3.X,0);ContactSolver.solve(List.of(contact),belt,0,.01);
        near(a.velocity.x(),3,1e-8,"zero friction preserves longitudinal motion");near(a.velocity.z(),2,1e-8,"zero friction preserves lateral motion");
        a=new Body(new V3(0,-1,0),100);Body other=new Body(V3.ZERO,200);belt=new ContactSolver.Belt(3,20);
        contact=new ContactSolver.Contact(a,other,V3.ZERO,V3.Y,V3.X,0);ContactSolver.solve(List.of(contact),belt,.8,.01);
        near(a.velocity.x()*100+other.velocity.x()*200,0,1e-8,"dynamic obstacle equal opposite traction");
        near(a.velocity.y()*100+other.velocity.y()*200,-100,1e-8,"dynamic obstacle momentum conservation");
        belt=new ContactSolver.Belt(0,20);belt.motor(100,100,.01);near(belt.speed,.05,1e-8,"motor force limit");belt.motor(0,1000,.1);near(belt.speed,0,1e-8,"brake no reversal");
        a=new Body(V3.ZERO,100);belt=new ContactSolver.Belt(4,20);contact=new ContactSolver.Contact(a,null,V3.ZERO,V3.Y,V3.X,-.02);
        ContactSolver.solve(List.of(contact),belt,.8,.01);near(a.velocity.length(),0,1e-8,"speculative separated contact does not provide traction");
        double[] impulses=new double[2];
        for(int k=0;k<2;k++) {
            a=new Body(new V3(0,-1,0),100);belt=new ContactSolver.Belt(20,20);var contacts=new ArrayList<ContactSolver.Contact>();
            for(int i=0;i<(k==0?1:30);i++) contacts.add(new ContactSolver.Contact(a,null,new V3(i,0,0),V3.Y,V3.X,0));
            impulses[k]=ContactSolver.solve(contacts,belt,.8,.01);
        }
        near(impulses[0],impulses[1],1e-8,"contact subdivision does not multiply support");
        var wheels=List.of(new TrackPath.Wheel(new TrackPath.Point(-2,0),.65),new TrackPath.Wheel(new TrackPath.Point(2,0),.65));
        var path=TrackPath.build(wheels);var spans=TrackPath.contactSpans(wheels,path.wheelDirections(),.45);
        check(spans.stream().anyMatch(s->s.first()!=s.second()),"straight spans route to two supports");
        check(spans.stream().anyMatch(s->s.first()==s.second()),"arc spans route to their wheel");
        double length=0;
        for(int i=0;i<spans.size();i++) {
            var span=spans.get(i);length+=span.end().sub(span.start()).length();
            near(span.end().sub(spans.get((i+1)%spans.size()).start()).length(),0,1e-7,"continuous closed physical path");
            check(span.secondWeight()>=0&&span.secondWeight()<=1,"bounded support weight");
        }
        near(length,path.length(),.03,"physical/render path lengths agree");
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,10.01}) {
            boolean rejected=false;try { new BeltSettings(invalid,30000,8,10,0,.08); }catch(IllegalArgumentException ex){rejected=true;}
            check(rejected,"invalid friction rejected");
        }
        double[] slip=new double[3];
        for(int i=0;i<3;i++) {
            double friction=new double[]{0,.8,10}[i];
            var settings=new BeltSettings(friction,30000,8,10,0,.08,2);
            a=new Body(new V3(0,-.1,1),100);belt=new ContactSolver.Belt(8,20);
            contact=new ContactSolver.Contact(a,null,V3.ZERO,V3.Y,V3.X,0);
            ContactSolver.solve(List.of(contact),belt,settings.friction(),.01);
            slip[i]=Math.hypot(a.velocity.x()+belt.speed,a.velocity.z());
            check(Math.hypot(contact.longImpulse,contact.sideImpulse)<=friction*contact.normalImpulse+1e-7,"Raised friction still obeys load limit");
        }
        check(slip[2]<slip[1]&&slip[1]<slip[0],"Friction setting measurably reduces combined longitudinal/lateral slip");
        near(new BeltSettings(.8,30000,8,10,0,.08).widthScale(),1,1e-9,"Legacy belt width defaults to one");
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,.24,3.01}) {
            boolean rejected=false;try {new BeltSettings(.8,30000,8,10,0,.08,invalid);}catch(IllegalArgumentException ex){rejected=true;}
            check(rejected,"invalid belt width rejected");
        }
        ContactBox narrow=new ContactBox(new V3(0,.02,0),V3.X,V3.Z,V3.Y,new V3(.2,.2,.04));
        ContactBox wide=new ContactBox(new V3(0,.02,0),V3.X,V3.Z,V3.Y,new V3(.2,.6,.04));
        ContactBox edge=box(new V3(0,-.5,.55),new V3(.5,.5,.1));
        check(narrow.surfaceContact(edge,0)==null&&wide.surfaceContact(edge,0)!=null,"Widened physical strip reaches support beyond narrow belt edge");
        System.out.println("Contact physics: "+checks+" checks passed");
    }
    private static void driveMultiplier() {
        var defaults=BeltSettings.DEFAULT;
        near(defaults.driveMultiplier(),1,1e-9,"Missing saved key inherits neutral default");
        near(defaults.drivenSpeed(3),3,1e-9,"Default preserves input");
        double[] values=defaults.values();values[18]=2;
        var doubled=BeltSettings.from(values);
        near(doubled.drivenSpeed(3),6,1e-9,"Double drive speed at fixed input");
        near(doubled.drivenSpeed(-3),-6,1e-9,"Preserve reverse direction");
        near(doubled.limitedSpeed(doubled.drivenSpeed(7)),8,1e-9,"Speed cap still applies");
        near(doubled.limitedSpeed(doubled.drivenSpeed(-7)),-8,1e-9,"Reverse speed cap");
        check(BeltSettings.from(doubled.values()).equals(doubled),"Multiplier survives schema round trip");
        var motor=new ContactSolver.Belt(0,20);
        motor.motor(doubled.limitedSpeed(doubled.drivenSpeed(3)),100000,.1);
        near(motor.speed,6,1e-9,"Physical belt motor reaches amplified target when unloaded");
        motor=new ContactSolver.Belt(0,20);motor.motor(doubled.drivenSpeed(3),100,.01);
        near(motor.speed,.05,1e-9,"Multiplier does not multiply available force");
        values[18]=0;near(BeltSettings.from(values).drivenSpeed(3),0,1e-9,"Zero multiplier means zero target");
        values[18]=16;near(BeltSettings.from(values).drivenSpeed(1),16,1e-9,"Upper multiplier boundary accepted");
        for(double invalid:new double[]{-1,16.01,Double.NaN,Double.POSITIVE_INFINITY}) {
            values[18]=invalid;boolean rejected=false;
            try {BeltSettings.from(values);}catch(IllegalArgumentException ex){rejected=true;}
            check(rejected,"Invalid multiplier rejected");
        }
    }
}
