package dev.trackssimulate.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.*;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.*;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.trackssimulate.wheel.WheelBlockEntity;
import dev.trackssimulate.wheel.WheelGeometry;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Vector3d;
import org.joml.Quaterniond;
import java.util.*;
import static dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis.*;

/** Native contacts and a prismatic spring joint. No raycast support force or per-frame teleport. */
public final class RoadWheelPhysics {
    private static final Map<ServerLevel,World> WORLDS=new IdentityHashMap<>();
    private static final int MAX_PER_BODY=64;

    public static void tick(WheelBlockEntity wheel,ServerSubLevel host) {
        if(wheel.beltContactActive()) {
            release(wheel);
            wheel.initializeBeltSuspension();
            wheel.physicsSample(wheel.suspensionEnabled()?wheel.renderExtension(1):0,"带面悬挂运行中（与接触统一求解）");
            return;
        }
        wheel.resetBeltSuspension();
        if(!wheel.roadColliderEnabled()||host.isRemoved()) { release(wheel);return; }
        ServerLevel level=(ServerLevel)host.getLevel();
        World world=WORLDS.get(level);
        if(world==null) return;
        if(host.logicalPose().scale().distanceSquared(new Vector3d(1))>1e-8) {
            world.remove(wheel);wheel.physicsSample(0,"暂不支持缩放车体的悬挂");return;
        }
        Rig rig=world.rigs.get(wheel);
        if(rig!=null&&(rig.host!=host||!rig.box.isActive()||!rig.settings.equals(wheel.suspensionSettings())
            ||!rig.geometry.equals(wheel.geometry())||rig.suspended!=wheel.suspensionEnabled()||rig.axis!=wheel.axis()||rig.belted!=wheel.beltContactActive())) {
            world.remove(wheel);rig=null;
        }
        if(rig==null) {
            long count=world.rigs.values().stream().filter(r->r.host==host).count();
            if(count>=MAX_PER_BODY) { wheel.physicsSample(0,"同车体负重轮物理上限为 64 个");return; }
            rig=new Rig(world,wheel,host);
            world.rigs.put(wheel,rig);
            try { rig.create(); }
            catch(RuntimeException ex) {
                world.remove(wheel);
                Sable.LOGGER.error("TracksSimulate could not create suspension at {}",wheel.getBlockPos(),ex);
                wheel.physicsSample(0,"悬挂创建失败，关闭再开启可重试");wheel.physicsFailed();return;
            }
        }
        rig.box.updatePose();
        Vector3d local=host.logicalPose().transformPositionInverse(rig.box.getPose().position(),new Vector3d());
        double extension=rig.suspended?wheel.getBlockPos().getY()+.5+wheel.anchorOffset().y-local.y:0;
        if(!Double.isFinite(extension)||Math.abs(extension)>8) {
            world.remove(wheel);wheel.physicsSample(0,"悬挂求解异常，关闭再开启可重试");wheel.physicsFailed();return;
        }
        // Report the solver result, including small limit error; do not hide penetration with a visual clamp.
        wheel.physicsSample(extension,rig.suspended?"物理承载运行中（盒形接触）":"固定轮物理承载（盒形接触）");
    }
    public static void release(WheelBlockEntity wheel) {
        if(wheel.getLevel() instanceof ServerLevel level) {
            World world=WORLDS.get(level);if(world!=null) world.remove(wheel);
        }
    }
    public static void stopped(ServerStoppedEvent event) { WORLDS.clear(); }
    static dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject contactBody(WheelBlockEntity wheel,ServerSubLevel host) {
        World world=WORLDS.get(host.getLevel());Rig rig=world==null?null:world.rigs.get(wheel);
        if(rig==null||rig.host!=host||!rig.box.isActive()) return null;
        rig.box.updatePose();
        Vector3d local=host.logicalPose().transformPositionInverse(rig.box.getPose().position(),new Vector3d());
        wheel.physicsContactExtension(rig.suspended?wheel.getBlockPos().getY()+.5+wheel.anchorOffset().y-local.y:0);
        return rig.box;
    }
    public static void beforeServerTick(ServerTickEvent.Pre event) {
        // Register observers before Sable iterates them; actor callbacks run inside that iteration.
        for(ServerLevel level:event.getServer().getAllLevels()) if(SubLevelPhysicsSystem.get(level)!=null)
            WORLDS.computeIfAbsent(level,World::new);
    }
    public static void unloaded(LevelEvent.Unload event) {
        // The observer still receives Sable's close callbacks; only drop our static level reference here.
        if(event.getLevel() instanceof ServerLevel level) WORLDS.remove(level);
    }

