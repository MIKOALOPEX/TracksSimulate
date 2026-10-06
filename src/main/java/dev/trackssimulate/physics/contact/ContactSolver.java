package dev.trackssimulate.physics.contact;

import java.util.List;

/** Coupled vehicle contact impulses with one material-speed DOF per belt and anisotropic friction. */
public final class ContactSolver {
    public interface Body {
        V3 velocity(V3 point);
        double inverseMass(V3 point,V3 direction);
        void impulse(V3 point,V3 impulse);
        default double coupling(V3 point,V3 a,V3 b) {
            // Polarization of the point effective-mass quadratic form, with unit perpendicular axes.
            return inverseMass(point,a.add(b).unit())-.5*(inverseMass(point,a)+inverseMass(point,b));
        }
    }
    public static final class Belt {
        public double speed;
        public final double mass;
        public double driveForceLimit;
        public double targetSpeed,motorImpulse,dragImpulse,speedBeforeContacts;
        public Belt(double speed,double mass) { this.speed=speed;this.mass=mass; }
        public void motor(double target,double force,double dt) {
            speed+=Math.max(-force*dt/mass,Math.min(force*dt/mass,target-speed));
        }
    }
    public static final class Contact {
        public record Source(int x,int y,int z,int bodyId,double mass,int exposedFaces) {}
        public final Body body,other;
        public final V3 point,normal,tangent,side;
        public final double penetration;
        public final double beltFactor;
        public final Source source;
        public double gripScale=1;
        public double normalImpulse,longImpulse,sideImpulse;
        public Contact(Body body,Body other,V3 point,V3 normal,V3 beltTangent,double penetration) {
            this(body,other,point,normal,beltTangent,penetration,null);
        }
        public Contact(Body body,Body other,V3 point,V3 normal,V3 beltTangent,double penetration,Source source) {
            this.source=source;
            this.body=body;this.other=other;this.point=point;this.normal=normal;this.penetration=penetration;
            V3 projected=beltTangent.sub(normal.mul(normal.dot(beltTangent)));
            beltFactor=projected.length();
            tangent=beltFactor>1e-8?projected.mul(1/beltFactor):normal.cross(Math.abs(normal.y())<.9?V3.Y:V3.X).unit();
            side=normal.cross(tangent).unit();
        }
        private V3 relative() { return body.velocity(point).sub(other==null?V3.ZERO:other.velocity(point)); }
        public double longitudinalSlip(Belt belt) {return relative().dot(tangent)+belt.speed*beltFactor;}
        public double lateralSlip() {return relative().dot(side);}
        private double inverse(V3 direction) { return body.inverseMass(point,direction)+(other==null?0:other.inverseMass(point,direction)); }
        private void push(V3 impulse) { body.impulse(point,impulse);if(other!=null) other.impulse(point,impulse.mul(-1)); }
    }
    public static double solve(List<Contact> contacts,Belt belt,double friction,double dt) {
        return solve(contacts,belt,friction,friction,dt);
    }
    public record Group(List<Contact> contacts,Belt belt,double friction,double lateralFriction,double softness,double recoverySpeed,double lateralSlipSpeed) {
        public Group(List<Contact> contacts,Belt belt,double friction,double lateralFriction) {this(contacts,belt,friction,lateralFriction,0,.12,0);}
        public Group(List<Contact> contacts,Belt belt,double friction,double lateralFriction,double softness,double recoverySpeed) {this(contacts,belt,friction,lateralFriction,softness,recoverySpeed,0);}
    }
    public static double solve(List<Contact> contacts,Belt belt,double friction,double lateralFriction,double dt) {
        solveGroups(List.of(new Group(contacts,belt,friction,lateralFriction)),dt);
        return contacts.stream().mapToDouble(c->c.normalImpulse).sum();
    }
    public static void solveGroups(List<Group> groups,double dt) {
        if(!(dt>0)||!Double.isFinite(dt)) throw new IllegalArgumentException("Invalid timestep");
        // Alternating traversal reduces a preferred end on straight spans shared by two springs.
        for(int iteration=0;iteration<24;iteration++) for(int groupIndex=0;groupIndex<groups.size();groupIndex++) {
            Group group=groups.get((iteration&1)==0?groupIndex:groups.size()-1-groupIndex);
            List<Contact> contacts=group.contacts;Belt belt=group.belt;double friction=group.friction,lateralFriction=group.lateralFriction;
            for(int index=0;index<contacts.size();index++) {
                Contact c=contacts.get((iteration&1)==0?index:contacts.size()-1-index);
                double inverse=c.inverse(c.normal);if(!(inverse>1e-12)) continue;
                // No restitution. Only a slow positional recovery, never a 2 m/s separating kick.
                double target=c.penetration>0?Math.min(group.recoverySpeed,.1*Math.max(0,c.penetration-.005)/dt):c.penetration/dt;
                double softness=inverse*group.softness;
                double increment=(target-c.relative().dot(c.normal)-softness*c.normalImpulse)/(inverse+softness);
                double next=Math.max(0,c.normalImpulse+increment);
                c.push(c.normal.mul(next-c.normalImpulse));c.normalImpulse=next;
                double longitudinal=c.inverse(c.tangent)+c.beltFactor*c.beltFactor/belt.mass,lateral=c.inverse(c.side);
                if(longitudinal<1e-12||lateral<1e-12)continue;
                double coupling=c.body.coupling(c.point,c.tangent,c.side)+(c.other==null?0:c.other.coupling(c.point,c.tangent,c.side));
                coupling=Math.max(-.999*Math.sqrt(longitudinal*lateral),Math.min(.999*Math.sqrt(longitudinal*lateral),coupling));
                V3 relative=c.relative();double vt=relative.dot(c.tangent)+belt.speed*c.beltFactor,vs=relative.dot(c.side);
                double mu=friction*c.gripScale,muSide=lateralFriction*c.gripScale;
                // Finite contact-patch shear response: before saturation, Js = -mu*Jn*vs/v_transition.
                // This permits controlled skid steering without replacing high-speed lateral grip by a rigid lock.
                // The extra positive diagonal is implicit damping; physical energy below still uses the real mass matrix.
                double shear=muSide*c.normalImpulse>1e-12?group.lateralSlipSpeed/(muSide*c.normalImpulse):0;
                double a=longitudinal*mu*mu,b=coupling*mu*muSide,d=(lateral+shear)*muSide*muSide;
                double rx=(longitudinal*c.longImpulse+coupling*c.sideImpulse-vt)*mu;
                double ry=(coupling*c.longImpulse+lateral*c.sideImpulse-vs)*muSide;
                // Minimize contact kinetic energy on a friction ellipse in the effective-mass metric.
                // A Euclidean clamp of independent axis solves can ADD energy on a rotating chassis.
                double lo=0,hi=Math.max(1e-12,Math.hypot(rx,ry)/Math.max(c.normalImpulse,1e-12));
                double x=0,y=0;
                for(int solve=0;solve<24;solve++) {
                    double lambda=solve==0?0:(lo+hi)*.5;
                    double aa=a+lambda,dd=d+lambda,det=aa*dd-b*b;
                    if(det>1e-30) {x=(dd*rx-b*ry)/det;y=(aa*ry-b*rx)/det;}
                    else {x=aa>1e-20?rx/aa:0;y=dd>1e-20?ry/dd:0;}
                    if(Math.hypot(x,y)<=c.normalImpulse) {hi=lambda;if(solve==0)break;} else lo=lambda;
                }
                double size=Math.hypot(x,y);
                if(size>c.normalImpulse&&size>0) {x*=c.normalImpulse/size;y*=c.normalImpulse/size;}
                double wantedLong=x*mu,wantedSide=y*muSide;
                double dl=wantedLong-c.longImpulse,ds=wantedSide-c.sideImpulse;
                double work=dl*vt+ds*vs,quadratic=longitudinal*dl*dl+2*coupling*dl*ds+lateral*ds*ds;
                // A shrinking normal load may make the previous friction impulse infeasible.
                // Release it only as far as the update remains dissipative, instead of injecting a kick.
                double fraction=quadratic>1e-20?Math.max(0,Math.min(1,-2*work/quadratic)):1;
                dl*=fraction;ds*=fraction;
                c.push(c.tangent.mul(dl).add(c.side.mul(ds)));
                belt.speed+=dl*c.beltFactor/belt.mass;c.longImpulse+=dl;c.sideImpulse+=ds;
            }
        }
    }
    private ContactSolver() {}
}
