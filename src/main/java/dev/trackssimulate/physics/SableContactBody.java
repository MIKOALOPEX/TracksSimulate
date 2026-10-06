package dev.trackssimulate.physics;

import dev.ryanhcode.sable.api.physics.*;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.trackssimulate.physics.contact.*;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** World-space shadow velocities; flush accumulated impulses once per substep into Sable's local-space API. */
final class SableContactBody implements LoadRouting.RigidBody {
    final PhysicsPipelineBody body;
    final V3 center;
    final Quaterniond rotation;
    final Matrix3d inverseInertia;
    final double inverseMass;
    final V3 initialLinear,initialAngular;
    V3 linear,angular,impulse=V3.ZERO,torque=V3.ZERO;
    SableContactBody(PhysicsPipeline pipeline,PhysicsPipelineBody body,Pose3dc pose) {
        this.body=body;center=of(pose.position());rotation=new Quaterniond(pose.orientation());
        var mass=body.getMassTracker();inverseMass=mass.getInverseMass();inverseInertia=new Matrix3d(mass.getInverseInertiaTensor());
        linear=of(pipeline.getLinearVelocity(body,new Vector3d()));angular=of(pipeline.getAngularVelocity(body,new Vector3d()));
        initialLinear=linear;initialAngular=angular;
    }
    static V3 of(org.joml.Vector3dc v) { return new V3(v.x(),v.y(),v.z()); }
    static Vector3d joml(V3 v) { return new Vector3d(v.x(),v.y(),v.z()); }
    private V3 inverseTorque(V3 world) {
        Vector3d local=rotation.transformInverse(joml(world));inverseInertia.transform(local);rotation.transform(local);return of(local);
    }
    public V3 velocity(V3 point) { return linear.add(angular.cross(point.sub(center))); }
    public double inverseMass(V3 point,V3 direction) {
        V3 cross=point.sub(center).cross(direction);
        return inverseMass+cross.dot(inverseTorque(cross));
    }
    public double coupling(V3 point,V3 a,V3 b) {return crossResponse(point,a,point,b);}
    public void impulse(V3 point,V3 change) {
        V3 turn=point.sub(center).cross(change);
        impulse=impulse.add(change);torque=torque.add(turn);
        linear=linear.add(change.mul(inverseMass));angular=angular.add(inverseTorque(turn));
    }
    void flush(PhysicsPipeline pipeline) {
        if(impulse.dot(impulse)+torque.dot(torque)<1e-12||body.isRemoved()) return;
        pipeline.applyLinearAndAngularImpulse(body,rotation.transformInverse(joml(impulse)),rotation.transformInverse(joml(torque)),true);
    }
    public V3 center() { return center; }
    public double inverseMass() { return inverseMass; }
    public double crossResponse(V3 source,V3 direction,V3 target,V3 targetDirection) {
        V3 response=direction.mul(inverseMass).add(inverseTorque(source.sub(center).cross(direction)).cross(target.sub(center)));
        return response.dot(targetDirection);
    }
}