    private static final class World implements SubLevelObserver {
        final SubLevelPhysicsSystem system;
        final Map<WheelBlockEntity,Rig> rigs=new IdentityHashMap<>();
        World(ServerLevel level) {
            system=SubLevelPhysicsSystem.require(level);
            Objects.requireNonNull(SubLevelContainer.getContainer(level)).addObserver(this);
        }
        void remove(WheelBlockEntity wheel) {
            Rig rig=rigs.remove(wheel);if(rig!=null) system.removeObject(rig.box);
        }
        @Override public void onSubLevelRemoved(SubLevel host,SubLevelRemovalReason reason) {
            // Sable closes all sub-levels before disposing the native scene, including world shutdown.
            for(Rig rig:List.copyOf(rigs.values())) if(rig.host==host) remove(rig.wheel);
        }
        @Override public void tick(SubLevelContainer container) {
            for(Rig rig:List.copyOf(rigs.values())) if(rig.wheel.isRemoved()||rig.host.isRemoved()
                ||Sable.HELPER.getContaining(rig.wheel)!=rig.host||!rig.wheel.roadColliderEnabled()) remove(rig.wheel);
        }
    }
    private static final class Rig {
        final World world;final WheelBlockEntity wheel;final ServerSubLevel host;
        final SuspensionSettings settings;final WheelBox box;
        final WheelGeometry geometry;final boolean suspended,belted;final Direction.Axis axis;
        final Map<Rig,GenericConstraintHandle> exclusions=new IdentityHashMap<>();
        GenericConstraintHandle spring;
        Rig(World world,WheelBlockEntity wheel,ServerSubLevel host) {
            this.world=world;this.wheel=wheel;this.host=host;settings=wheel.suspensionSettings();
            geometry=wheel.geometry();suspended=wheel.suspensionEnabled();axis=wheel.axis();belted=wheel.beltContactActive();
            var pos=wheel.getBlockPos();
            var offset=wheel.anchorOffset();
            Vector3d centre=host.logicalPose().transformPosition(new Vector3d(pos.getX()+.5+offset.x,pos.getY()+.5+offset.y-wheel.suspensionStart(),pos.getZ()+.5+offset.z));
            Pose3d pose=new Pose3d(centre,new Quaterniond(host.logicalPose().orientation()),new Vector3d(),new Vector3d(1));
            var size=geometry.halfExtents(axis.ordinal(),wheel.kind().radius,wheel.kind().width);
            // A belted wheel retains a small hub; the contact strips carry the outer surface.
            box=new WheelBox(pose,belted?new Vector3d(Math.min(.12,size.x()),Math.min(.12,size.y()),Math.min(.12,size.z())):new Vector3d(size.x(),size.y(),size.z()),this);
        }
        void create() {
            var pipeline=world.system.getPipeline();world.system.addObject(box);
            var pos=wheel.getBlockPos();
            var offset=wheel.anchorOffset();
            // Host anchors are plot coordinates; arbitrary-box anchors are local to its centre.
            spring=Objects.requireNonNull(pipeline.addConstraint(host,box,new GenericConstraintConfiguration(
                new Vector3d(pos.getX()+.5+offset.x,pos.getY()+.5+offset.y,pos.getZ()+.5+offset.z),new Vector3d(),new Quaterniond(),new Quaterniond(),
                suspended?EnumSet.of(LINEAR_X,LINEAR_Z,ANGULAR_X,ANGULAR_Y,ANGULAR_Z):EnumSet.allOf(ConstraintJointAxis.class))),"Sable generic joints unavailable");
            spring.setContactsEnabled(false);
            if(suspended) {
                spring.setLimit(LINEAR_Y,settings.jointMinimum(),settings.jointMaximum());
                spring.setMotor(LINEAR_Y,settings.jointRest(),settings.stiffness(),settings.damping(),false,0);
            }
            // Overlapping wheels on the same vehicle must not push each other apart.
            for(Rig other:world.rigs.values()) if(other!=this&&other.host==host&&other.box.isActive()) {
                GenericConstraintHandle filter=Objects.requireNonNull(pipeline.addConstraint(box,other.box,
                    new GenericConstraintConfiguration(new Vector3d(),new Vector3d(),new Quaterniond(),new Quaterniond())));
                filter.setContactsEnabled(false);exclusions.put(other,filter);other.exclusions.put(this,filter);
            }
            Vector3d angular=pipeline.getAngularVelocity(host,new Vector3d());
            Vector3d radius=new Vector3d(box.getPose().position()).sub(host.logicalPose().position());
            Vector3d linear=new Vector3d(angular).cross(radius).add(pipeline.getLinearVelocity(host,new Vector3d()));
            pipeline.addLinearAndAngularVelocity(box,linear,angular);
            pipeline.wakeUp(host);
        }
        void removeConstraints() {
            if(spring!=null) { spring.remove();spring=null; }
            for(var entry:List.copyOf(exclusions.entrySet())) {
                entry.getValue().remove();entry.getKey().exclusions.remove(this);
            }
            exclusions.clear();
        }
    }
    private static final class WheelBox extends BoxPhysicsObject {
        final Rig rig;
        WheelBox(Pose3d pose,Vector3d halfExtents,Rig rig) { super(pose,halfExtents,rig.settings.mass());this.rig=rig; }
        @Override protected void remove() {
            rig.removeConstraints();
            // The holding-chunk callback and the owner may both request removal.
            if(handle!=null) super.remove();
        }
        @Override public void wakeUp() { if(handle!=null) super.wakeUp(); }
        @Override public dev.ryanhcode.sable.api.physics.mass.MassData getMassTracker() {
            var h=getHalfExtents();double m=getMass();
            org.joml.Matrix3d tensor=new org.joml.Matrix3d().zero().m00(m*(h.y()*h.y()+h.z()*h.z())/3)
                .m11(m*(h.x()*h.x()+h.z()*h.z())/3).m22(m*(h.x()*h.x()+h.y()*h.y())/3);
            org.joml.Matrix3d inverse=tensor.invert(new org.joml.Matrix3d());
            return new dev.ryanhcode.sable.api.physics.mass.MassData() {
                public double getMass(){return m;} public double getInverseMass(){return 1/m;}
                public org.joml.Matrix3dc getInertiaTensor(){return tensor;} public org.joml.Matrix3dc getInverseInertiaTensor(){return inverse;}
                public org.joml.Vector3dc getCenterOfMass(){return new Vector3d();}
            };
        }
    }
    private RoadWheelPhysics() {}
}
