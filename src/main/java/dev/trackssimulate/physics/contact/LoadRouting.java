package dev.trackssimulate.physics.contact;

/** Virtual-work mapping: suspension-axis load enters the hub; the other components and residual torque enter the chassis. */
public final class LoadRouting {
    public interface RigidBody extends ContactSolver.Body {
        V3 center();
        double inverseMass();
        double crossResponse(V3 source,V3 direction,V3 target,V3 targetDirection);
    }
    public static ContactSolver.Body suspension(RigidBody host,RigidBody hub,V3 axis) {
        return new ContactSolver.Body() {
            public V3 velocity(V3 point) {
                return host.velocity(point).add(axis.mul(hub.velocity(hub.center()).sub(host.velocity(hub.center())).dot(axis)));
            }
            public double inverseMass(V3 point,V3 n) {
                double a=n.dot(axis);
                return Math.max(1e-9,host.inverseMass(point,n)-2*a*host.crossResponse(point,n,hub.center(),axis)
                    +a*a*(host.inverseMass(hub.center(),axis)+hub.inverseMass()));
            }
            public void impulse(V3 point,V3 j) {
                V3 suspension=axis.mul(j.dot(axis));
                host.impulse(point,j);host.impulse(hub.center(),suspension.mul(-1));hub.impulse(hub.center(),suspension);
            }
        };
    }
    public static ContactSolver.Body blend(ContactSolver.Body first,ContactSolver.Body second,double secondWeight) {
        if(first==second||secondWeight<1e-9)return first;if(secondWeight>1-1e-9)return second;
        double a=1-secondWeight,b=secondWeight;
        return new ContactSolver.Body() {
            public V3 velocity(V3 p) { return first.velocity(p).mul(a).add(second.velocity(p).mul(b)); }
            // Supports share the chassis: this conservative bound under-relaxes instead of counting shared inertia twice.
            public double inverseMass(V3 p,V3 n) { return a*first.inverseMass(p,n)+b*second.inverseMass(p,n); }
            public void impulse(V3 p,V3 j) { first.impulse(p,j.mul(a));second.impulse(p,j.mul(b)); }
        };
    }
    private LoadRouting() {}
}
