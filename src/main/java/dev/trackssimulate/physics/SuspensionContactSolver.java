package dev.trackssimulate.physics;

import java.util.List;

/** Coupled spring, unilateral hard-stop and friction impulses across contact bodies. */
public final class SuspensionContactSolver {
    public static final int MAX_ITERATIONS = 64;
    /** Shared velocity response for a physical body across all contact pairs. */
    public static final class Body { public final double[] delta=new double[6]; }

    /** One finite rolling inertia shared by every contact of an unpowered loop.
     * This is an internal degree of freedom, not a prescribed ground velocity. */
    public static final class FreeBelt {
        public final double inverseMass;
        public double speed;
        private double brake,brakeImpulse;
        public FreeBelt(double mass, double speed) {
            if(!(mass>0)||!Double.isFinite(mass)||!Double.isFinite(speed))throw new IllegalArgumentException("Invalid rolling state");
            this.inverseMass=1/mass;this.speed=speed;
        }
        public void brake(double fraction){brake=Math.max(0,Math.min(1,fraction));}
        public double brake(){return brake;}
    }

    public static final class Row {
        // Jacobian and inverse-mass response, in the body's local frame.
        public final double[] j = new double[6], response = new double[6];
        public final double velocity, target, softness, diagonal;
        private final double massDiagonal;
        public double impulse;
        private FreeBelt belt;
        private double beltJacobian;
        private boolean lockedRolling;
        private Body owner;
        private Row reaction;
        public Row bind(Body body){owner=body;return this;}
        public void react(Row opposite){
            if(owner==null||opposite==null||opposite.owner==null||opposite.owner==owner||opposite.reaction!=null)
                throw new IllegalArgumentException("Reaction needs two distinct bound bodies");
            reaction=opposite;
        }
        public Row reaction(){return reaction;}
        public void lockRolling(){lockedRolling=true;}

        public void couple(FreeBelt belt,double winding) {
            if(!Double.isFinite(winding)||Math.abs(winding)>1.000001)throw new IllegalArgumentException("Invalid rolling projection");
            this.belt=belt;this.beltJacobian=-winding;
        }
        private double effectiveSoftness(){return lockedRolling?0:softness;}
        private double effectiveDiagonal(){return (lockedRolling?massDiagonal:diagonal)
            +(belt==null?0:belt.inverseMass*beltJacobian*beltJacobian)+(reaction==null?0:reaction.massDiagonal);}

        public Row(double nx, double ny, double nz, double rx, double ry, double rz,
                   double inverseMass, double[] inverseInertia, double velocity, double target, double softness) {
            j[0]=nx; j[1]=ny; j[2]=nz;
            j[3]=ry*nz-rz*ny; j[4]=rz*nx-rx*nz; j[5]=rx*ny-ry*nx;
            for(int i=0;i<3;i++) {
                response[i]=j[i]*inverseMass;
                response[i+3]=inverseInertia[i*3]*j[3]+inverseInertia[i*3+1]*j[4]+inverseInertia[i*3+2]*j[5];
            }
            this.velocity=velocity; this.target=target; this.softness=softness;
            double effective=0;
            for(int i=0;i<6;i++)effective+=j[i]*response[i];
            massDiagonal=effective;diagonal=effective+softness;
            if(!(diagonal>0) || !Double.isFinite(diagonal) || softness<0
                || !Double.isFinite(velocity) || !Double.isFinite(target))
                throw new IllegalArgumentException("Invalid suspension contact row");
        }

        private double predicted(double[] delta) {
            if(owner!=null)delta=owner.delta;
            double value=velocity;
            for(int i=0;i<6;i++)value+=j[i]*delta[i];
            if(reaction!=null)for(int i=0;i<6;i++)value+=reaction.j[i]*reaction.owner.delta[i];
            if(belt!=null)value+=beltJacobian*belt.speed;
            return value;
        }

        private double setImpulse(double value, double[] delta) {
            if(owner!=null)delta=owner.delta;
            double change=value-impulse; impulse=value;
            for(int i=0;i<6;i++)delta[i]+=response[i]*change;
            if(reaction!=null)for(int i=0;i<6;i++)reaction.owner.delta[i]+=reaction.response[i]*change;
            if(belt!=null)belt.speed+=belt.inverseMass*beltJacobian*change;
            return Math.abs(change);
        }

        private double solve(double min, double max, double[] delta) {
            double value=impulse+(target-predicted(delta)-effectiveSoftness()*impulse)/effectiveDiagonal();
            return setImpulse(Math.max(min,Math.min(max,value)),delta);
        }

        private double residual(double min, double max, double[] delta) {
            double value=impulse+(target-predicted(delta)-effectiveSoftness()*impulse)/effectiveDiagonal();
            return Math.abs(impulse-Math.max(min,Math.min(max,value)));
        }
    }

