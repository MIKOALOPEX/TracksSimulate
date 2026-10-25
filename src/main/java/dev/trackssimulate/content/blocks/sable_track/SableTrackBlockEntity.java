// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.blocks.sable_track;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.trackssimulate.TracksClient;
import dev.trackssimulate.assembly.TrackAssemblies;
import dev.trackssimulate.config.OffsetScope;
import dev.trackssimulate.config.TrackSetting;
import dev.trackssimulate.physics.SuspensionContactSolver;
import dev.trackssimulate.content.items.SuspensionKeyItem;
import dev.trackssimulate.index.TracksItems;
import dev.trackssimulate.network.SelectTrackTuningModePayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Collection;
import java.util.List;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public class SableTrackBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor, Clearable {
   private static final MutableComponent SCROLL_OPTION_TITLE = Component.translatable("tracks.scroll_option.track_suspension_strength");
   private static final double MAX_LATERAL_OFFSET = 1.0;
   private static final double LATERAL_OFFSET_STEP = 0.125;
   private static final double MAX_LONGITUDINAL_OFFSET = 1.0;
   private static final double LONGITUDINAL_OFFSET_STEP = 0.125;
   private static final double MAX_HEIGHT_OFFSET = 0.75;
   private static final double HEIGHT_OFFSET_STEP = 0.125;
   private static final double SLEEP_VELOCITY = 0.08;
   private static final double OFFROAD_SMALL_WHEEL_REST_DISTANCE = 0.65;
   private static final double TRACK_DRIVE_FORCE_SCALE = -0.45;
   private static final double TRACK_MAX_SPRING_IMPULSE_SCALE = 0.9;
   private static final double TRACK_BUMP_STOP_CLEARANCE_SCALE = 0.95;
   private static final double TRACK_BUMP_STOP_FORCE_SCALE = 4.0;
   private static final java.util.Map<ServerSubLevel, Integer> SUPPORT_COUNTS = new java.util.WeakHashMap<>();
   private static final java.util.Map<ServerSubLevel, SuspensionFrame> SUSPENSION_FRAMES = new java.util.WeakHashMap<>();
   private static final class SuspensionFrame {
      final SuspensionContactSolver.Body body=new SuspensionContactSolver.Body();
      final java.util.List<SableTrackBlockEntity> wheels=new java.util.ArrayList<>();
      final double inverseMass;
      final double[] inverseInertia;
      final Vector3d centerOfMass;
      boolean valid(){return inverseMass>0&&Double.isFinite(inverseMass)&&centerOfMass.isFinite()
         &&java.util.Arrays.stream(inverseInertia).allMatch(Double::isFinite);}
      SuspensionFrame(ServerSubLevel host) {
         var mass=host.getMassTracker();var inertia=mass.getInverseInertiaTensor();
         inverseMass=mass.getInverseMass();centerOfMass=new Vector3d(mass.getCenterOfMass());
         inverseInertia=new double[]{inertia.m00(),inertia.m10(),inertia.m20(),
            inertia.m01(),inertia.m11(),inertia.m21(),inertia.m02(),inertia.m12(),inertia.m22()};
      }
   }
   private SuspensionContactSolver.Contact pendingContact;
   private SubLevel pendingOther;
   private long diagnosticNanos;
   private final Vector3d queuedForcePos = new Vector3d();
   private final Vector3d queuedForce = new Vector3d();
   private SableTrackBlockEntity.TrackStrengthValueBehaviour strength;
   // Missing NBT uses the registered legacy part's original size.
   private double radiusOverride = Double.NaN;
   private double wheelWidth = 1.0;
   public double wheelRadius() { return Double.isFinite(radiusOverride) ? radiusOverride : effectivePart().radius(); }
   public double wheelWidth() { return wheelWidth; }
   private double extension = 0.0;
   private double targetExtension;
   private double lastSentExtension = Double.NaN;
   private boolean lastSentLifted;
   private int suspensionSyncTicks;
   private double lastExtension = this.extension;
   private double lateralOffset = 0.0;
   private double lastLateralOffset = this.lateralOffset;
   private double longitudinalOffset = 0.0;
   private double lastLongitudinalOffset = this.longitudinalOffset;
   private double heightOffset = 0.0;
   private double lastHeightOffset = this.heightOffset;
   private double lastAngle;
   private double angle;
   private double angularVelocity = 0.0;
   // Only assembly node zero owns passive material travel. No independent wheel phases.
   private boolean passiveMotion;
   private double passiveDistance,passiveSpeed,passiveRender,passiveLastRender,lastSentPassiveDistance=Double.NaN,lastSentPassiveSpeed=Double.NaN;
   private long passivePhysicsTick=-100,passiveSampleTick=-100,passiveReportTick=-100;
   private boolean lastSentPassive;
   public boolean passiveMotion(){return passiveMotion;}
   public double passiveDistance(float partial){return level.isClientSide?Mth.lerp(partial,passiveLastRender,passiveRender):passiveDistance;}
   public void receivePassiveMotion(java.util.UUID id,long tick,double distance,double speed,boolean active) {
      if(level==null||!level.isClientSide||beltIndex!=0||!id.equals(beltId)||tick<passiveSampleTick
          ||!Double.isFinite(distance)||!Double.isFinite(speed))return;
      if(!passiveMotion)passiveLastRender=passiveRender=distance;
      passiveMotion=active;passiveDistance=distance;passiveSpeed=speed;passiveSampleTick=tick;
   }
   private double touchingFriction = 1.0;
   private double springMultiplier = 1.0;
   private double dampingMultiplier = 1.0;
   private double bumpClearanceMultiplier = 1.0;
   private double bumpForceMultiplier = 1.0;
   private double maxImpulseMultiplier = 1.0;
   private double maxExtensionOverride=Double.NaN, maxCompression=.45;
   private double contactSpread=1, contactMargin=.25, forwardFriction=1, sideFriction=1;
   public double maximumExtension(){return effectiveRole()==SableTrackRole.SUSPENSION
      ?(Double.isFinite(maxExtensionOverride)?maxExtensionOverride:effectivePart().suspensionTravel()):0;}
   public double maximumCompression(){return effectiveRole()==SableTrackRole.SUSPENSION?maxCompression:0;}
   private boolean travelMigrationPending;
   public double minimumExtension(){return effectiveRole()==SableTrackRole.SUSPENSION?maximumExtension()-maxCompression:0;}
   private dev.trackssimulate.physics.SuspensionLimits suspensionLimits(){return new dev.trackssimulate.physics.SuspensionLimits(maximumExtension(),maxCompression,-heightOffset);}
   public void validateTravelSettings(java.util.Map<TrackSetting,Double> values) {
      if(effectiveRole()!=SableTrackRole.SUSPENSION)return;
      double e=values.getOrDefault(TrackSetting.MAX_EXTENSION,maximumExtension());
      double c=values.getOrDefault(TrackSetting.MAX_COMPRESSION,maxCompression);
      double height=values.getOrDefault(TrackSetting.HEIGHT,heightOffset);
      try {new dev.trackssimulate.physics.SuspensionLimits(e,c,-height);}
      catch(IllegalArgumentException invalid) {
         throw new IllegalArgumentException("轮 "+getBlockPos()+"：最大压缩必须满足 0 ≤ 压缩 ≤ 向下偏移＋最大伸长（当前允许 "+TrackSetting.format(e-height)
            +" 格）；可同时编辑偏移/伸长/压缩，再点同步提交全部输入。本次未修改。");
      }
   }
   public double defaultSettingValue(TrackSetting setting){
      if(effectiveRole()==SableTrackRole.SUSPENSION) {
         double e=Math.max(effectivePart().suspensionTravel(),heightOffset);
         if(setting==TrackSetting.MAX_EXTENSION)return e;
         if(setting==TrackSetting.MAX_COMPRESSION)return Math.min(setting.defaultValue,e-heightOffset);
      }
      return setting.defaultValue;
   }
   private java.util.UUID beltId;
   private int beltIndex;
   private boolean settingsConfigured;
   private Player removalRecipient;
   private long removalTick;
   public void removalRecipient(Player player){removalRecipient=player;removalTick=level==null?0:level.getGameTime();}
   public Player removalRecipient(){return level!=null&&removalTick==level.getGameTime()?removalRecipient:null;}
   public boolean settingsConfigured(){return settingsConfigured;}
   /** One validated transaction writes each member once; never propagates implicitly. */
   public void applyLocalSettings(java.util.Map<TrackSetting,Double> values) {
      validateTravelSettings(values);
      boolean changed=false;
      for(var entry:values.entrySet()) {
         var setting=entry.getKey();double v=entry.getValue();
         if(!setting.supports(effectiveRole()==SableTrackRole.SUSPENSION,effectiveRole()==SableTrackRole.DRIVE)||!setting.accepts(v))continue;
         if(Math.abs(getSettingValue(setting.key)-v)<1e-9)continue;
         switch(setting) {
            case STRENGTH -> {if(strength!=null)strength.value=(int)v;lastPropagatedStrength=protectedStrengthValue=(int)v;}
            case SPRING -> springMultiplier=v;
            case DAMPING -> dampingMultiplier=v;
            case BUMP_CLEARANCE -> bumpClearanceMultiplier=v;
            case BUMP_FORCE -> bumpForceMultiplier=v;
            case MAX_IMPULSE -> maxImpulseMultiplier=v;
            case DRIVE -> driveMultiplier=v;
            case GRIP -> gripMultiplier=v;
            case LATERAL -> lateralOffset=v;
            case LONGITUDINAL -> longitudinalOffset=v;
            case HEIGHT -> heightOffset=v;
            case SCALE -> radiusOverride=TrackSetting.scaledRadius(effectivePart().radius(),v);
            case WIDTH -> wheelWidth=v;
            case HIDDEN -> visualSuspensionHidden=v==1;
            case WHEEL_HIDDEN -> wheelHidden=v==1;
            case CONNECTED_CONTACTS -> connectedContacts=v==1;
            case SPEED -> speedMultiplier=v;
            case MAX_EXTENSION -> maxExtensionOverride=v;
            case MAX_COMPRESSION -> maxCompression=v;
            case CONTACT_SPREAD -> contactSpread=v;
            case CONTACT_MARGIN -> contactMargin=v;
            case FRICTION_FORWARD -> forwardFriction=v;
            case FRICTION_SIDE -> sideFriction=v;
            case THROUGH_AXLE -> {
               if(level!=null&&!level.isClientSide)KineticBlockEntity.switchToBlockState(level,worldPosition,getBlockState()
                  .setValue(SableTrackBlock.AXLE_MODE,(int)v).setValue(SableTrackBlock.THROUGH_AXLE,v==1));
            }
         }
         changed=true;
      }
      if(changed){
         if(effectiveRole()==SableTrackRole.SUSPENSION) {
            var limits=suspensionLimits();extension=limits.clamp(extension);targetExtension=limits.clamp(targetExtension);lastExtension=limits.clamp(lastExtension);
         }
         settingsConfigured=true;setChanged();invalidateRenderBoundingBox();sendData();
      }
   }

   public java.util.UUID beltId() { return beltId; }
   public int beltIndex() { return beltIndex; }
   public void setBelt(java.util.UUID id,int index) {
      if(!java.util.Objects.equals(id,beltId)){passiveMotion=false;passiveSpeed=passiveDistance=passiveRender=passiveLastRender=0;lastSentPassiveDistance=Double.NaN;}
      beltId=id;beltIndex=index;setChanged();invalidateRenderBoundingBox();
      if(level!=null&&!level.isClientSide)sendData();
   }

   private net.minecraft.world.level.block.state.BlockState material=com.simibubi.create.AllBlocks.COPYCAT_BASE.getDefaultState();
   private ItemStack materialItem=ItemStack.EMPTY;
   private boolean wheelHidden;
   private boolean connectedContacts;
   public boolean connectedContacts(){return connectedContacts;}
   private double speedMultiplier=1;
   public boolean hasMaterial(){return !material.is(com.simibubi.create.AllBlocks.COPYCAT_BASE.get());}
   public net.minecraft.world.level.block.state.BlockState material(){return material;}
   public ItemStack materialItem(){return materialItem.copy();}
   public void setMaterial(net.minecraft.world.level.block.state.BlockState state,ItemStack item){
      material=state;materialItem=item.isEmpty()?ItemStack.EMPTY:item.copyWithCount(1);setChanged();
      if(level!=null&&!level.isClientSide){sendData();dev.trackssimulate.Tracks.LOGGER.info("[TS-MATERIAL] pos={} material={} stored={}",getBlockPos(),state,!materialItem.isEmpty());}
   }
   public boolean isWheelHidden(){return wheelHidden;}
   public double speedMultiplier(){return speedMultiplier;}

   // Transient graph-edit state; never serialized or sent as user configuration.
   public boolean beltKineticsMuted;
   public java.util.List<BlockPos> detachingBeltPeers=java.util.List.of();
   public float kineticStress(){return stress;}
   public float kineticCapacity(){return capacity;}
   @Override public java.util.List<BlockPos> addPropagationLocations(com.simibubi.create.content.kinetics.base.IRotate block,
         BlockState state,java.util.List<BlockPos> neighbours) {
      return dev.trackssimulate.assembly.TrackDriveNetwork.locations(this,super.addPropagationLocations(block,state,neighbours));
   }
   @Override public float propagateRotationTo(KineticBlockEntity target,BlockState from,BlockState to,BlockPos diff,
         boolean axes,boolean cogs) {
      return dev.trackssimulate.assembly.TrackDriveNetwork.connected(this,target)?1:super.propagateRotationTo(target,from,to,diff,axes,cogs);
   }
   @Override public boolean isCustomConnection(KineticBlockEntity other,BlockState state,BlockState otherState) {
      return dev.trackssimulate.assembly.TrackDriveNetwork.connected(this,other)||super.isCustomConnection(other,state,otherState);
   }

   private double driveMultiplier = 1.0;
   private double gripMultiplier = 1.0;
   private int lastPropagatedStrength = 16;
   private int protectedStrengthValue = 16;
   private String scrollTuningKey = "strength";
   private boolean liftedUp = false;
   private boolean visualSuspensionHidden = false;
   private DyeColor beltColor = null;
   private ItemStack heldItem = ItemStack.EMPTY;

   public SableTrackBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
      super(type, pos, state);
      if (state.getBlock() instanceof SableTrackBlock block) {
         this.extension = block.builtInPart().suspensionTravel();
         this.lastExtension = this.targetExtension = this.extension;
      }
   }

   private record ContactLoad(ServerSubLevel host,Vec3 point,SubLevel other,Vec3 otherPoint,
         SableTrackBlockEntity root,SableTrackBlockEntity source,SuspensionContactSolver.Contact contact,double rolling,boolean surface) {}

   public static void applyAllBatchedForces(ServerLevel level, double timeStep) {
      dev.trackssimulate.physics.ConnectedBodies.maintain(level);
      dev.trackssimulate.physics.SurfaceContacts.prune(level);
      var frames=new java.util.LinkedHashMap<ServerSubLevel,SuspensionFrame>();
      var iterator=SUSPENSION_FRAMES.entrySet().iterator();
      while(iterator.hasNext()) {
         var e=iterator.next();if(e.getKey().isRemoved()){iterator.remove();continue;}
         if(e.getKey().getLevel()!=level)continue;
         if(e.getValue().valid())frames.put(e.getKey(),e.getValue());iterator.remove();
      }
      var active=new java.util.ArrayList<>(frames.entrySet());
      var loads=new java.util.ArrayList<ContactLoad>();
      var batches=new java.util.IdentityHashMap<ServerSubLevel,dev.trackssimulate.physics.SurfaceContacts.Batch>();
      var passive=new java.util.LinkedHashMap<SableTrackBlockEntity,SuspensionContactSolver.FreeBelt>();
      for(var entry:active) {
         var host=entry.getKey();var frame=entry.getValue();
         frame.wheels.removeIf(w->w.isRemoved()||Sable.HELPER.getContaining(w)!=host);
         frame.wheels.sort(java.util.Comparator.comparingLong(w->w.getBlockPos().asLong()));
         var batch=dev.trackssimulate.physics.SurfaceContacts.collect(host,frame.wheels,timeStep);batches.put(host,batch);
         // Wide wheel/belt samples supplement the narrow suspension cast, never add a
         // second rigid row in parallel with it. One spring and one stop per wheel.
         for(var support:batch.supports()) {
            var wheel=support.wheel();
            if(support.rawExtension()<wheel.diagnosticRawExtension) {
               long supplementStart=System.nanoTime();
               wheel.applySuspensionTerrain(host,timeStep,frame,new TerrainCastResult(
                  support.rawExtension()+wheel.wheelRadius(),support.normal(),support.other(),support.block(),support.point()));
               wheel.diagnosticNanos+=System.nanoTime()-supplementStart;
            }
         }
         for(var wheel:frame.wheels)if(wheel.pendingContact!=null) {
            var members=TrackAssemblies.members(wheel);var a=TrackAssemblies.get(wheel);
            var point=JOMLConversion.toMojang(wheel.queuedForcePos);
            loads.add(new ContactLoad(host,point,wheel.pendingOther,oppositePoint(host,point,wheel.pendingOther),
               members.getFirst(),wheel,wheel.pendingContact,a==null?1:a.sides().get(wheel.beltIndex),false));
         }
         for(var hit:batch.contacts()) {
            var w=hit.source();var point=hit.point();var n=JOMLConversion.toJOML(hit.normal());
            var tangent=JOMLConversion.toJOML(hit.tangent());tangent.fma(-tangent.dot(n),n);
            double projection=Math.min(1,tangent.length());
            if(projection<1e-6)tangent.set(Math.abs(n.y)<.9?new Vector3d(0,1,0):new Vector3d(1,0,0)).cross(n).normalize();
            else tangent.div(projection);
            var side=new Vector3d(n).cross(tangent).normalize();
            var velocity=Sable.HELPER.getVelocity(level,JOMLConversion.toJOML(point));
            var otherPoint=oppositePoint(host,point,hit.other());
            if(hit.other()!=null)velocity.sub(Sable.HELPER.getVelocity(level,JOMLConversion.toJOML(otherPoint)));
            host.logicalPose().transformNormalInverse(velocity);
            double vn=velocity.dot(n),brake=level.getSignal(w.getBlockPos().above(),Direction.DOWN)/15.0;
            var a=TrackAssemblies.get(w);int winding=a==null?1:a.sides().get(w.beltIndex);
            double target=-TrackAssemblies.rpm(w)*Math.PI*2/60*w.wheelRadius()*winding*projection*(1-brake);
            var lever=JOMLConversion.toJOML(point).sub(frame.centerOfMass);
            // Compliance scales with mass; increasing the drive gain changes response, never RPM.
            double soft=frame.inverseMass/(Math.max(1e-6,timeStep)*24*w.driveMultiplier);
            var contact=new SuspensionContactSolver.Contact(
               contactRow(frame,lever,n,vn,dev.trackssimulate.physics.SurfaceProbes.normalTarget(hit.penetration(),timeStep),0),null,
               contactRow(frame,lever,tangent,velocity.dot(tangent),target,(1-brake)*soft),
               contactRow(frame,lever,side,velocity.dot(side),0,soft),
               (Math.abs(vn)+2+20*timeStep)/frame.inverseMass,
               hit.friction()*w.gripMultiplier*w.forwardFriction,hit.friction()*w.gripMultiplier*w.sideFriction);
            loads.add(new ContactLoad(host,point,hit.other(),otherPoint,hit.root(),w,contact,projection,true));
         }
         var checked=new java.util.HashSet<java.util.UUID>();
         for(var wheel:frame.wheels) {
            if(wheel.beltId==null||!checked.add(wheel.beltId))continue;
            var a=TrackAssemblies.get(wheel);var members=TrackAssemblies.members(wheel);
            if(a==null||members.size()!=a.offsets().size()||members.stream().anyMatch(w->w.isRemoved()
                  ||TrackAssemblies.axis(w)!=a.axis()||Sable.HELPER.getContaining(w)!=host||w.effectiveRole()==SableTrackRole.DRIVE))continue;
            var root=members.getFirst();
            var rolling=new SuspensionContactSolver.FreeBelt(members.size()*dev.trackssimulate.config.TracksServerConfig.PASSIVE_ROLLING_MASS.get(),root.passiveMotion?root.passiveSpeed:0);
            double brake=0;for(var w:members)brake=Math.max(brake,level.getSignal(w.getBlockPos().above(),Direction.DOWN)/15.0);
            if(brake>=1)rolling.speed=0;
            rolling.brake(brake);passive.put(root,rolling);
         }
      }
      for(var load:loads) {
         var rolling=passive.get(load.root);
         if(rolling!=null) {
            if(rolling.brake()>=1)load.contact.forward().lockRolling();
            else load.contact.forward().couple(rolling,load.rolling);
         }
         if(load.other instanceof ServerSubLevel other&&!other.isRemoved()&&other.getMassTracker().getInverseMass()>0) {
            var frame=frames.computeIfAbsent(other,SuspensionFrame::new);
            if(!frame.valid())continue;
            var lever=JOMLConversion.toJOML(load.otherPoint).sub(frame.centerOfMass);
            for(var row:rows(load.contact))if(row!=null) {
               var direction=new Vector3d(row.j[0],row.j[1],row.j[2]);load.host.logicalPose().transformNormal(direction);
               other.logicalPose().transformNormalInverse(direction).negate();
               row.react(contactRow(frame,lever,direction,0,0,0));
            }
         }
      }
      long start=System.nanoTime();
      var result=SuspensionContactSolver.solve(loads.stream().map(ContactLoad::contact).toList());
      long solveNanos=System.nanoTime()-start;
      var totals=new java.util.IdentityHashMap<ServerSubLevel,ForceTotal>();
      for(var load:loads) {
         var impulse=new Vector3d();
         for(var row:rows(load.contact))if(row!=null)impulse.add(row.j[0]*row.impulse,row.j[1]*row.impulse,row.j[2]*row.impulse);
         if(!impulse.isFinite())continue;
         totals.computeIfAbsent(load.host,h->new ForceTotal()).applyImpulseAtPoint(load.host,JOMLConversion.toJOML(load.point),impulse);
         if(load.other instanceof ServerSubLevel other&&load.contact.normal().reaction()!=null) {
            var reaction=new Vector3d(impulse);load.host.logicalPose().transformNormal(reaction);other.logicalPose().transformNormalInverse(reaction).negate();
            totals.computeIfAbsent(other,h->new ForceTotal()).applyImpulseAtPoint(other,JOMLConversion.toJOML(load.otherPoint),reaction);
         }
         var w=load.source;w.queuedForce.add(impulse);w.diagnosticNormal+=load.contact.supportImpulse();
         w.diagnosticForwardImpulse+=load.contact.forward().impulse;w.diagnosticSideImpulse+=load.contact.side().impulse;
         if(load.contact.supportImpulse()>1e-8)w.diagnosticContact=true;
         if(!load.surface) {
            w.diagnosticStopImpulse=load.contact.stop()==null?0:load.contact.stop().impulse;
            w.diagnosticLimited=w.diagnosticNormal<=1e-8||load.contact.normal().impulse>=load.contact.maxNormal()-1e-8;
         }
      }
      for(var e:totals.entrySet())RigidBodyHandle.of(e.getKey()).applyForcesAndReset(e.getValue());
      for(var e:passive.entrySet()) {
         var root=e.getKey();var rolling=e.getValue();if(!Double.isFinite(rolling.speed))continue;
         root.passiveMotion=true;root.passiveSpeed=rolling.speed;root.passiveDistance+=rolling.speed*timeStep;
         root.passivePhysicsTick=root.passiveSampleTick=level.getGameTime();
         if(level.getGameTime()-root.passiveReportTick>=100) {
            root.passiveReportTick=level.getGameTime();int count=0;double normal=0,tangent=0;
            for(var load:loads)if(load.root==root){count++;normal+=load.contact.supportImpulse();tangent+=Math.abs(load.contact.forward().impulse);}
            dev.trackssimulate.diagnostics.TrackDiagnostics.motion(root.beltId,System.identityHashCode(Sable.HELPER.getContaining(root)),TrackAssemblies.members(root).size(),count,rolling.speed,root.passiveDistance,normal,tangent,1/rolling.inverseMass);
         }
      }
      boolean timed=false;
      for(var e:active) {
         var host=e.getKey();var batch=batches.get(host);int count=0;double normal=0,tangent=0;
         for(var load:loads)if(load.host==host){count++;if(load.surface){normal+=load.contact.supportImpulse();tangent+=Math.abs(load.contact.forward().impulse)+Math.abs(load.contact.side().impulse);}}
         // Global island time is charged once, not multiplied by the number of vehicles.
         dev.trackssimulate.diagnostics.TrackDiagnostics.solver(host,count,result,timed?0:solveNanos);timed=true;
         dev.trackssimulate.diagnostics.TrackDiagnostics.surface(host,batch,normal,tangent);
         for(var w:e.getValue().wheels){w.recordPhysicsDiagnostics(host,timeStep);w.pendingContact=null;w.pendingOther=null;}
      }
      SUPPORT_COUNTS.keySet().removeIf(host->host.isRemoved()||host.getLevel()==level);
   }
   private static Vec3 oppositePoint(ServerSubLevel host,Vec3 point,SubLevel other) {
      var world=host.logicalPose().transformPosition(point);return other==null?world:other.logicalPose().transformPositionInverse(world);
   }
   private static SuspensionContactSolver.Row[] rows(SuspensionContactSolver.Contact c) {
      return new SuspensionContactSolver.Row[]{c.normal(),c.stop(),c.forward(),c.side()};
   }

   public static void clearPhysicsQueue() {
      SUSPENSION_FRAMES.clear();SUPPORT_COUNTS.clear();
      dev.trackssimulate.physics.SurfaceContacts.clear();
      dev.trackssimulate.physics.ConnectedBodies.clear();
   }

   public float calculateStressApplied() {
      float stress = this.effectiveRole() == SableTrackRole.DRIVE ? 16.0F : 0.0F;
      this.lastStressApplied = stress;
      return stress;
   }

   public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
      behaviours.add(
         this.strength = new SableTrackBlockEntity.TrackStrengthValueBehaviour(SCROLL_OPTION_TITLE, this, new SableTrackBlockEntity.TrackStrengthValueBox())
      );
      this.strength.withCallback(this::onStrengthChanged);
      this.strength.value = 16;
   }

   private double diagnosticNormal, diagnosticRpm, diagnosticForward, diagnosticSide;
   private double diagnosticRawExtension, diagnosticCompression, diagnosticSpring, diagnosticDamping;
   private double diagnosticStopImpulse,diagnosticStopPenetration,diagnosticForwardImpulse,diagnosticSideImpulse;
   private BlockPos diagnosticHit;
   private int diagnosticQueries;
   private boolean diagnosticLimited, diagnosticContact;

   public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
      if (isRemoved() || subLevel.isRemoved() || !(timeStep > 0) || timeStep > .1 || !effectivePart().appliesPhysics()) return;
      long start = System.nanoTime();
      queuedForce.zero(); diagnosticNormal=0; diagnosticRpm=0; diagnosticForward=0; diagnosticSide=0;
      diagnosticRawExtension=Double.NaN; diagnosticCompression=0; diagnosticSpring=0; diagnosticDamping=0;
      diagnosticStopImpulse=diagnosticStopPenetration=diagnosticForwardImpulse=diagnosticSideImpulse=0;
      diagnosticHit=null; diagnosticLimited=false; diagnosticContact=false; diagnosticQueries=0;
      pendingContact=null;pendingOther=null;
      var frame=SUSPENSION_FRAMES.computeIfAbsent(subLevel,SuspensionFrame::new);
      if(!frame.valid())return;
      applyTrackPhysics(subLevel,handle,timeStep);
      diagnosticNanos=System.nanoTime()-start;
      frame.wheels.add(this);
   }

   private void recordPhysicsDiagnostics(ServerSubLevel subLevel,double timeStep) {
      dev.trackssimulate.diagnostics.TrackDiagnostics.sample(subLevel,getBlockPos(),effectiveRole().name(),
         wheelRadius(),wheelWidth(),timeStep,extension,maximumExtension(),queuedForce,diagnosticNormal,diagnosticRpm,
         diagnosticForward,diagnosticSide,diagnosticHit,diagnosticContact,diagnosticRawExtension,diagnosticCompression,
         diagnosticSpring,diagnosticDamping,diagnosticLimited,diagnosticQueries,diagnosticNanos,
         maximumCompression(),contactSpread,contactMargin,forwardFriction,sideFriction,diagnosticStopImpulse,diagnosticStopPenetration,
         diagnosticForwardImpulse,diagnosticSideImpulse,-heightOffset);
   }

   private static int supportCount(ServerSubLevel host) {
      return SUPPORT_COUNTS.computeIfAbsent(host, body -> {
         int count=0;
         for (var actor : body.getPlot().getBlockEntityActors()) {
            if (actor instanceof SableTrackBlockEntity wheel && !wheel.isRemoved()
               && wheel.effectiveRole()==SableTrackRole.SUSPENSION) count++;
         }
         return Math.max(1,count);
      });
   }

   private void applyTrackPhysics(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
      SableTrackPart part = this.effectivePart();
      Direction facing = this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      diagnosticRpm = this.getSharedTrackSpeed(facing);
      // Fixed wheels have no spring travel. Their shell contacts are collected
      // with the complete belt after all suspension positions have been evaluated.
      if (part.role()!=SableTrackRole.SUSPENSION) {
         this.extension=0; this.liftedUp=true;
         return;
      }
      Vec3i side = Direction.get(AxisDirection.POSITIVE, facing.getAxis()).getNormal();
      Vector3dc forwardD = this.getRotatedAxis(new Vec3i(side.getZ(),0,side.getX()));
      TerrainCastResult terrain = this.computeExtensionToTerrain(forwardD, subLevel.logicalPose(), part);
      applySuspensionTerrain(subLevel,timeStep,SUSPENSION_FRAMES.get(subLevel),terrain);
   }

   private void applySuspensionTerrain(ServerSubLevel subLevel,double timeStep,SuspensionFrame frame,TerrainCastResult terrain) {
      SableTrackPart part=this.effectivePart();
      Direction facing=this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      Vec3 localPos=terrain.forcePoint()!=null?terrain.forcePoint():this.getTrackCenter(facing);
      this.queuedForcePos.set(localPos.x,localPos.y,localPos.z);
      pendingContact=null;pendingOther=null;diagnosticContact=false;
      MassData massData = subLevel.getMassTracker();
      double normalMass = 1.0 / massData.getInverseNormalMass(this.queuedForcePos, OrientedBoundingBox3d.UP);
      double supportedMass = massData.getMass()/supportCount(subLevel);
      if (!(normalMass>0) || !Double.isFinite(normalMass) || !(supportedMass>0)) return;
      Pose3d pose = subLevel.logicalPose();
      Vec3i side = Direction.get(AxisDirection.POSITIVE, facing.getAxis()).getNormal();
      Vector3dc sideD = this.getRotatedAxis(side);
      Vector3dc forwardD = this.getRotatedAxis(new Vec3i(side.getZ(),0,side.getX()));
      double rawExtension=terrain.maxExtension()-wheelRadius();
      diagnosticRawExtension=rawExtension; diagnosticHit=terrain.minInteractingBlock();
      var limits=suspensionLimits();
      this.extension=limits.clamp(rawExtension);
      this.liftedUp=terrain.minInteractingBlock()==null || !limits.reachable(rawExtension);
      if (this.liftedUp) return;
      diagnosticContact=true;pendingOther=terrain.subLevel();

      // Use exactly the same extension for geometry and spring compression.
      // Stiffness is per wheel, not the full vehicle effective mass multiplied
      // once again for every wheel. Added load can now visibly compress a spring.
      double effectiveStrength=this.strength==null?16:this.strength.getValue();
      double springStrength=effectiveStrength*8.0*this.springMultiplier;
      double dampingStrength=1.6*Math.sqrt(springStrength*supportedMass)*this.dampingMultiplier;
      double springError=maximumExtension()-this.extension;
      Vector3d velocity = Sable.HELPER.getVelocity(this.level,JOMLConversion.toJOML(localPos));
      if (terrain.subLevel()!=null) {
         Vec3 hitLocal=terrain.subLevel().logicalPose().transformPositionInverse(pose.transformPosition(localPos));
         velocity.sub(Sable.HELPER.getVelocity(this.level,JOMLConversion.toJOML(hitLocal)));
      }
      Vector3d localVelocity=pose.transformNormalInverse(velocity);
      Vector3d normal=JOMLConversion.toJOML(terrain.normal());
      normal.normalize();
      // Velocity projection and impulse direction must form the same Jacobian.
      // Every wheel then observes velocity changes caused by every other wheel.
      double normalVelocity=localVelocity.dot(normal);
      double dampingForce=-normalVelocity*dampingStrength;
      double bumpStart=limits.bumpStart(this.bumpClearanceMultiplier);
      double bumpError=Math.max(0,bumpStart-rawExtension);
      double bumpStiffness=springStrength*4*this.bumpForceMultiplier;
      double springForce=springError*springStrength+bumpError*bumpStiffness;
      double stiffness=springStrength+(bumpError>0?bumpStiffness:0);
      diagnosticStopPenetration=limits.penetration(rawExtension);
      double implicitDamping=dampingStrength+stiffness*timeStep;
      double softness=1/(timeStep*implicitDamping);
      double maxImpulse=supportedMass*80*timeStep*this.maxImpulseMultiplier;
      diagnosticCompression=springError; diagnosticSpring=springForce; diagnosticDamping=dampingForce;
      this.touchingFriction=fudgeFriction(PhysicsBlockPropertyHelper.getFriction(this.level.getBlockState(terrain.minInteractingBlock())));
      double brakeStrength=(double)this.level.getSignal(this.getBlockPos().above(),Direction.DOWN)/15;
      double surfaceBraking=Math.min(this.touchingFriction,1);
      double strengthMul=Math.min(normalMass,effectiveStrength)*16;
      Vector3d forward=new Vector3d(forwardD).fma(-forwardD.dot(normal),normal).normalize();
      Vector3d lateral=new Vector3d(normal).cross(forward);
      if(lateral.dot(sideD)<0)lateral.negate();
      diagnosticForward=localVelocity.dot(forward); diagnosticSide=localVelocity.dot(lateral);
      // Friction raises the traction/braking envelope, not the straight-line
      // viscous drag or desired speed. Full braking solves no-slip directly.
      double forwardDamping=Math.max(1e-6,(0.075+brakeStrength*0.3)*surfaceBraking*part.sideGripMultiplier()*strengthMul);
      double sideDamping=Math.max(1e-6,0.6*this.touchingFriction*strengthMul*part.sideGripMultiplier()*this.gripMultiplier*this.sideFriction);
      forwardDamping*=this.driveMultiplier;
      double rimTarget=-diagnosticRpm*Math.PI*2/60*wheelRadius()*(1-brakeStrength);
      Vector3d lever=new Vector3d(queuedForcePos).sub(frame.centerOfMass);
      pendingContact=new SuspensionContactSolver.Contact(
         contactRow(frame,lever,normal,normalVelocity,springForce/implicitDamping,softness),
         contactRow(frame,lever,normal,normalVelocity,limits.stopTarget(rawExtension,normal.y,timeStep),0),
         contactRow(frame,lever,forward,diagnosticForward,rimTarget,(1-brakeStrength)/(timeStep*forwardDamping)),
         contactRow(frame,lever,lateral,diagnosticSide,0,1/(timeStep*sideDamping)),
         maxImpulse,this.touchingFriction*this.gripMultiplier*this.forwardFriction,this.touchingFriction*this.gripMultiplier*this.sideFriction);
   }

   private static SuspensionContactSolver.Row contactRow(SuspensionFrame frame, Vector3dc lever, Vector3dc direction,
         double velocity,double target,double softness) {
      return new SuspensionContactSolver.Row(direction.x(),direction.y(),direction.z(),lever.x(),lever.y(),lever.z(),
         frame.inverseMass,frame.inverseInertia,velocity,target,softness).bind(frame.body);
   }

   public static double fudgeFriction(double realValue) {
      return realValue < 1.0 ? 0.1 + 0.9 * realValue : realValue;
   }

   public void tick() {
      if(level!=null&&!level.isClientSide&&travelMigrationPending) {
         travelMigrationPending=false;setChanged();sendData();
         dev.trackssimulate.Tracks.LOGGER.info("[TS-TRAVEL] event=saved_semantics_migrated pos={} downOffset={} extension={} compressionStroke={} relativeLimits=[{},{}]",worldPosition,-heightOffset,maximumExtension(),maxCompression,minimumExtension(),maximumExtension());
      }
      dev.trackssimulate.assembly.TrackDriveNetwork.maintain(this);
      super.tick();
      if(isRemoved())return; // Create may break a conflicting kinetic connection during attach.
      if(beltIndex==0&&beltId!=null) {
         if(level.isClientSide) {
            passiveLastRender=passiveRender;
            if(passiveMotion) {
               double age=Math.max(0,Math.min(4,level.getGameTime()-passiveSampleTick));
               double expected=passiveDistance+passiveSpeed*age/20;
               passiveRender+=(expected-passiveRender)*.65;
            }
         } else {
            if(passiveMotion&&level.getGameTime()-passivePhysicsTick>2){passiveSpeed=0;passiveSampleTick=level.getGameTime();}
            if(level.getGameTime()%2==0&&(passiveMotion!=lastSentPassive||!Double.isFinite(lastSentPassiveDistance)
                ||Math.abs(passiveSpeed-lastSentPassiveSpeed)>.01||Math.abs(passiveDistance-lastSentPassiveDistance)>.002||passiveMotion&&level.getGameTime()%20==0)) {
               lastSentPassive=passiveMotion;lastSentPassiveDistance=passiveDistance;lastSentPassiveSpeed=passiveSpeed;
               PacketDistributor.sendToPlayersInDimension((ServerLevel)level,new dev.trackssimulate.assembly.AssemblyPackets.Motion(worldPosition,beltId,passiveSampleTick,passiveDistance,passiveSpeed,passiveMotion));
               if(level.getGameTime()%20==0)setChanged();
            }
         }
      }
      if(removalRecipient!=null&&removalTick!=level.getGameTime())removalRecipient=null;
      if (beltId!=null && level.getGameTime()%20==0) {
         if(!level.isClientSide)TrackAssemblies.reconcile(this);
         else invalidateRenderBoundingBox();
      }
      this.lastLateralOffset = this.lateralOffset;
      this.lastLongitudinalOffset = this.longitudinalOffset;
      this.lastHeightOffset = this.heightOffset;
      if (!this.level.isClientSide) {
         this.syncSuspensionModelState();
      }

      this.propagateTrackStrengthIfChanged();
      SableTrackPart part = this.effectivePart();
      if (part.isWheel()) {
         if (part.role()!=SableTrackRole.SUSPENSION) {
            this.extension=this.lastExtension=this.targetExtension=0;
         } else if (this.level.isClientSide) {
            this.lastExtension=this.extension;
            this.extension=Mth.lerp(0.7,this.extension,this.targetExtension);
         } else if (Sable.HELPER.getContaining(this)==null) {
            this.extension=maximumExtension(); this.liftedUp=true;
         }
         if (!this.level.isClientSide && ++this.suspensionSyncTicks>=2) {
            this.suspensionSyncTicks=0;
            if (!Double.isFinite(this.lastSentExtension) || Math.abs(this.extension-this.lastSentExtension)>0.002
               || this.liftedUp!=this.lastSentLifted) {
               this.lastSentExtension=this.extension; this.lastSentLifted=this.liftedUp;
               this.sendData();
            }
         }
         Direction facing = (Direction)this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
         float speed = this.getSharedTrackSpeed(facing);
         double rpt = (double)(-speed) * Math.PI * 2.0 / 60.0 / 20.0 * (double)(15 - this.level.getSignal(this.getBlockPos().above(), Direction.DOWN)) / 15.0;
         double attemptedAngularVelocity = Mth.lerp(0.2, this.angularVelocity, rpt);
         if (this.level.isClientSide) {
            SubLevel subLevel = Sable.HELPER.getContaining(this);
            if (subLevel != null && !this.liftedUp) {
               Vector3d velocity = Sable.HELPER.getVelocity(this.level, JOMLConversion.toJOML(this.getTrackCenter(facing)));
               Vector3d localVelocity = subLevel.logicalPose().transformNormalInverse(velocity).div(20.0);
               Axis axis = facing.getAxis();
               Vec3i forward = Direction.get(AxisDirection.POSITIVE, axis).getNormal();
               forward = new Vec3i(forward.getZ(), 0, forward.getX());
               Vector3dc forwardD = this.getRotatedAxis(forward);
               double translation = localVelocity.dot(forwardD);
               double circumference = Math.PI * wheelRadius() * 2.0;
               double angularDelta = translation / circumference * Math.PI * 2.0;
               if (this.touchingFriction < 1.0) {
                  angularDelta = Mth.lerp(this.touchingFriction, attemptedAngularVelocity, angularDelta);
               }

               this.lastAngle = this.angle;
               this.angle += angularDelta;
               this.angularVelocity = angularDelta;
            } else {
               this.angularVelocity = attemptedAngularVelocity;
               this.lastAngle = this.angle;
               this.angle = this.angle + this.angularVelocity;
            }
         }
      }
   }

   private TerrainCastResult computeExtensionToTerrain(Vector3dc forwardD, Pose3dc pose, SableTrackPart part) {
      Direction facing=this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      Vec3 center=this.getTrackCenter(facing);
      double bestDistance=wheelRadius()+maximumExtension()+contactMargin+1;
      Vec3 bestNormal=new Vec3(0,1,0);
      SubLevel bestSubLevel=null;
      BlockPos bestBlock=null;
      // Fixed five-ray budget; width changes reach, never multiplies support forces.
      // Offset rays use the circular underside rather than a flat plank envelope.
      double spacing=wheelRadius()*0.4*contactSpread;
      for(int i=0;i<5;i++) {
         double offset=i<3?(i-1)*spacing:0;
         double axial=i<3?0:(i==3?-1:1)*wheelWidth()*.4*contactSpread;
         Vec3 sample=center.add(JOMLConversion.toMojang(forwardD).scale(offset))
            .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(axial));
         double top=Math.max(0,-minimumExtension())+contactMargin;
         ClipContext clip=new ClipContext(sample.add(0,top,0),sample.subtract(0,wheelRadius()+maximumExtension()+contactMargin,0),
            Block.COLLIDER,Fluid.NONE,CollisionContext.empty());
         ((ClipContextExtension)clip).sable$setIgnoredSubLevel(Sable.HELPER.getContaining(this));
         if(Sable.HELPER.getContaining(this) instanceof ServerSubLevel host)
            ((ClipContextExtension)clip).sable$setSubLevelIgnoring(dev.trackssimulate.physics.ConnectedBodies.ignoring(host,connectedContacts));
         diagnosticQueries++;
         BlockHitResult hit=this.level.clip(clip);
         if(hit.getType()==Type.MISS||hit.isInside())continue;
         SubLevel hitBody=Sable.HELPER.getContaining(this.level,hit.getLocation());
         Vec3 worldHit=hitBody==null?hit.getLocation():hitBody.logicalPose().transformPosition(hit.getLocation());
         Vec3 localHit=pose.transformPositionInverse(worldHit);
         Vector3d normal=new Vector3d(hit.getDirection().getStepX(),hit.getDirection().getStepY(),hit.getDirection().getStepZ());
         if(hitBody!=null)hitBody.logicalPose().transformNormal(normal);
         pose.transformNormalInverse(normal);
         if(normal.y<0.5 || localHit.y>center.y+top)continue;
         double distance=center.y-localHit.y+wheelRadius()-Math.sqrt(wheelRadius()*wheelRadius()-offset*offset);
         if(distance<bestDistance) {
            bestDistance=distance;bestNormal=JOMLConversion.toMojang(normal);bestSubLevel=hitBody;
            bestBlock=hit.getBlockPos().immutable();
         }
      }
      return new TerrainCastResult(bestDistance,bestNormal,bestSubLevel,bestBlock,null);
   }

   @NotNull
   private Vector3dc getRotatedAxis(Vec3i normal) {
      return new Vector3d((double)normal.getX(), (double)normal.getY(), (double)normal.getZ());
   }

   private Vec3 getTrackCenter(Direction facing) {
      return this.getWheelMountCenter(1);
   }

   /** Explicit placement offset is independent of suspension travel. */
   public Vec3 getWheelMountCenter(float partialTicks) {
      Direction facing = this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      return this.getAnchorCenter()
         .add(Vec3.atLowerCornerOf(facing.getClockWise().getNormal()).scale(this.getLerpedLateralOffset(partialTicks)))
         .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(this.getLerpedLongitudinalOffset(partialTicks)))
         .add(0.0, this.getLerpedHeightOffset(partialTicks), 0.0);
   }

   public Vec3 offsetDelta(double lateral,double longitudinal,double height) {
      var facing=getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      return Vec3.atLowerCornerOf(facing.getClockWise().getNormal()).scale(lateral-lateralOffset)
         .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(longitudinal-longitudinalOffset))
         .add(0,height-heightOffset,0);
   }

   public Vec3 getWheelCenter(float partialTicks) {
      return this.getWheelMountCenter(partialTicks).add(0, -this.getLerpedExtension(partialTicks), 0);
   }

   /** Shared origin for rendering, terrain sampling and force application. */
   public Vec3 getAnchorCenter() {
      if (this.getBlockState().getBlock() instanceof SableTrackBlock block && block.isDirectWheel()) {
         return this.getBlockPos().getCenter();
      }
      // Existing rebuild.1 mounts keep their original wheel position.
      return this.getBlockPos().relative(this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING)).getCenter();
   }

   public SableTrackRole baseRole() {
      return this.getBlockState().getBlock() instanceof SableTrackBlock trackBlock ? trackBlock.role() : SableTrackRole.MOUNT;
   }

   public SableTrackPart effectivePart() {
      if (this.getBlockState().getBlock() instanceof SableTrackBlock block && block.isDirectWheel()) {
         return block.builtInPart();
      }
      return SableTrackPart.fromStack(this.heldItem);
   }

   public SableTrackRole effectiveRole() {
      return this.effectivePart().role();
   }

   public double adjustLateralOffset(int direction) {return adjustOffset("lateral",direction);}
   public double adjustLongitudinalOffset(int direction) {return adjustOffset("longitudinal",direction);}
   public double adjustHeightOffset(int direction) {return adjustOffset("height",direction);}
   private double adjustOffset(String key,int direction) {
      var setting=TrackSetting.byKey(key);
      double limit=key.equals("height")?.75:1;
      double value=Mth.clamp(Math.round((getSettingValue(key)+direction*.125)/.125)*.125,-limit,limit);
      setSettingValue(setting.key,value,OffsetScope.ROLE);
      return getSettingValue(key);
   }

   public double adjustTuning(String key, int direction) {
      if (key.equals("strength")) {
         int current = this.strength == null ? 16 : this.strength.getValue();
         int next = Mth.clamp(current + direction * 5, 5, 180);
         if (this.strength != null) {
            this.strength.value = next;
         }

         if (this.level != null && !this.level.isClientSide) {
            this.applyStrengthToConnectedTrack(next);
         } else {
            this.onTuningChanged();
         }

         return (double)next;
      } else {
         double step = key.equals("drive") ? 0.1 : 0.05;
         double previous = this.getTuning(key);
         double nextx = Mth.clamp((double)Math.round((previous + (double)direction * step) / step) * step, 0.1, 4.0);
         switch (key) {
            case "spring":
               this.springMultiplier = nextx;
               break;
            case "damping":
               this.dampingMultiplier = nextx;
               break;
            case "bump_clearance":
               this.bumpClearanceMultiplier = nextx;
               break;
            case "bump_force":
               this.bumpForceMultiplier = nextx;
               break;
            case "max_impulse":
               this.maxImpulseMultiplier = nextx;
               break;
            case "drive":
               this.driveMultiplier = nextx;
               break;
            case "grip":
               this.gripMultiplier = nextx;
               break;
            default:
               return previous;
         }

         if (this.level != null && !this.level.isClientSide) {
            this.applyTuningToConnectedTrack(key, nextx);
         } else {
            this.onTuningChanged();
         }

         return nextx;
      }
   }

   public double getTuning(String key) {
      return switch (key) {
         case "strength" -> this.strength == null ? 16.0 : (double)this.strength.getValue();
         case "spring" -> this.springMultiplier;
         case "damping" -> this.dampingMultiplier;
         case "bump_clearance" -> this.bumpClearanceMultiplier;
         case "bump_force" -> this.bumpForceMultiplier;
         case "max_impulse" -> this.maxImpulseMultiplier;
         case "drive" -> this.driveMultiplier;
         case "grip" -> this.gripMultiplier;
         default -> 1.0;
      };
   }

   public double getSettingValue(String key) {
      return switch (key) {
         case "scale" -> wheelRadius()/effectivePart().radius();
         case "hidden" -> visualSuspensionHidden?1:0;
         case "wheel_hidden" -> wheelHidden?1:0;
         case "connected_contacts" -> connectedContacts?1:0;
         case "speed" -> speedMultiplier;
         case "max_extension" -> effectiveRole()==SableTrackRole.SUSPENSION?maximumExtension():TrackSetting.MAX_EXTENSION.defaultValue;
         case "max_compression" -> effectiveRole()==SableTrackRole.SUSPENSION?maximumCompression():TrackSetting.MAX_COMPRESSION.defaultValue;
         case "contact_spread" -> contactSpread;
         case "contact_margin" -> contactMargin;
         case "friction_forward" -> forwardFriction;
         case "friction_side" -> sideFriction;
         case "through_axle" -> SableTrackBlock.axleMode(getBlockState()).id;
         case "width" -> wheelWidth();
         case "lateral" -> this.lateralOffset;
         case "longitudinal" -> this.longitudinalOffset;
         case "height" -> this.heightOffset;
         default -> this.getTuning(key);
      };
   }

   /** Physical tuning is shared; position writes use an explicit target scope. */
   public boolean setSettingValue(String key, double value) {
      return setSettingValue(key,value,OffsetScope.ROLE);
   }
   public boolean setSettingValue(String key,double value,OffsetScope scope) {
      var setting = dev.trackssimulate.config.TrackSetting.byKey(key);
      if (setting == null || !setting.accepts(value) || this.level == null || this.level.isClientSide) return false;
      switch (key) {
         case "connected_contacts", "scale", "width", "hidden", "wheel_hidden", "speed", "through_axle", "max_extension", "max_compression", "contact_spread", "contact_margin", "friction_forward", "friction_side" -> throw new IllegalArgumentException("请通过调试 GUI 应用此配置");
         case "strength" -> {
            if (this.strength == null) return false;
            this.strength.value = (int)value;
            this.applyStrengthToConnectedTrack((int)value);
         }
         case "lateral", "longitudinal", "height" -> {
            this.applyOffsetToConnectedTrack(key, value,scope);
         }
         default -> this.applyTuningToConnectedTrack(key, value);
      }
      return true;
   }

   private void onTuningChanged() {
      settingsConfigured=true;
      this.setChanged();
      if (this.level != null && !this.level.isClientSide) {
         this.sendData();
      }
   }

   public void selectScrollTuningMode(String key) {
      this.scrollTuningKey = key;
      if (this.strength != null) {
         if ("strength".equals(key)) {
            this.strength.value = this.protectedStrengthValue;
            this.lastPropagatedStrength = this.protectedStrengthValue;
         } else {
            this.protectedStrengthValue = this.lastPropagatedStrength;
            if (this.level != null && this.level.isClientSide) {
               this.strength.value = tuningToScroll(this.getTuning(key));
            } else {
               this.strength.value = this.protectedStrengthValue;
            }
         }
      }
   }

   public double getLerpedExtension(float partialTicks) {
      return effectiveRole()!=SableTrackRole.SUSPENSION?0:Mth.lerp((double)partialTicks, this.lastExtension, this.extension);
   }

   public double getLerpedLateralOffset(float partialTicks) {
      return Mth.lerp((double)partialTicks, this.lastLateralOffset, this.lateralOffset);
   }

   public double getLerpedLongitudinalOffset(float partialTicks) {
      return Mth.lerp((double)partialTicks, this.lastLongitudinalOffset, this.longitudinalOffset);
   }

   public double getLerpedHeightOffset(float partialTicks) {
      return Mth.lerp((double)partialTicks, this.lastHeightOffset, this.heightOffset);
   }

   public float getLerpedAngle(float partialTicks) {
      return (float)Mth.lerp((double)partialTicks, this.lastAngle, this.angle);
   }

   public float getSharedTrackAngle(Direction facing,float partialTicks) {
      return beltId==null?getLerpedAngle(partialTicks):TrackAssemblies.angle(this,partialTicks);
   }

   public ItemStack getHeldItem() {
      return this.heldItem;
   }

   public void clearContent() {
      this.heldItem = ItemStack.EMPTY;
   }

   public void setHeldItem(ItemStack heldItem) {
      this.heldItem = heldItem.copy();
      this.setChanged();
      this.invalidateRenderBoundingBox();
      if (this.level != null && !this.level.isClientSide) {
         this.syncSuspensionModelState();
         this.sendData();
      }
   }

   public float getSharedTrackSpeed(Direction facing) {
      if(beltId!=null)return TrackAssemblies.rpm(this);
      return effectiveRole()==SableTrackRole.DRIVE?(float)((facing.getAxis()==Axis.X?-getSpeed():getSpeed())*speedMultiplier):0;
   }

   public boolean isVisualSuspensionHidden() {
      return this.visualSuspensionHidden;
   }

   public DyeColor getBeltColor() {
      return this.beltColor;
   }

   public boolean applyBeltColorToConnectedTrack(DyeColor color) {
      if(level==null||level.isClientSide)return false;
      boolean changed=false;for(var member:TrackAssemblies.members(this))changed|=member.setBeltColor(color);return changed;
   }

   public void resetTuningToConnectedTrack() {
      if(level!=null&&!level.isClientSide)for(var member:TrackAssemblies.members(this))member.resetTuning();
   }

   public void toggleVisualSuspensionHidden() {
      this.visualSuspensionHidden = !this.visualSuspensionHidden;
      this.setChanged();
      this.invalidateRenderBoundingBox();
      if (this.level != null && !this.level.isClientSide) {
         this.sendData();
      }
   }

   private void syncSuspensionModelState() {
      boolean useSuspensionModel = this.effectiveRole() == SableTrackRole.SUSPENSION;
      BlockState state = this.getBlockState();
      if (state.hasProperty(SableTrackBlock.SUSPENSION_MODEL) && (Boolean)state.getValue(SableTrackBlock.SUSPENSION_MODEL) != useSuspensionModel) {
         this.level.setBlock(this.worldPosition, (BlockState)state.setValue(SableTrackBlock.SUSPENSION_MODEL, useSuspensionModel), 2);
      }
   }

   private void resetTuning() {
      settingsConfigured=true;
      if (this.strength != null) {
         this.strength.value = 16;
      }

      this.lastPropagatedStrength = 16;
      this.protectedStrengthValue = 16;
      this.springMultiplier = 1.0;
      this.dampingMultiplier = 1.0;
      this.bumpClearanceMultiplier = 1.0;
      this.bumpForceMultiplier = 1.0;
      this.maxImpulseMultiplier = 1.0;
      this.driveMultiplier = 1.0;
      this.gripMultiplier = 1.0;
      this.setChanged();
      if (this.level != null && !this.level.isClientSide) {
         this.sendData();
      }
   }

   private boolean setBeltColor(DyeColor color) {
      if (this.beltColor == color) {
         return false;
      } else {
         this.beltColor = color;
         this.setChanged();
         this.invalidateRenderBoundingBox();
         if (this.level != null && !this.level.isClientSide) {
            this.sendData();
         }

         return true;
      }
   }

   public double getNeighborExtensionDelta(Direction facing, float partialTicks) {
      if (this.level == null) {
         return 0.0;
      } else {
         Direction along = facing.getClockWise();
         if (this.level.getBlockEntity(this.getBlockPos().relative(along)) instanceof SableTrackBlockEntity neighbor
            && neighbor.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING) == facing) {
            return neighbor.getLerpedExtension(partialTicks) - this.getLerpedExtension(partialTicks);
         }

         return 0.0;
      }
   }

   public boolean hasTrackNeighbor(Direction side) {
      return this.level != null && this.level.getBlockEntity(this.getBlockPos().relative(side)) instanceof SableTrackBlockEntity neighbor
         ? neighbor.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING) == this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING)
            && neighbor.effectivePart().isWheel()
         : false;
   }

   private void onStrengthChanged(int value) {
      if (this.level != null && !this.level.isClientSide) {
         if ("strength".equals(this.scrollTuningKey)) {
            this.protectedStrengthValue = value;
            this.applyStrengthToConnectedTrack(value);
         } else {
            if (this.strength != null) {
               this.strength.value = this.protectedStrengthValue;
               this.lastPropagatedStrength = this.protectedStrengthValue;
            }

            double tuningValue = scrollToTuning(value);
            this.applyTuningToConnectedTrack(this.scrollTuningKey, tuningValue);
         }
      }
   }

   private void propagateTrackStrengthIfChanged() {
      if (this.level != null && !this.level.isClientSide && this.strength != null && "strength".equals(this.scrollTuningKey)) {
         int current = this.strength.getValue();
         if (current != this.lastPropagatedStrength) {
            this.applyStrengthToConnectedTrack(current);
         }
      }
   }

   private void applyStrengthToConnectedTrack(int value) {
      if(level==null||level.isClientSide)return;
      for(var member:TrackAssemblies.members(this)) {
         if(member.strength!=null)member.strength.value=value;
         member.settingsConfigured=true;member.lastPropagatedStrength=value;member.protectedStrengthValue=value;member.setChanged();member.sendData();
      }
   }

   private void applyTuningToConnectedTrack(String key,double value) {
      if(level!=null&&!level.isClientSide)for(var member:TrackAssemblies.members(this))member.setTuning(key,value);
   }

   private void applyOffsetToConnectedTrack(String key,double value) {
      applyOffsetToConnectedTrack(key,value,OffsetScope.ROLE);
   }
   private void applyOffsetToConnectedTrack(String key,double value,OffsetScope scope) {
      if(level==null||level.isClientSide)return;
      var deltas=new java.util.LinkedHashMap<SableTrackBlockEntity,Vec3>();
      for(var member:TrackAssemblies.members(this))if(scope.includes(member==this,member.effectiveRole()==effectiveRole()))
         deltas.put(member,member.offsetDelta(key.equals("lateral")?value:member.lateralOffset,
            key.equals("longitudinal")?value:member.longitudinalOffset,key.equals("height")?value:member.heightOffset));
      var edit=TrackAssemblies.prepareOffsets(this,deltas);
      for(var member:deltas.keySet())member.validateTravelSettings(java.util.Map.of(TrackSetting.byKey(key),value));
      for(var member:deltas.keySet())member.setOffset(key,value);
      edit.commit("offset_"+key+"_"+scope);
   }

   private void setOffset(String key, double value) {
      settingsConfigured=true;
      switch (key) {
         case "lateral":
            this.lateralOffset = value;
            break;
         case "longitudinal":
            this.longitudinalOffset = value;
            break;
         case "height":
            this.heightOffset = value;
            break;
         default:
            return;
      }

      this.setChanged();
      this.invalidateRenderBoundingBox();
      if (this.level != null && !this.level.isClientSide) {
         this.sendData();
      }
   }

   private void setTuning(String key, double value) {
      switch (key) {
         case "spring":
            this.springMultiplier = value;
            break;
         case "damping":
            this.dampingMultiplier = value;
            break;
         case "bump_clearance":
            this.bumpClearanceMultiplier = value;
            break;
         case "bump_force":
            this.bumpForceMultiplier = value;
            break;
         case "max_impulse":
            this.maxImpulseMultiplier = value;
            break;
         case "drive":
            this.driveMultiplier = value;
            break;
         case "grip":
            this.gripMultiplier = value;
            break;
         default:
            return;
      }

      this.onTuningChanged();
   }

   private static int tuningToScroll(double value) {
      return Mth.clamp((int)Math.round(5.0 + (Mth.clamp(value, 0.1, 4.0) - 0.1) / 3.9 * 175.0), 5, 180);
   }

   private static double scrollToTuning(int value) {
      return 0.1 + (double)(Mth.clamp(value, 5, 180) - 5) / 175.0 * 3.9;
   }

   protected void write(CompoundTag tag, Provider registries, boolean clientPacket) {
      if(beltId!=null&&beltIndex==0) {
         tag.putDouble("PassiveDistance",passiveDistance);
         if(clientPacket){tag.putBoolean("PassiveMotion",passiveMotion);tag.putDouble("PassiveSpeed",passiveSpeed);tag.putLong("PassiveSampleTick",passiveSampleTick);}
      }
      if(Double.isFinite(radiusOverride))tag.putDouble("WheelRadius",radiusOverride);
      tag.putDouble("WheelWidth",wheelWidth);
      tag.put("WheelMaterial",net.minecraft.nbt.NbtUtils.writeBlockState(material));
      tag.put("WheelMaterialItem",materialItem.saveOptional(registries));
      tag.putBoolean("ConnectedContacts",connectedContacts);
      tag.putBoolean("WheelHidden",wheelHidden);tag.putDouble("BeltSpeedMultiplier",speedMultiplier);
      tag.putInt("SuspensionSemantics",2);
      if(Double.isFinite(maxExtensionOverride))tag.putDouble("MaximumExtension",maxExtensionOverride);
      tag.putDouble("MaximumCompression",maxCompression);tag.putDouble("ContactSpread",contactSpread);tag.putDouble("ContactMargin",contactMargin);
      tag.putDouble("ForwardFriction",forwardFriction);tag.putDouble("SideFriction",sideFriction);
      tag.putBoolean("IndependentOffsets",true);
      tag.putBoolean("SettingsConfigured",settingsConfigured);
      if(beltId!=null){tag.putUUID("AssemblyId",beltId);tag.putInt("AssemblyNode",beltIndex);}
      tag.putDouble("Extension", effectiveRole()!=SableTrackRole.SUSPENSION?0:this.extension);
      tag.putBoolean("SuspensionLifted",this.liftedUp);
      tag.putDouble("TouchingFriction",this.touchingFriction);
      tag.putDouble("LateralOffset", this.lateralOffset);
      tag.putDouble("LongitudinalOffset", this.longitudinalOffset);
      tag.putDouble("HeightOffset", this.heightOffset);
      tag.putDouble("SpringMultiplier", this.springMultiplier);
      tag.putDouble("DampingMultiplier", this.dampingMultiplier);
      tag.putDouble("BumpClearanceMultiplier", this.bumpClearanceMultiplier);
      tag.putDouble("BumpForceMultiplier", this.bumpForceMultiplier);
      tag.putDouble("MaxImpulseMultiplier", this.maxImpulseMultiplier);
      tag.putDouble("DriveMultiplier", this.driveMultiplier);
      tag.putDouble("GripMultiplier", this.gripMultiplier);
      tag.putBoolean("VisualSuspensionHidden", this.visualSuspensionHidden);
      if (this.beltColor != null) {
         tag.putString("BeltColor", this.beltColor.getName());
      }

      tag.put("HeldTrackItem", this.heldItem.saveOptional(registries));
      super.write(tag, registries, clientPacket);
   }

   protected void read(CompoundTag tag, Provider registries, boolean clientPacket) {
      boolean wasPassive=passiveMotion&&tag.hasUUID("AssemblyId")&&tag.getUUID("AssemblyId").equals(beltId);
      passiveDistance=Double.isFinite(tag.getDouble("PassiveDistance"))?tag.getDouble("PassiveDistance"):0;
      passiveMotion=clientPacket&&tag.getBoolean("PassiveMotion");
      passiveSpeed=clientPacket&&Double.isFinite(tag.getDouble("PassiveSpeed"))?tag.getDouble("PassiveSpeed"):0;
      passiveSampleTick=tag.getLong("PassiveSampleTick");
      if(!clientPacket||!wasPassive)passiveLastRender=passiveRender=passiveDistance;
      radiusOverride=(Double.isFinite(tag.getDouble("WheelRadius"))&&tag.getDouble("WheelRadius")>0&&tag.getDouble("WheelRadius")<=TrackSetting.SCALE.max)?tag.getDouble("WheelRadius"):Double.NaN;
      wheelWidth=TrackSetting.WIDTH.accepts(tag.getDouble("WheelWidth"))?tag.getDouble("WheelWidth"):1;
      material=tag.contains("WheelMaterial")?net.minecraft.nbt.NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),tag.getCompound("WheelMaterial")):com.simibubi.create.AllBlocks.COPYCAT_BASE.getDefaultState();
      if(material.isAir())material=com.simibubi.create.AllBlocks.COPYCAT_BASE.getDefaultState();
      materialItem=ItemStack.parseOptional(registries,tag.getCompound("WheelMaterialItem"));
      if(!materialItem.isEmpty())materialItem.setCount(1);
      connectedContacts=tag.getBoolean("ConnectedContacts");
      wheelHidden=tag.getBoolean("WheelHidden");speedMultiplier=TrackSetting.SPEED.accepts(tag.getDouble("BeltSpeedMultiplier"))?tag.getDouble("BeltSpeedMultiplier"):1;
      beltId=tag.hasUUID("AssemblyId")?tag.getUUID("AssemblyId"):null;
      beltIndex=tag.getInt("AssemblyNode");invalidateRenderBoundingBox();
      this.heldItem = ItemStack.parseOptional(registries, tag.getCompound("HeldTrackItem"));
      maxExtensionOverride=tag.contains("MaximumExtension")&&TrackSetting.MAX_EXTENSION.accepts(tag.getDouble("MaximumExtension"))?tag.getDouble("MaximumExtension"):Double.NaN;
      maxCompression=readSetting(tag,"MaximumCompression",TrackSetting.MAX_COMPRESSION);
      heightOffset=readSetting(tag,"HeightOffset",TrackSetting.HEIGHT);
      if(effectiveRole()==SableTrackRole.SUSPENSION) {
         double oldExtension=maximumExtension(),oldCompression=maxCompression;
         var restored=dev.trackssimulate.physics.SuspensionLimits.restore(oldExtension,maxCompression,-heightOffset,tag.getInt("SuspensionSemantics")<2);
         if(restored.extension()!=oldExtension)maxExtensionOverride=restored.extension();
         maxCompression=restored.compression();
         if(!clientPacket&&(tag.getInt("SuspensionSemantics")<2||restored.extension()!=oldExtension||maxCompression!=oldCompression))travelMigrationPending=true;
      }
      contactSpread=readSetting(tag,"ContactSpread",TrackSetting.CONTACT_SPREAD);contactMargin=readSetting(tag,"ContactMargin",TrackSetting.CONTACT_MARGIN);
      forwardFriction=readSetting(tag,"ForwardFriction",TrackSetting.FRICTION_FORWARD);sideFriction=readSetting(tag,"SideFriction",TrackSetting.FRICTION_SIDE);
      if (tag.contains("Extension")) {
         double received=tag.getDouble("Extension");
         if (Double.isFinite(received)) {
            this.targetExtension=Mth.clamp(received,minimumExtension(),maximumExtension());
            if(!clientPacket)this.lastExtension=this.extension=this.targetExtension;
         }
      }
      extension=Mth.clamp(extension,minimumExtension(),maximumExtension());lastExtension=Mth.clamp(lastExtension,minimumExtension(),maximumExtension());
      this.liftedUp=tag.getBoolean("SuspensionLifted");
      if(tag.contains("TouchingFriction"))this.touchingFriction=tag.getDouble("TouchingFriction");

      if (tag.contains("LateralOffset")) {
         this.lateralOffset = tag.getDouble("LateralOffset");
      }

      if (tag.contains("LongitudinalOffset")) {
         this.longitudinalOffset = tag.getDouble("LongitudinalOffset");
      }

      // Height was validated above, before deriving the travel interval.

      if (tag.contains("SpringMultiplier")) {
         this.springMultiplier = tag.getDouble("SpringMultiplier");
      }

      if (tag.contains("DampingMultiplier")) {
         this.dampingMultiplier = tag.getDouble("DampingMultiplier");
      }

      if (tag.contains("BumpClearanceMultiplier")) {
         this.bumpClearanceMultiplier = tag.getDouble("BumpClearanceMultiplier");
      }

      if (tag.contains("BumpForceMultiplier")) {
         this.bumpForceMultiplier = tag.getDouble("BumpForceMultiplier");
      }

      if (tag.contains("MaxImpulseMultiplier")) {
         this.maxImpulseMultiplier = tag.getDouble("MaxImpulseMultiplier");
      }

      if (tag.contains("DriveMultiplier")) {
         this.driveMultiplier = tag.getDouble("DriveMultiplier");
      }

      if (tag.contains("GripMultiplier")) {
         this.gripMultiplier = tag.getDouble("GripMultiplier");
      }

      if (tag.contains("VisualSuspensionHidden")) {
         this.visualSuspensionHidden = tag.getBoolean("VisualSuspensionHidden");
      }

      if (tag.contains("BeltColor")) {
         this.beltColor = DyeColor.byName(tag.getString("BeltColor"), null);
      } else {
         this.beltColor = null;
      }

      if (clientPacket) {
         this.lastLateralOffset = this.lateralOffset;
         this.lastLongitudinalOffset = this.longitudinalOffset;
         this.lastHeightOffset = this.heightOffset;
      }

      // Old versions broadcast road offsets into drives, but never displayed
      // them. Do not suddenly move old drives when enabling explicit offsets.
      if(!tag.getBoolean("IndependentOffsets")&&effectiveRole()==SableTrackRole.DRIVE) {
         lateralOffset=lastLateralOffset=longitudinalOffset=lastLongitudinalOffset=heightOffset=lastHeightOffset=0;
      }
      super.read(tag, registries, clientPacket);
      settingsConfigured=tag.getBoolean("SettingsConfigured");
      if(!tag.contains("SettingsConfigured"))for(var setting:TrackSetting.values())
         if(setting!=TrackSetting.SCALE&&setting!=TrackSetting.WIDTH&&Math.abs(getSettingValue(setting.key)-setting.defaultValue)>1e-8)settingsConfigured=true;
      if (this.strength != null) {
         this.protectedStrengthValue = this.strength.getValue();
         this.lastPropagatedStrength = this.protectedStrengthValue;
      }
   }

   private static double readSetting(CompoundTag tag,String key,TrackSetting setting) {
      return tag.contains(key)&&setting.accepts(tag.getDouble(key))?tag.getDouble(key):setting.defaultValue;
   }

   protected AABB createRenderBoundingBox() {
      AABB box=new AABB(getBlockPos());var assembly=TrackAssemblies.get(this);
      if(assembly==null||beltIndex!=0){
         var mount=getWheelMountCenter(1);double reach=Math.max(wheelRadius(),wheelWidth()/2)+Math.max(maximumExtension(),maximumCompression())+.5;
         return box.minmax(new AABB(mount,mount).inflate(reach));
      }
      if(assembly!=null){var origin=assembly.origin(this);for(var offset:assembly.offsets())box=box.minmax(new AABB(origin.offset(offset)));}
      // Covers every allowed radius/width, mount offset and suspension travel,
      // including a large non-root wheel when only the root draws the belt.
      return box.inflate(10.0);
   }

   private static record TerrainCastResult(double maxExtension, @NotNull Vec3 normal, @Nullable SubLevel subLevel, @Nullable BlockPos minInteractingBlock, @Nullable Vec3 forcePoint) {
   }

   private static class TrackStrengthValueBehaviour extends ScrollValueBehaviour {
      private static final int MAX_STRENGTH = 180;
      private final SableTrackBlockEntity owner;

      public TrackStrengthValueBehaviour(Component label, SableTrackBlockEntity be, ValueBoxTransform slot) {
         super(label, be, slot);
         this.owner = be;
         this.between(5, 180);
      }

      public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
         SuspensionKeyItem.TuningMode mode = player.getMainHandItem().is(TracksItems.SUSPENSION_KEY.asItem())
            ? SuspensionKeyItem.getMode(player.getMainHandItem())
            : (
               player.getOffhandItem().is(TracksItems.SUSPENSION_KEY.asItem())
                  ? SuspensionKeyItem.getMode(player.getOffhandItem())
                  : SuspensionKeyItem.TuningMode.STRENGTH
            );
         this.owner.selectScrollTuningMode(mode.key);
         if (this.owner.level != null && this.owner.level.isClientSide) {
            PacketDistributor.sendToServer(new SelectTrackTuningModePayload(this.owner.getBlockPos(), mode.key), new CustomPacketPayload[0]);
         }

         if (mode == SuspensionKeyItem.TuningMode.STRENGTH) {
            this.value = this.owner.protectedStrengthValue;
         } else {
            this.value = SableTrackBlockEntity.tuningToScroll(this.owner.getTuning(mode.key));
         }

         return new ValueSettingsBoard(mode.title(), 180, 20, ImmutableList.of(mode.title()), new ValueSettingsFormatter(ValueSettings::format));
      }
   }

   private static final class TrackStrengthValueBox extends ValueBoxTransform {
      public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
         Direction facing = (Direction)state.getValue(SableTrackBlock.HORIZONTAL_FACING);
         float yRot = AngleHelper.horizontalAngle(facing) + 180.0F;
         ((PoseTransformStack)TransformStack.of(ms).rotateYDegrees(yRot)).rotateXDegrees(90.0F);
      }

      public boolean testHit(LevelAccessor level, BlockPos pos, BlockState state, Vec3 localHit) {
         if (TracksClient.holdingSuspensionKey && !TracksClient.holdingSuspensionKeyInPositionMode && !TracksClient.holdingSuspensionKeyInResetMode) {
            return true;
         } else {
            Vec3 offset = this.getLocalOffset(level, pos, state);
            return offset != null && localHit.distanceTo(offset) < (double)(this.scale / 3.0F);
         }
      }

      public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
         Direction facing = (Direction)state.getValue(SableTrackBlock.HORIZONTAL_FACING);
         float stateAngle = AngleHelper.horizontalAngle(facing) + 180.0F;
         return VecHelper.rotateCentered(VecHelper.voxelSpace(8.0, 15.5, 11.0), (double)stateAngle, Axis.Y);
      }
   }
}
