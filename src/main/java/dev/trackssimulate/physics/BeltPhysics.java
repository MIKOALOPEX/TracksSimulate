package dev.trackssimulate.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.companion.math.*;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.trackssimulate.physics.contact.*;
import dev.trackssimulate.track.TrackPath;
import dev.trackssimulate.wheel.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.Block;
import org.joml.Vector3d;
import java.util.*;

/** Reduced belt: deforming two-point contact strips and a shared material-speed DOF.
 * Contact impulses enter Sable's real rigid bodies; no displacement/velocity teleportation.
 * Stretch/tension, loose links and damage remain separate future work.
 */
public final class BeltPhysics {
    private static final Map<ServerSubLevel,Vehicle> VEHICLES=new IdentityHashMap<>();
    private static final class Vehicle {
        final Set<WheelBlockEntity> roots=new LinkedHashSet<>();long tick=Long.MIN_VALUE;double substep=-1;
        final PhysicsDiagnostics.State diagnostics=new PhysicsDiagnostics.State();
    }
    public static void register(WheelBlockEntity root,ServerSubLevel host) {VEHICLES.computeIfAbsent(host,h->new Vehicle()).roots.add(root);}
    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {VEHICLES.clear();}
    public static void unloaded(net.neoforged.neoforge.event.level.LevelEvent.Unload e) {VEHICLES.keySet().removeIf(h->h.getLevel()==e.getLevel());}
    private record Strip(ContactBox box,V3 tangent,ContactSolver.Body support) {}
    private record Obstacle(ContactBox box,ContactSolver.Contact.Source source) {}
    private record Cell(ServerSubLevel host,BlockPos pos) {}
    private static final class Frame {
        final ServerLevel level;final PhysicsPipeline pipeline;
        final IdentityHashMap<PhysicsPipelineBody,SableContactBody> bodies=new IdentityHashMap<>();
        final Map<Cell,List<Obstacle>> cells=new HashMap<>();
        int probes;
        Frame(ServerLevel level,PhysicsPipeline pipeline) { this.level=level;this.pipeline=pipeline; }
        SableContactBody body(PhysicsPipelineBody body,Pose3dc pose) { return bodies.computeIfAbsent(body,b->new SableContactBody(pipeline,b,pose)); }
        List<Obstacle> cell(ServerSubLevel host,BlockPos pos) {
            if(++probes>24000) throw new IllegalArgumentException("碰撞查询超过单环预算，请缩短履带");
            return cells.computeIfAbsent(new Cell(host,pos),key->{
                if(!level.hasChunkAt(pos)||(host!=null&&!host.getPlot().contains(new Vector3d(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)))) return List.of();
                var state=level.getBlockState(pos);
                if(state.isAir()) return List.of();
                int exposed=ContactBox.ALL_FACES;
                VoxelShape collision=shape(host,pos);
                if(collision.isEmpty())return List.of();
                if(Block.isShapeFullBlock(collision)) {
                    for(Direction face:Direction.values()) {
                        BlockPos adjacent=pos.relative(face);
                        if(!level.hasChunkAt(adjacent)||(host!=null&&!host.getPlot().contains(new Vector3d(adjacent.getX()+.5,adjacent.getY()+.5,adjacent.getZ()+.5))))continue;
                        if(Block.isShapeFullBlock(shape(host,adjacent)))exposed&=~faceBit(face);
                    }
                }
                List<Obstacle> result=new ArrayList<>();
                var source=new ContactSolver.Contact.Source(pos.getX(),pos.getY(),pos.getZ(),host==null?0:System.identityHashCode(host),host==null?0:host.getMassTracker().getMass(),exposed);
                for(AABB box:collision.toAabbs()) {
                    V3 center=new V3(pos.getX()+(box.minX+box.maxX)/2,pos.getY()+(box.minY+box.maxY)/2,pos.getZ()+(box.minZ+box.maxZ)/2);
                    V3 half=new V3(box.getXsize()/2,box.getYsize()/2,box.getZsize()/2);
                    Pose3dc pose=host==null?null:host.logicalPose();
                    result.add(new Obstacle(new ContactBox(position(pose,center),normal(pose,V3.X),normal(pose,V3.Y),normal(pose,V3.Z),half),source));
                }
                return result;
            });
        }
        VoxelShape shape(ServerSubLevel host,BlockPos pos) {
            var state=level.getBlockState(pos);
            if(host!=null&&state.getBlock() instanceof BlockSubLevelCollisionShape custom)return custom.getSubLevelCollisionShape(level,state);
            return state.getCollisionShape(level,pos,CollisionContext.empty());
        }
        private static int faceBit(Direction face) {return switch(face){case EAST->ContactBox.POS_X;case WEST->ContactBox.NEG_X;case UP->ContactBox.POS_Y;case DOWN->ContactBox.NEG_Y;case SOUTH->ContactBox.POS_Z;case NORTH->ContactBox.NEG_Z;};}
    }
    public static void step(WheelBlockEntity root,ServerSubLevel host,double dt) {
        if(!(dt>0)||dt>.1||host.isRemoved()||!root.beltContactActive()) return;
        VEHICLES.keySet().removeIf(ServerSubLevel::isRemoved);
        register(root,host);Vehicle vehicle=VEHICLES.get(host);
        var system=SubLevelPhysicsSystem.require(host.getLevel());double substep=system.getPartialPhysicsTick();long tick=host.getLevel().getGameTime();
        if(vehicle.tick==tick&&vehicle.substep==substep)return;
        vehicle.tick=tick;vehicle.substep=substep;
        vehicle.roots.removeIf(w->w.isRemoved()||!w.controller()||Sable.HELPER.getContaining(w)!=host);
        try {
            Frame frame=new Frame((ServerLevel)host.getLevel(),system.getPipeline());
            Map<WheelBlockEntity,BeltSuspension> suspension=new LinkedHashMap<>();
            Map<WheelBlockEntity,ContactSolver.Group> groups=new LinkedHashMap<>();
            var active=vehicle.roots.stream().filter(WheelBlockEntity::beltContactActive).sorted(Comparator.comparingLong(w->w.getBlockPos().asLong())).toList();
            for(var controller:active) {
                var group=prepare(controller,host,dt,frame,suspension,active.size());if(group!=null)groups.put(controller,group);
            }
            ContactSolver.solveGroups(new ArrayList<>(groups.values()),dt);
            for(var body:frame.bodies.values()) if(!Double.isFinite(body.impulse.length())||!Double.isFinite(body.torque.length()))throw new IllegalArgumentException("接触冲量异常");
            for(var group:groups.values()) if(!Double.isFinite(group.belt().speed)||Math.abs(group.belt().speed)>256)throw new IllegalArgumentException("履带速度异常");
            for(var body:frame.bodies.values())body.flush(frame.pipeline);
            suspension.forEach(WheelBlockEntity::beltSuspensionSample);
            groups.forEach((controller,group)->controller.beltSample(group.belt().speed,dt,group.contacts().size(),group.contacts().stream().mapToDouble(c->c.normalImpulse).sum()/dt));
            PhysicsDiagnostics.sample(vehicle.diagnostics,host,dt,frame.bodies.get(host),groups,suspension);
        }
        catch(RuntimeException ex) {
            PhysicsDiagnostics.failed(vehicle.diagnostics,host,dt,ex);
            for(var controller:vehicle.roots)controller.beltFailed(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());
            Sable.LOGGER.error("TracksSimulate belt contact disabled at {}",root.getBlockPos(),ex);
        }
    }
    private static ContactSolver.Group prepare(WheelBlockEntity root,ServerSubLevel host,double dt,Frame frame,Map<WheelBlockEntity,BeltSuspension> suspension,int loopCount) {
        frame.probes=0; // Query budget remains per loop, though the response is solved per vehicle.
        if(host.logicalPose().scale().distanceSquared(new Vector3d(1))>1e-8) throw new IllegalArgumentException("暂不支持缩放车体");
        var system=SubLevelPhysicsSystem.require(host.getLevel());
        SableContactBody chassis=frame.body(host,host.logicalPose());
        V3 springAxis=normal(host.logicalPose(),V3.Y);
        List<ContactSolver.Body> supports=new ArrayList<>();List<TrackPath.Wheel> wheels=new ArrayList<>();
        for(BlockPos pos:root.nodes()) {
            if(!frame.level.hasChunkAt(pos)||!(frame.level.getBlockEntity(pos) instanceof WheelBlockEntity w)
                ||Sable.HELPER.getContaining(w)!=host||!Objects.equals(root.loopId(),w.loopId())) return null;
            ContactSolver.Body support=chassis;
            if(w.kind()==WheelKind.ROAD) {
                if(w.suspensionEnabled()) {var spring=w.beltSuspension(chassis,springAxis,dt);suspension.put(w,spring);support=spring;}
                else w.physicsContactExtension(0);
            }
            supports.add(support);wheels.add(w.pathWheel(root.getBlockPos(),1,0));
        }
        root.beltReady();
        var path=root.path();if(path==null) return null;
        var spans=TrackPath.contactSpans(wheels,path.wheelDirections(),.45);
        var settings=root.beltSettings();
        double halfWidth=root.beltWidth()/2;
        V3 axle=normal(host.logicalPose(),switch(root.axis()){case X->V3.X;case Y->V3.Y;case Z->V3.Z;});
        List<Strip> strips=new ArrayList<>();AABB total=null;
        for(var span:spans) {
            V3 a=point(root,host.logicalPose(),span.start()),b=point(root,host.logicalPose(),span.end());
            V3 tangent=b.sub(a).unit();
            ContactBox box=settings.contactBox(a,b,axle,halfWidth);
            strips.add(new Strip(box,tangent,LoadRouting.blend(supports.get(span.first()),supports.get(span.second()),span.secondWeight())));
            AABB bounds=bounds(box,settings.predictionDistance());total=total==null?bounds:total.minmax(bounds);
        }
        if(total==null) return null;
        List<ServerSubLevel> others=new ArrayList<>();
        for(var other:system.queryIntersecting(new BoundingBox3d(total.minX,total.minY,total.minZ,total.maxX,total.maxY,total.maxZ))) {
            if(other instanceof ServerSubLevel body&&body!=host&&!body.isRemoved()&&body.logicalPose().scale().distanceSquared(new Vector3d(1))<1e-8) others.add(body);
        }
        if(others.size()>16) throw new IllegalArgumentException("接触车体过多");
        List<ContactSolver.Contact> contacts=new ArrayList<>();
        for(Strip strip:strips) {
            double margin=Math.min(settings.predictionDistance(),.005+strip.support.velocity(strip.box.center()).length()*dt);
            gather(frame,strip,null,margin,contacts,settings.sideGrip());
            for(var other:others) gather(frame,strip,other,margin,contacts,settings.sideGrip());
        }
        double vehicleMass=1/chassis.inverseMass,mass=settings.equivalentMass(vehicleMass,loopCount);
        var belt=new ContactSolver.Belt(root.speedForMass(mass),mass);
        belt.driveForceLimit=settings.effectiveDriveForce(vehicleMass,loopCount);
        belt.targetSpeed=settings.limitedSpeed(root.driveTarget());
        double beforeMotor=belt.speed;
        if(root.driven()&&!root.conflict()) belt.motor(belt.targetSpeed,belt.driveForceLimit,dt);
        belt.motorImpulse=(belt.speed-beforeMotor)*mass;
        double brakeForce=vehicleMass/Math.max(1,loopCount)*settings.maxAcceleration()*3;
        double beforeDrag=belt.speed;
        belt.motor(0,Math.min(settings.rollingDrag(),brakeForce)+(root.conflict()?1:settings.brake())*brakeForce,dt);
        belt.dragImpulse=(belt.speed-beforeDrag)*mass;belt.speedBeforeContacts=belt.speed;
        return new ContactSolver.Group(contacts,belt,settings.friction(),settings.lateralFriction(),settings.contactSoftness(),settings.recoverySpeed(),settings.lateralSlipSpeed());
    }
    private static void gather(Frame frame,Strip strip,ServerSubLevel other,double margin,List<ContactSolver.Contact> contacts,double sideGrip) {
        AABB query=bounds(strip.box,margin);
        if(other!=null) {
            V3 min=new V3(Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY),max=min.mul(-1);
            for(int i=0;i<8;i++) {
                Vector3d p=other.logicalPose().transformPositionInverse(new Vector3d((i&1)==0?query.minX:query.maxX,(i&2)==0?query.minY:query.maxY,(i&4)==0?query.minZ:query.maxZ));
                min=new V3(Math.min(min.x(),p.x),Math.min(min.y(),p.y),Math.min(min.z(),p.z));max=new V3(Math.max(max.x(),p.x),Math.max(max.y(),p.y),Math.max(max.z(),p.z));
            }
            query=new AABB(min.x(),min.y(),min.z(),max.x(),max.y(),max.z());
        }
        for(int x=(int)Math.floor(query.minX)-1;x<=(int)Math.floor(query.maxX);x++)
            for(int y=(int)Math.floor(query.minY)-1;y<=(int)Math.floor(query.maxY);y++)
                for(int z=(int)Math.floor(query.minZ)-1;z<=(int)Math.floor(query.maxZ);z++) {
                    for(var obstacle:frame.cell(other,new BlockPos(x,y,z))) {
                        var hit=strip.box.surfaceContact(obstacle.box,margin,obstacle.source.exposedFaces());if(hit==null) continue;
                        ContactSolver.Body otherBody=other==null?null:frame.body(other,other.logicalPose());
                        var contact=new ContactSolver.Contact(strip.support,otherBody,hit.point(),hit.normal(),strip.tangent,hit.penetration(),obstacle.source);
                        double side=Math.min(1,Math.abs(hit.normal().dot(strip.box.y())));
                        contact.gripScale=1-side*(1-sideGrip);contacts.add(contact);
                        if(contacts.size()>2048) throw new IllegalArgumentException("接触点超过单环预算，请减少轮数或尺寸");
                    }
                }
    }
    private static AABB bounds(ContactBox box,double margin) {
        V3 e=box.extent().add(new V3(margin,margin,margin)),c=box.center();return new AABB(c.x()-e.x(),c.y()-e.y(),c.z()-e.z(),c.x()+e.x(),c.y()+e.y(),c.z()+e.z());
    }
    private static V3 point(WheelBlockEntity root,Pose3dc pose,TrackPath.Point p) {
        double axial=root.geometry().axial();V3 local=switch(root.axis()) {case X->new V3(axial,p.y(),p.x());case Y->new V3(p.x(),axial,p.y());case Z->new V3(p.x(),p.y(),axial);};
        var pos=root.getBlockPos();return position(pose,local.add(new V3(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)));
    }
    private static V3 position(Pose3dc pose,V3 point) { return pose==null?point:SableContactBody.of(pose.transformPosition(SableContactBody.joml(point))); }
    private static V3 normal(Pose3dc pose,V3 vector) { return pose==null?vector:SableContactBody.of(pose.transformNormal(SableContactBody.joml(vector))); }
    private BeltPhysics() {}
}
