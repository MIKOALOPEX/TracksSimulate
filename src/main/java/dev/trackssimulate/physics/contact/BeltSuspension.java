package dev.trackssimulate.physics.contact;

import dev.trackssimulate.physics.SuspensionSettings;

/** A bounded internal deformation coordinate, solved in the same velocity system as belt contacts.
 * It is not an additional native rigid body or joint. This removes the second constraint solver
 * which used to fight the vehicle/gravity-tool constraints, especially at large mass ratios.
 */
public final class BeltSuspension implements ContactSolver.Body {
    private final ContactSolver.Body chassis;
    private final V3 axis;
    private final double extension,dt,inverse,lowerVelocity,upperVelocity;
    private double velocity;
    private boolean stopped;
    public BeltSuspension(ContactSolver.Body chassis,V3 axis,SuspensionSettings s,double extension,double velocity,double dt) {
        this.chassis=chassis;this.axis=axis;this.dt=dt;this.extension=s.clamp(extension);
        // Preserve the acceleration-gain convention of the former native spring motor.
        double denominator=1+s.damping()*dt+s.stiffness()*dt*dt;
        inverse=1/(s.mass()*denominator);
        this.velocity=(velocity+s.stiffness()*dt*(this.extension-s.rest()))/denominator;
        lowerVelocity=(this.extension-s.maximum())/dt;
        upperVelocity=(this.extension-s.minimum())/dt;
        constrain();
    }
    private void constrain() {
        if(velocity>upperVelocity) {velocity=upperVelocity;stopped=true;}
        if(velocity<lowerVelocity) {velocity=lowerVelocity;stopped=true;}
    }
    public V3 velocity(V3 point) {return chassis.velocity(point).add(axis.mul(velocity));}
    public double inverseMass(V3 point,V3 direction) {
        double a=axis.dot(direction);return chassis.inverseMass(point,direction)+(stopped?0:inverse*a*a);
    }
    public double coupling(V3 point,V3 a,V3 b) {return chassis.coupling(point,a,b)+(stopped?0:inverse*axis.dot(a)*axis.dot(b));}
    public void impulse(V3 point,V3 impulse) {
        chassis.impulse(point,impulse);
        if(!stopped) {velocity+=impulse.dot(axis)*inverse;constrain();}
    }
    public double extension() {return extension-velocity*dt;}
    public double speed() {return stopped?0:velocity;}
    public boolean atStop() {return stopped;}
}
