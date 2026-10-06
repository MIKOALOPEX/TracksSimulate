package dev.ryanhcode.sable.physics.impl.rapier;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import net.jpountz.lz4.LZ4FrameInputStream;
import dev.trackssimulate.physics.SuspensionSettings;
import dev.trackssimulate.wheel.WheelGeometry;
import dev.trackssimulate.physics.contact.*;
import dev.trackssimulate.track.TrackPath;
import java.util.*;

/** Isolated JNI contract check. This source set must NEVER be included in the mod or Minecraft classpath. */
public final class Rapier3D {
    static native long initialize(double x,double y,double z,double drag);
    static native void dispose(long scene);
    static native void createBox(long scene,int id,double mass,double x,double y,double z,double[] pose);
    static native void removeBox(long scene,int id);
    static native void getPose(long scene,int id,double[] pose);
    static native void step(long scene,double dt);
    static native void getLinearVelocity(long scene,int id,double[] value);
    static native void applyForceAndTorque(long scene,int id,double x,double y,double z,double tx,double ty,double tz,boolean wake);
    static native long addGenericConstraint(long scene,int a,int b,double ax,double ay,double az,double qax,double qay,double qaz,double qaw,
        double bx,double by,double bz,double qbx,double qby,double qbz,double qbw,int mask);
    static native void setConstraintContactsEnabled(long scene,long joint,boolean enabled);
    static native void setConstraintMotor(long scene,long joint,int axis,double target,double k,double c,boolean capped,double max);
    static native void setConstraintLimit(long scene,long joint,int axis,double min,double max);
    static native void removeConstraint(long scene,long joint);
    static native boolean isConstraintValid(long scene,long joint);
    private static int checks;
    private static void check(boolean value,String name) { if(!value) throw new AssertionError(name);checks++; }
    private static long joint(long scene,int a,int b,double height,int mask) {
        return addGenericConstraint(scene,a,b,0,height,0,0,0,0,1,0,0,0,0,0,0,1,mask);
    }
    private static double y(long scene,int id) { double[] pose=new double[7];getPose(scene,id,pose);return pose[1]; }
    private static void advance(long scene) { for(int i=0;i<800;i++) step(scene,1.0/120); }
    private static double hanging(double gravity,boolean spring,boolean floor,boolean overlap) {
        long scene=initialize(0,gravity,0,0);
        try {
            var settings=SuspensionSettings.DEFAULT;
            createBox(scene,1,settings.mass(),.44,.59,.59,new double[]{0,3.5,0,0,0,0,1});
            long suspension=joint(scene,-1,1,4,61);
            setConstraintContactsEnabled(scene,suspension,false);
            setConstraintLimit(scene,suspension,1,settings.jointMinimum(),settings.jointMaximum());
            if(spring) setConstraintMotor(scene,suspension,1,settings.jointRest(),settings.stiffness(),settings.damping(),false,0);
            if(floor) {
                createBox(scene,2,10000,2,.5,2,new double[]{0,2.5,0,0,0,0,1});
                joint(scene,-1,2,2.5,63);
            }
            if(overlap) {
                createBox(scene,3,settings.mass(),.44,.59,.59,new double[]{0,3.5,0,0,0,0,1});
                long other=joint(scene,-1,3,4,61);
                setConstraintLimit(scene,other,1,-1,0);
                setConstraintMotor(scene,other,1,-.5,100,20,false,0);
                long exclusion=joint(scene,1,3,0,0);
                setConstraintContactsEnabled(scene,exclusion,false);
            }
            advance(scene);double result=y(scene,1);
            if(overlap) check(Math.abs(result-y(scene,3))<.001,"overlapping wheels remain coincident");
            check(isConstraintValid(scene,suspension),"live suspension joint");
            removeConstraint(scene,suspension);removeConstraint(scene,suspension);
            check(!isConstraintValid(scene,suspension),"constraint removal is idempotent");
            removeBox(scene,1);
            // Match the host-removed-before-owner cleanup order used by Sable unload.
            if(overlap) { removeBox(scene,3);advance(scene); }
            return result;
        } finally { dispose(scene); }
    }
    private static void chassisSupport() {
        long scene=initialize(0,-9.81,0,0);
        try {
            createBox(scene,1,1000,.2,.1,.2,new double[]{0,3,0,0,0,0,1});
            createBox(scene,2,10000,2,.5,2,new double[]{0,-.5,0,0,0,0,1});
            joint(scene,-1,2,-.5,63);
            createBox(scene,3,100,.44,.59,.59,new double[]{0,2.5,0,0,0,0,1});
            long spring=joint(scene,1,3,0,61);
            setConstraintContactsEnabled(scene,spring,false);
            setConstraintLimit(scene,spring,1,-1,0);
            setConstraintMotor(scene,spring,1,-.5,1000,40,false,0);
            advance(scene);
            check(y(scene,3)>.56&&y(scene,3)<.62,"wheel remains on floor under chassis load");
            check(y(scene,1)>.85&&y(scene,1)<1.1,"wheel contact transmits support to dynamic chassis: "+y(scene,1));
            check(y(scene,1)-y(scene,3)<.5,"chassis load compresses spring");
            removeBox(scene,1);removeConstraint(scene,spring);removeBox(scene,3);advance(scene);
            check(!isConstraintValid(scene,spring),"host-first cleanup leaves no joint");
        } finally { dispose(scene); }
    }
    private static void configuredWheels() {
        var geometry=new WheelGeometry(2,1.5,1,.5,.75);
        for(int axis=0;axis<3;axis++) {
            long scene=initialize(0,-9.81,0,0);
            try {
                var offset=geometry.offset(axis);var size=geometry.halfExtents(axis,.59,.88);
                double x=offset.x(),y=4+offset.y(),z=offset.z();
                createBox(scene,1,100,size.x(),size.y(),size.z(),new double[]{x+.1,y+.1,z+.1,0,0,0,1});
                long fixed=addGenericConstraint(scene,-1,1,x,y,z,0,0,0,1,0,0,0,0,0,0,1,63);
                advance(scene);double[] pose=new double[7];getPose(scene,1,pose);
                check(Math.abs(pose[0]-x)<.002&&Math.abs(pose[1]-y)<.002&&Math.abs(pose[2]-z)<.002,"fixed wheel respects offset, axis "+axis);
                removeConstraint(scene,fixed);removeBox(scene,1);
            } finally { dispose(scene); }
        }
        long scene=initialize(0,-9.81,0,0);
        try {
            var size=geometry.halfExtents(0,.59,.88);
            createBox(scene,1,100,size.x(),size.y(),size.z(),new double[]{0,2,0,0,0,0,1});
            createBox(scene,2,10000,3,.5,3,new double[]{0,-.5,0,0,0,0,1});joint(scene,-1,2,-.5,63);
            advance(scene);
            check(Math.abs(y(scene,1)-1.18)<.015,"scaled wheel uses enlarged native ground contact");
        } finally { dispose(scene); }
    }
    /** Planar rig with rotation locked by native joints; production contact solver and path tessellation drive the JNI body. */
    private static final class PlanarBody implements LoadRouting.RigidBody {
        final long scene;final int id;final double mass;V3 velocity,impulse=V3.ZERO,center;
        PlanarBody(long scene,int id,double mass) {
            this.scene=scene;this.id=id;this.mass=mass;double[] v=new double[3],p=new double[7];getLinearVelocity(scene,id,v);getPose(scene,id,p);
            velocity=new V3(v[0],v[1],v[2]);center=new V3(p[0],p[1],p[2]);
        }
        public V3 velocity(V3 point) { return velocity; }
        public V3 center() { return center; }
        public double inverseMass() { return 1/mass; }
        public double crossResponse(V3 p,V3 n,V3 q,V3 m) { return (n.x()*m.x()+n.y()*m.y())/mass; }
        public double inverseMass(V3 point,V3 n) { return (n.x()*n.x()+n.y()*n.y())/mass; }
        public void impulse(V3 point,V3 j) { j=new V3(j.x(),j.y(),0);impulse=impulse.add(j);velocity=velocity.add(j.mul(1/mass)); }
        void flush() { applyForceAndTorque(scene,id,impulse.x(),impulse.y(),0,0,0,0,true); }
    }
    private static ContactBox contactBox(V3 c,V3 h) { return new ContactBox(c,V3.X,V3.Y,V3.Z,h); }
    private static double[] beltRun(boolean powered,boolean floor,boolean stair,boolean brake,double initialSpeed,boolean reverse) {
        long scene=initialize(0,floor?-9.81:0,0,0);
        try {
            createBox(scene,1,1000,.2,.1,.2,new double[]{0,2,0,0,0,0,1});
            joint(scene,-1,1,0,60); // planar movement only, no native terrain collider
            if(initialSpeed!=0) applyForceAndTorque(scene,1,initialSpeed*1000,0,0,0,0,0,true);
            var wheels=List.of(new TrackPath.Wheel(new TrackPath.Point(-2,-.5),.65),new TrackPath.Wheel(new TrackPath.Point(2,-.5),.65));
            var path=TrackPath.build(wheels);var spans=TrackPath.contactSpans(wheels,path.wheelDirections(),.45);
            var belt=new ContactSolver.Belt(0,path.length()*20);
            var ground=contactBox(new V3(0,-.5,0),new V3(100,.5,2));
            var leftStep=contactBox(new V3(-58,-.375,0),new V3(50,.625,2));
            var rightStep=contactBox(new V3(58,-.375,0),new V3(50,.625,2));
            double maxHeight=0;
            for(int tick=0;tick<1200;tick++) {
                double dt=1.0/120;var body=new PlanarBody(scene,1,1000);var contacts=new ArrayList<ContactSolver.Contact>();
                if(floor) for(var span:spans) {
                    V3 a=body.center.add(new V3(span.start().x(),span.start().y(),0)),b=body.center.add(new V3(span.end().x(),span.end().y(),0));
                    V3 tangent=b.sub(a).unit();var box=new ContactBox(a.add(b).mul(.5),tangent,V3.Z,tangent.cross(V3.Z),new V3(b.sub(a).length()/2+.003,.4,.04));
                    for(ContactBox obstacle:stair?List.of(ground,leftStep,rightStep):List.of(ground)) {
                        var hit=box.surfaceContact(obstacle,.1);
                        if(hit!=null) contacts.add(new ContactSolver.Contact(body,null,hit.point(),hit.normal(),tangent,hit.penetration()));
                    }
                }
                if(powered&&tick>180) belt.motor(reverse?-4:4,30000,dt);
                if(brake&&tick>180) belt.motor(0,200000,dt);
                ContactSolver.solve(contacts,belt,.8,dt);body.flush();step(scene,dt);
                if(tick>600) maxHeight=Math.max(maxHeight,body.center.y());
            }
            var body=new PlanarBody(scene,1,1000);
            return new double[]{body.center.x(),body.center.y(),body.velocity.x(),belt.speed,maxHeight};
        } finally { dispose(scene); }
    }
    private static void beltPhysics() {
        double[] resting=beltRun(false,true,false,false,0,false);
        check(Math.abs(resting[1]-1.19)<.025,"belt surface alone supports native body: "+Arrays.toString(resting));
        check(Math.abs(resting[0])<.01,"stationary unpowered belt does not drift");
        double[] powered=beltRun(true,true,false,false,0,false);
        check(Math.abs(powered[0])>15&&Math.abs(powered[2])>3,"native belt traction drives chassis: "+Arrays.toString(powered));
        double[] reverse=beltRun(true,true,false,false,0,true);
        check(powered[0]*reverse[0]<0&&Math.abs(reverse[0])>15,"reversing motor reverses vehicle");
        double[] air=beltRun(true,false,false,false,0,false);
        check(Math.abs(air[0])<1e-8&&Math.abs(air[2])<1e-8&&Math.abs(air[3])>3,"airborne belt spins without pushing native body");
        double[] free=beltRun(false,true,false,false,3,false);
        check(free[0]>15&&free[2]>1.5&&Math.abs(free[3])>1.5,"external motion spins free belt: "+Arrays.toString(free));
        double[] braked=beltRun(false,true,false,true,3,false);
        check(Math.abs(braked[2])<.03&&braked[0]<free[0]*.5,"grounded brake stops native chassis: "+Arrays.toString(braked));
        double[] stair=beltRun(true,true,true,false,0,false);
        check(Math.abs(stair[0])>12&&stair[4]>1.35,"belt arc climbs quarter-block ledge: "+Arrays.toString(stair));
        System.out.printf("Native belt: supportY=%.4f drivenX=%.3f reverseX=%.3f freeX=%.3f brakeX=%.3f ledgeY=%.4f%n",resting[1],powered[0],reverse[0],free[0],braked[0],stair[4]);
    }
    private static void suspendedBelt(boolean pedestal) {
        suspendedBelt(pedestal,false);
    }
    private static void suspendedBelt(boolean pedestal,boolean middle) {
        long scene=initialize(0,-9.81,0,0);
        try {
            createBox(scene,1,1000,2.4,.25,.4,new double[]{0,3,0,0,0,0,1});joint(scene,-1,1,0,60);
            for(int id=2;id<=3;id++) {
                double x=id==2?-2:2;createBox(scene,id,100,.12,.12,.12,new double[]{x,2.5,0,0,0,0,1});
                long joint=addGenericConstraint(scene,1,id,x,0,0,0,0,0,1,0,0,0,0,0,0,1,61);
                setConstraintContactsEnabled(scene,joint,false);setConstraintLimit(scene,joint,1,-1,0);setConstraintMotor(scene,joint,1,-.5,1000,40,false,0);
            }
            if(middle) {
                createBox(scene,4,100,.12,.12,.12,new double[]{0,2.9,0,0,0,0,1});
                long extra=joint(scene,1,4,0,61);setConstraintContactsEnabled(scene,extra,false);
                setConstraintLimit(scene,extra,1,-.25,0);setConstraintMotor(scene,extra,1,-.1,1000,40,false,0);
            }
            var ground=contactBox(new V3(0,-.5,0),new V3(pedestal?.4:100,.5,2));
            var belt=new ContactSolver.Belt(0,242);List<Integer> directions=null;double middleImpulse=0;
            for(int tick=0;tick<1200;tick++) {
                double dt=1.0/120;var chassis=new PlanarBody(scene,1,1000);var a=new PlanarBody(scene,2,100);var b=new PlanarBody(scene,3,100);
                var supports=new ArrayList<ContactSolver.Body>();var wheels=new ArrayList<TrackPath.Wheel>();
                supports.add(LoadRouting.suspension(chassis,a,V3.Y));wheels.add(new TrackPath.Wheel(new TrackPath.Point(-2,a.center.y()-chassis.center.y()),.65,0,3,true));
                PlanarBody mid=middle?new PlanarBody(scene,4,100):null;
                if(mid!=null) { supports.add(LoadRouting.suspension(chassis,mid,V3.Y));wheels.add(new TrackPath.Wheel(new TrackPath.Point(0,mid.center.y()-chassis.center.y()),.3,1,3,true)); }
                supports.add(LoadRouting.suspension(chassis,b,V3.Y));wheels.add(new TrackPath.Wheel(new TrackPath.Point(2,b.center.y()-chassis.center.y()),.65,0,3,true));
                if(directions==null) directions=TrackPath.build(wheels).wheelDirections();
                var contacts=new ArrayList<ContactSolver.Contact>();
                for(var span:TrackPath.contactSpans(wheels,directions,.45)) {
                    V3 start=chassis.center.add(new V3(span.start().x(),span.start().y(),0)),end=chassis.center.add(new V3(span.end().x(),span.end().y(),0)),tangent=end.sub(start).unit();
                    var box=new ContactBox(start.add(end).mul(.5),tangent,V3.Z,tangent.cross(V3.Z),new V3(end.sub(start).length()/2+.003,.4,.04));
                    var hit=box.surfaceContact(ground,.1);
                    if(hit!=null) contacts.add(new ContactSolver.Contact(LoadRouting.blend(supports.get(span.first()),supports.get(span.second()),span.secondWeight()),null,hit.point(),hit.normal(),tangent,hit.penetration()));
                }
                if(!pedestal&&tick>240) belt.motor(4,30000,dt);
                if(pedestal) belt.motor(0,200000,dt);
                ContactSolver.solve(contacts,belt,.8,dt);chassis.flush();a.flush();b.flush();
                if(mid!=null) { middleImpulse+=mid.impulse.length();mid.flush(); }
                step(scene,dt);
            }
            var chassis=new PlanarBody(scene,1,1000);var a=new PlanarBody(scene,2,100);var b=new PlanarBody(scene,3,100);
            check(chassis.center.y()>.8&&chassis.center.y()<1.25,"band supports spring chassis "+(pedestal?"on central pedestal":"while driving")+": "+chassis.center);
            check(chassis.center.y()-a.center.y()>=-.01&&chassis.center.y()-a.center.y()<.5,"front suspension compresses within limits");
            check(chassis.center.y()-b.center.y()>=-.01&&chassis.center.y()-b.center.y()<.5,"rear suspension compresses within limits");
            if(pedestal) check(Math.abs(chassis.center.x())<.05&&a.center.x()<-1.1&&b.center.x()>1.1,"parked straight belt bridges support with both wheels clear of pedestal: "+chassis.center);
            else check(Math.abs(chassis.center.x())>12&&Math.abs(chassis.velocity.x())>3,"native suspended belt can drive: "+chassis.center);
            if(middle) {
                check(middleImpulse<1e-9,"selected but detached middle wheel receives no belt support impulse");
                check(y(scene,4)-.3>Math.max(a.center.y(),b.center.y())-.65+.2,"middle wheel stays clear while endpoints carry band");
            }
            System.out.printf("Suspended belt (%s%s): chassisY=%.4f hubY=%.4f x=%.3f%n",pedestal?"bridge":"drive",middle?" with detached middle":"",chassis.center.y(),a.center.y(),chassis.center.x());
        } finally { dispose(scene); }
    }
    private static void impulseFrame() {
        long scene=initialize(0,0,0,0);
        try {
            createBox(scene,1,100,.2,.1,.2,new double[]{0,0,0,0,0,Math.sqrt(.5),Math.sqrt(.5)});
            applyForceAndTorque(scene,1,100,0,0,0,0,0,true);double[] v=new double[3];getLinearVelocity(scene,1,v);
            check(Math.abs(v[0])<1e-6&&Math.abs(v[1]-1)<1e-6,"native impulse uses body-local coordinates");
        } finally { dispose(scene); }
    }
    public static void main(String[] args) throws Exception {
        Path dll=Path.of(args[1]).toAbsolutePath();Files.createDirectories(dll.getParent());
        try(ZipFile mod=new ZipFile(args[0])) {
            var nested=mod.stream().filter(e->e.getName().endsWith(".jar")&&e.getName().contains("sable_rapier")).findFirst().orElseThrow();
            try(ZipInputStream backend=new ZipInputStream(mod.getInputStream(nested))) {
                ZipEntry entry;boolean extracted=false;
                while((entry=backend.getNextEntry())!=null) if(entry.getName().endsWith("binaries.zip.l4z")) {
                    try(ZipInputStream natives=new ZipInputStream(new LZ4FrameInputStream(new ByteArrayInputStream(backend.readAllBytes())))) {
                        while((entry=natives.getNextEntry())!=null) if(entry.getName().equals("sable_rapier_x86_64_windows.dll")) {
                            Files.copy(natives,dll,StandardCopyOption.REPLACE_EXISTING);extracted=true;break;
                        }
                    }
                    break;
                }
                if(!extracted) throw new FileNotFoundException("Windows native library in "+args[0]);
            }
        }
        System.load(dll.toString());
        double rest=hanging(-9.81,true,false,false);
        check(rest>3.35&&rest<3.48,"spring settles below natural position: "+rest);
        double lower=hanging(-100,false,false,false);
        check(Math.abs(lower-3)<.03,"extension hard limit: "+lower);
        double upper=hanging(100,false,false,false);
        check(Math.abs(upper-4)<.03,"compression hard limit: "+upper);
        double support=hanging(-9.81,true,true,false);
        check(support>3.56&&support<3.62,"native floor contact supports wheel: "+support);
        double same=hanging(-9.81,true,false,true);
        check(Math.abs(same-rest)<.005,"same-vehicle contact exclusion: "+same);
        chassisSupport();
        configuredWheels();
        beltPhysics();
        suspendedBelt(false);suspendedBelt(true);impulseFrame();
        suspendedBelt(true,true);
        System.out.printf("Native suspension: %d checks passed; restY=%.5f lowerY=%.5f upperY=%.5f contactY=%.5f%n",checks,rest,lower,upper,support);
    }
}