    public record Contact(Row normal, Row stop, Row forward, Row side, double maxNormal,
                          double forwardFriction, double sideFriction) {
        public Contact {
            if(!(maxNormal>=0)||!Double.isFinite(maxNormal)||!(forwardFriction>=0)||!Double.isFinite(forwardFriction)
                ||!(sideFriction>=0)||!Double.isFinite(sideFriction))throw new IllegalArgumentException("Invalid contact limits");
        }
        public Contact(Row normal,Row forward,Row side,double maxNormal,double friction) {
            this(normal,null,forward,side,maxNormal,friction,friction);
        }
        public double supportImpulse(){return normal.impulse+(stop==null?0:stop.impulse);}
    }
    public record Result(int iterations, double residual, double maxDeltaSpeed, double maxDeltaAngularSpeed) {}

    public static Result solve(List<Contact> contacts) {
        if(contacts.isEmpty())return new Result(0,0,0,0);
        double[] delta=new double[6];
        var bodies=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Body,Boolean>());
        for(var c:contacts)for(var row:new Row[]{c.normal,c.stop,c.forward,c.side})if(row!=null) {
            if(row.owner!=null)bodies.add(row.owner);
            if(row.reaction!=null)bodies.add(row.reaction.owner);
        }
        for(var body:bodies)java.util.Arrays.fill(body.delta,0);
        var freeBelts=new java.util.LinkedHashMap<FreeBelt,Double>();
        for(Contact c:contacts) {
            c.normal.impulse=c.forward.impulse=c.side.impulse=0;
            if(c.stop!=null)c.stop.impulse=0;
            if(c.forward.belt!=null&&c.forward.belt.brake>0)freeBelts.put(c.forward.belt,0.0);
        }
        for(var belt:freeBelts.keySet())belt.brakeImpulse=0;
        // Seed from the current spring impulse, not a stale prior frame. This
        // exactly represents balanced static load and avoids slowly rebuilding
        // it from zero when damping is large relative to rotational inertia.
        for(Contact c:contacts) {
            double seed=c.normal.softness>0?c.normal.target/c.normal.softness:0;
            c.normal.setImpulse(Math.max(0,Math.min(c.maxNormal,seed)),delta);
        }
        int iterations=0;
        for(;iterations<MAX_ITERATIONS;iterations++) {
            double change=0;
            // Alternating traversal avoids permanently favoring one end of the track.
            for(int step=0;step<contacts.size();step++) {
                Contact c=contacts.get((iterations&1)==0?step:contacts.size()-1-step);
                change=Math.max(change,c.normal.solve(0,c.maxNormal,delta));
                // The spring-force cap must not turn a bottomed-out suspension
                // into a yielding spring again. A zero-restitution stop solves
                // the missing support through the same coupled mass matrix.
                if(c.stop!=null)change=Math.max(change,c.stop.solve(0,Double.POSITIVE_INFINITY,delta));
                double forwardLimit=c.forwardFriction*c.supportImpulse(), sideLimit=c.sideFriction*c.supportImpulse();
                change=Math.max(change,c.forward.solve(-forwardLimit,forwardLimit,delta));
                change=Math.max(change,c.side.solve(-sideLimit,sideLimit,delta));
                double length=Math.hypot(forwardLimit>0?c.forward.impulse/forwardLimit:0,
                                         sideLimit>0?c.side.impulse/sideLimit:0);
                if(length>1) {
                    double scale=1/length;
                    change=Math.max(change,c.forward.setImpulse(c.forward.impulse*scale,delta));
                    change=Math.max(change,c.side.setImpulse(c.side.impulse*scale,delta));
                }
            }
            if(!freeBelts.isEmpty()) {
                freeBelts.replaceAll((belt,budget)->0.0);
                for(Contact c:contacts)if(c.forward.belt!=null&&c.forward.belt.brake>0)freeBelts.merge(c.forward.belt,
                    c.forwardFriction*c.supportImpulse()*c.forward.belt.brake,Double::sum);
                for(var entry:freeBelts.entrySet()) {
                    var belt=entry.getKey();double limit=entry.getValue();
                    double next=Math.max(-limit,Math.min(limit,belt.brakeImpulse-belt.speed/belt.inverseMass));
                    double diff=next-belt.brakeImpulse;belt.speed+=diff*belt.inverseMass;belt.brakeImpulse=next;
                    change=Math.max(change,Math.abs(diff));
                }
            }
            if(iterations>=7 && change<1e-6) {iterations++;break;}
        }
        double residual=0;
        for(Contact c:contacts) {
            residual=Math.max(residual,c.normal.residual(0,c.maxNormal,delta));
            if(c.stop!=null)residual=Math.max(residual,c.stop.residual(0,Double.POSITIVE_INFINITY,delta));
        }
        double dv=Math.sqrt(delta[0]*delta[0]+delta[1]*delta[1]+delta[2]*delta[2]),dw=Math.sqrt(delta[3]*delta[3]+delta[4]*delta[4]+delta[5]*delta[5]);
        for(var body:bodies){var d=body.delta;dv=Math.max(dv,Math.sqrt(d[0]*d[0]+d[1]*d[1]+d[2]*d[2]));dw=Math.max(dw,Math.sqrt(d[3]*d[3]+d[4]*d[4]+d[5]*d[5]));}
        return new Result(iterations,residual,dv,dw);
    }

    private SuspensionContactSolver() {}
}
