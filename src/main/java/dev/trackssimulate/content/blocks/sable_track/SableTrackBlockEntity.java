// Recovered from the verified rebuild.24 artifact; see docs/RESTORE_REBUILD24_2026-10-26.md.
// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.blocks.sable_track;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.TransformStack;
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
import dev.trackssimulate.Tracks;
import dev.trackssimulate.TracksClient;
import dev.trackssimulate.assembly.AssemblyPackets;
import dev.trackssimulate.assembly.TrackAssemblies;
import dev.trackssimulate.assembly.TrackDriveNetwork;
import dev.trackssimulate.config.OffsetScope;
import dev.trackssimulate.config.TrackSetting;
import dev.trackssimulate.config.TracksServerConfig;
import dev.trackssimulate.content.items.SuspensionKeyItem;
import dev.trackssimulate.diagnostics.TrackDiagnostics;
import dev.trackssimulate.index.TracksItems;
import dev.trackssimulate.network.SelectTrackTuningModePayload;
import dev.trackssimulate.physics.SuspensionContactSolver;
import dev.trackssimulate.physics.SuspensionLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
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
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.Block;
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
import org.joml.Matrix3dc;
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
   private static final Map<ServerSubLevel, Integer> SUPPORT_COUNTS = new WeakHashMap<>();
   private static final Map<ServerSubLevel, SableTrackBlockEntity.SuspensionFrame> SUSPENSION_FRAMES = new WeakHashMap<>();
   private SuspensionContactSolver.Contact pendingContact;
   private long diagnosticNanos;
   private final Vector3d queuedForcePos = new Vector3d();
   private final Vector3d queuedForce = new Vector3d();
   private SableTrackBlockEntity.TrackStrengthValueBehaviour strength;
   private double radiusOverride = Double.NaN;
   private double wheelWidth = 1.0;
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
   private boolean passiveMotion;
   private double passiveDistance;
   private double passiveSpeed;
   private double passiveRender;
   private double passiveLastRender;
   private double lastSentPassiveDistance = Double.NaN;
   private double lastSentPassiveSpeed = Double.NaN;
   private long passivePhysicsTick = -100L;
   private long passiveSampleTick = -100L;
   private long passiveReportTick = -100L;
   private boolean lastSentPassive;
   private double touchingFriction = 1.0;
   private double springMultiplier = 1.0;
   private double dampingMultiplier = 1.0;
   private double bumpClearanceMultiplier = 1.0;
   private double bumpForceMultiplier = 1.0;
   private double maxImpulseMultiplier = 1.0;
   private double maxExtensionOverride = Double.NaN;
   private double maxCompression = 0.45;
   private double contactSpread = 1.0;
   private double contactMargin = 0.25;
   private double forwardFriction = 1.0;
   private double sideFriction = 1.0;
   private boolean travelMigrationPending;
   private UUID beltId;
   private int beltIndex;
   private boolean settingsConfigured;
   private Player removalRecipient;
   private long removalTick;
   private BlockState material = AllBlocks.COPYCAT_BASE.getDefaultState();
   private ItemStack materialItem = ItemStack.EMPTY;
   private boolean wheelHidden;
   private double speedMultiplier = 1.0;
   public boolean beltKineticsMuted;
   public List<BlockPos> detachingBeltPeers = List.of();
   private double driveMultiplier = 1.0;
   private double gripMultiplier = 1.0;
   private int lastPropagatedStrength = 16;
   private int protectedStrengthValue = 16;
   private String scrollTuningKey = "strength";
   private boolean liftedUp = false;
   private boolean visualSuspensionHidden = false;
   private DyeColor beltColor = null;
   private ItemStack heldItem = ItemStack.EMPTY;
   private double diagnosticNormal;
   private double diagnosticRpm;
   private double diagnosticForward;
   private double diagnosticSide;
   private double diagnosticRawExtension;
   private double diagnosticCompression;
   private double diagnosticSpring;
   private double diagnosticDamping;
   private double diagnosticStopImpulse;
   private double diagnosticStopPenetration;
   private double diagnosticForwardImpulse;
   private double diagnosticSideImpulse;
   private BlockPos diagnosticHit;
   private int diagnosticQueries;
   private boolean diagnosticLimited;
   private boolean diagnosticContact;

   public double wheelRadius() {
      return Double.isFinite(this.radiusOverride) ? this.radiusOverride : this.effectivePart().radius();
   }

   public double wheelWidth() {
      return this.wheelWidth;
   }

   public boolean passiveMotion() {
      return this.passiveMotion;
   }

   public double passiveDistance(float partial) {
      return this.level.isClientSide ? Mth.lerp((double)partial, this.passiveLastRender, this.passiveRender) : this.passiveDistance;
   }

   public void receivePassiveMotion(UUID id, long tick, double distance, double speed, boolean active) {
      if (this.level != null
         && this.level.isClientSide
         && this.beltIndex == 0
         && id.equals(this.beltId)
         && tick >= this.passiveSampleTick
         && Double.isFinite(distance)
         && Double.isFinite(speed)) {
         if (!this.passiveMotion) {
            this.passiveLastRender = this.passiveRender = distance;
         }

         this.passiveMotion = active;
         this.passiveDistance = distance;
         this.passiveSpeed = speed;
         this.passiveSampleTick = tick;
      }
   }

   public double maximumExtension() {
      return this.effectiveRole() == SableTrackRole.SUSPENSION
         ? (Double.isFinite(this.maxExtensionOverride) ? this.maxExtensionOverride : this.effectivePart().suspensionTravel())
         : 0.0;
   }

   public double maximumCompression() {
      return this.effectiveRole() == SableTrackRole.SUSPENSION ? this.maxCompression : 0.0;
   }

   public double minimumExtension() {
      return this.effectiveRole() == SableTrackRole.SUSPENSION ? this.maximumExtension() - this.maxCompression : 0.0;
   }

   private SuspensionLimits suspensionLimits() {
      return new SuspensionLimits(this.maximumExtension(), this.maxCompression, -this.heightOffset);
   }

   public void validateTravelSettings(Map<TrackSetting, Double> values) {
      if (this.effectiveRole() == SableTrackRole.SUSPENSION) {
         double e = values.getOrDefault(TrackSetting.MAX_EXTENSION, this.maximumExtension());
         double c = values.getOrDefault(TrackSetting.MAX_COMPRESSION, this.maxCompression);
         double height = values.getOrDefault(TrackSetting.HEIGHT, this.heightOffset);

         try {
            new SuspensionLimits(e, c, -height);
         } catch (IllegalArgumentException var9) {
            throw new IllegalArgumentException(
               "轮 " + this.getBlockPos() + "：最大压缩必须满足 0 ≤ 压缩 ≤ 向下偏移＋最大伸长（当前允许 " + TrackSetting.format(e - height) + " 格）；可同时编辑偏移/伸长/压缩，再点同步提交全部输入。本次未修改。"
            );
         }
      }
   }

   public double defaultSettingValue(TrackSetting setting) {
      if (this.effectiveRole() == SableTrackRole.SUSPENSION) {
         double e = Math.max(this.effectivePart().suspensionTravel(), this.heightOffset);
         if (setting == TrackSetting.MAX_EXTENSION) {
            return e;
         }

         if (setting == TrackSetting.MAX_COMPRESSION) {
            return Math.min(setting.defaultValue, e - this.heightOffset);
         }
      }

      return setting.defaultValue;
   }

   public void removalRecipient(Player player) {
      this.removalRecipient = player;
      this.removalTick = this.level == null ? 0L : this.level.getGameTime();
   }

   public Player removalRecipient() {
      return this.level != null && this.removalTick == this.level.getGameTime() ? this.removalRecipient : null;
   }

   public boolean settingsConfigured() {
      return this.settingsConfigured;
   }

   public void applyLocalSettings(Map<TrackSetting, Double> values) {
      this.validateTravelSettings(values);
      boolean changed = false;

      for (Entry<TrackSetting, Double> entry : values.entrySet()) {
         TrackSetting setting = entry.getKey();
         double v = entry.getValue();
         if (setting.supports(this.effectiveRole() == SableTrackRole.SUSPENSION, this.effectiveRole() == SableTrackRole.DRIVE)
            && setting.accepts(v)
            && !(Math.abs(this.getSettingValue(setting.key) - v) < 1.0E-9)) {
            switch (setting) {
               case STRENGTH:
                  if (this.strength != null) {
                     this.strength.value = (int)v;
                  }

                  this.lastPropagatedStrength = this.protectedStrengthValue = (int)v;
                  break;
               case SPRING:
                  this.springMultiplier = v;
                  break;
               case DAMPING:
                  this.dampingMultiplier = v;
                  break;
               case BUMP_CLEARANCE:
                  this.bumpClearanceMultiplier = v;
                  break;
               case BUMP_FORCE:
                  this.bumpForceMultiplier = v;
                  break;
               case MAX_IMPULSE:
                  this.maxImpulseMultiplier = v;
                  break;
               case DRIVE:
                  this.driveMultiplier = v;
                  break;
               case GRIP:
                  this.gripMultiplier = v;
                  break;
               case LATERAL:
                  this.lateralOffset = v;
                  break;
               case LONGITUDINAL:
                  this.longitudinalOffset = v;
                  break;
               case HEIGHT:
                  this.heightOffset = v;
                  break;
               case SCALE:
                  this.radiusOverride = TrackSetting.scaledRadius(this.effectivePart().radius(), v);
                  break;
               case WIDTH:
                  this.wheelWidth = v;
                  break;
               case HIDDEN:
                  this.visualSuspensionHidden = v == 1.0;
                  break;
               case WHEEL_HIDDEN:
                  this.wheelHidden = v == 1.0;
                  break;
               case SPEED:
                  this.speedMultiplier = v;
                  break;
               case MAX_EXTENSION:
                  this.maxExtensionOverride = v;
                  break;
               case MAX_COMPRESSION:
                  this.maxCompression = v;
                  break;
               case CONTACT_SPREAD:
                  this.contactSpread = v;
                  break;
               case CONTACT_MARGIN:
                  this.contactMargin = v;
                  break;
               case FRICTION_FORWARD:
                  this.forwardFriction = v;
                  break;
               case FRICTION_SIDE:
                  this.sideFriction = v;
                  break;
               case THROUGH_AXLE:
                  if (this.level != null && !this.level.isClientSide) {
                     KineticBlockEntity.switchToBlockState(
                        this.level,
                        this.worldPosition,
                        (BlockState)((BlockState)this.getBlockState().setValue(SableTrackBlock.AXLE_MODE, (int)v))
                           .setValue(SableTrackBlock.THROUGH_AXLE, v == 1.0)
                     );
                  }
            }

            changed = true;
         }
      }

      if (changed) {
         if (this.effectiveRole() == SableTrackRole.SUSPENSION) {
            SuspensionLimits limits = this.suspensionLimits();
            this.extension = limits.clamp(this.extension);
            this.targetExtension = limits.clamp(this.targetExtension);
            this.lastExtension = limits.clamp(this.lastExtension);
         }

         this.settingsConfigured = true;
         this.setChanged();
         this.invalidateRenderBoundingBox();
         this.sendData();
      }
   }

   public UUID beltId() {
      return this.beltId;
   }

   public int beltIndex() {
      return this.beltIndex;
   }

   public void setBelt(UUID id, int index) {
      if (!Objects.equals(id, this.beltId)) {
         this.passiveMotion = false;
         this.passiveSpeed = this.passiveDistance = this.passiveRender = this.passiveLastRender = 0.0;
         this.lastSentPassiveDistance = Double.NaN;
      }

      this.beltId = id;
      this.beltIndex = index;
      this.setChanged();
      this.invalidateRenderBoundingBox();
      if (this.level != null && !this.level.isClientSide) {
         this.sendData();
      }
   }

   public boolean hasMaterial() {
      return !this.material.is((Block)AllBlocks.COPYCAT_BASE.get());
   }

   public BlockState material() {
      return this.material;
   }

   public ItemStack materialItem() {
      return this.materialItem.copy();
   }

   public void setMaterial(BlockState state, ItemStack item) {
      this.material = state;
      this.materialItem = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
      this.setChanged();
      if (this.level != null && !this.level.isClientSide) {
         this.sendData();
         Tracks.LOGGER.info("[TS-MATERIAL] pos={} material={} stored={}", new Object[]{this.getBlockPos(), state, !this.materialItem.isEmpty()});
      }
   }

   public boolean isWheelHidden() {
      return this.wheelHidden;
   }

   public double speedMultiplier() {
      return this.speedMultiplier;
   }

   public float kineticStress() {
      return this.stress;
   }

   public float kineticCapacity() {
      return this.capacity;
   }

   public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
      return TrackDriveNetwork.locations(this, super.addPropagationLocations(block, state, neighbours));
   }

   public float propagateRotationTo(KineticBlockEntity target, BlockState from, BlockState to, BlockPos diff, boolean axes, boolean cogs) {
      return TrackDriveNetwork.connected(this, target) ? 1.0F : super.propagateRotationTo(target, from, to, diff, axes, cogs);
   }

   public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
      return TrackDriveNetwork.connected(this, other) || super.isCustomConnection(other, state, otherState);
   }

   public SableTrackBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
      super(type, pos, state);
      if (state.getBlock() instanceof SableTrackBlock block) {
         this.extension = block.builtInPart().suspensionTravel();
         this.lastExtension = this.targetExtension = this.extension;
      }
   }

   public static void applyAllBatchedForces(ServerLevel level, double timeStep) {
      Iterator<Entry<ServerSubLevel, SableTrackBlockEntity.SuspensionFrame>> iterator = SUSPENSION_FRAMES.entrySet().iterator();

      while (iterator.hasNext()) {
         Entry<ServerSubLevel, SableTrackBlockEntity.SuspensionFrame> entry = iterator.next();
         ServerSubLevel host = entry.getKey();
         SableTrackBlockEntity.SuspensionFrame frame = entry.getValue();
         if (host.isRemoved()) {
            iterator.remove();
         } else if (host.getLevel() == level) {
            frame.wheels.removeIf(wheelx -> wheelx.isRemoved() || Sable.HELPER.getContaining(wheelx) != host);
            frame.wheels.sort(Comparator.comparingLong(wheelx -> wheelx.getBlockPos().asLong()));
            ArrayList<SuspensionContactSolver.Contact> contacts = new ArrayList<>();

            for (SableTrackBlockEntity wheel : frame.wheels) {
               if (wheel.pendingContact != null) {
                  contacts.add(wheel.pendingContact);
               }
            }

            long start = System.nanoTime();
            LinkedHashMap<SableTrackBlockEntity, SuspensionContactSolver.FreeBelt> passive = new LinkedHashMap<>();
            HashSet<UUID> checked = new HashSet<>();

            for (SableTrackBlockEntity wheelx : frame.wheels) {
               if (wheelx.beltId != null && checked.add(wheelx.beltId)) {
                  TrackAssemblies.Assembly assembly = TrackAssemblies.get(wheelx);
                  List<SableTrackBlockEntity> members = TrackAssemblies.members(wheelx);
                  if (assembly != null
                     && members.size() == assembly.offsets().size()
                     && !members.stream()
                        .anyMatch(
                           w -> w.isRemoved()
                                 || TrackAssemblies.axis(w) != assembly.axis()
                                 || Sable.HELPER.getContaining(w) != host
                                 || w.effectiveRole() == SableTrackRole.DRIVE
                        )) {
                     SableTrackBlockEntity root = members.getFirst();
                     SuspensionContactSolver.FreeBelt rolling = new SuspensionContactSolver.FreeBelt(
                        (double)members.size() * (Double)TracksServerConfig.PASSIVE_ROLLING_MASS.get(), root.passiveMotion ? root.passiveSpeed : 0.0
                     );
                     double brake = 0.0;

                     for (SableTrackBlockEntity member : members) {
                        brake = Math.max(brake, (double)level.getSignal(member.getBlockPos().above(), Direction.DOWN) / 15.0);
                     }

                     if (brake >= 1.0) {
                        rolling.speed = 0.0;

                        for (SableTrackBlockEntity member : members) {
                           if (member.pendingContact != null) {
                              member.pendingContact.forward().lockRolling();
                           }
                        }
                     } else {
                        for (SableTrackBlockEntity memberx : members) {
                           if (memberx.pendingContact != null) {
                              memberx.pendingContact.forward().couple(rolling, (double)assembly.sides().get(memberx.beltIndex).intValue());
                           }
                        }
                     }

                     rolling.brake(brake);
                     passive.put(root, rolling);
                  }
               }
            }

            SuspensionContactSolver.Result result = SuspensionContactSolver.solve(contacts);

            for (Entry<SableTrackBlockEntity, SuspensionContactSolver.FreeBelt> rollingEntry : passive.entrySet()) {
               SableTrackBlockEntity root = rollingEntry.getKey();
               SuspensionContactSolver.FreeBelt rolling = rollingEntry.getValue();
               if (Double.isFinite(rolling.speed)) {
                  root.passiveMotion = true;
                  root.passiveSpeed = rolling.speed;
                  root.passiveDistance = root.passiveDistance + rolling.speed * timeStep;
                  root.passivePhysicsTick = root.passiveSampleTick = level.getGameTime();
                  if (level.getGameTime() - root.passiveReportTick >= 100L) {
                     root.passiveReportTick = level.getGameTime();
                     List<SableTrackBlockEntity> members = TrackAssemblies.members(root);
                     int grounded = 0;
                     double normal = 0.0;
                     double tangent = 0.0;

                     for (SableTrackBlockEntity memberxx : members) {
                        if (memberxx.pendingContact != null) {
                           grounded++;
                           normal += memberxx.pendingContact.supportImpulse();
                           tangent += Math.abs(memberxx.pendingContact.forward().impulse);
                        }
                     }

                     TrackDiagnostics.motion(
                        root.beltId,
                        System.identityHashCode(host),
                        members.size(),
                        grounded,
                        rolling.speed,
                        root.passiveDistance,
                        normal,
                        tangent,
                        1.0 / rolling.inverseMass
                     );
                  }
               }
            }

            ForceTotal total = new ForceTotal();

            for (SableTrackBlockEntity wheelxx : frame.wheels) {
               SuspensionContactSolver.Contact contact = wheelxx.pendingContact;
               if (contact != null) {
                  wheelxx.queuedForce.zero();

                  for (SuspensionContactSolver.Row row : new SuspensionContactSolver.Row[]{contact.normal(), contact.stop(), contact.forward(), contact.side()}) {
                     if (row != null) {
                        wheelxx.queuedForce.add(row.j[0] * row.impulse, row.j[1] * row.impulse, row.j[2] * row.impulse);
                     }
                  }

                  wheelxx.diagnosticNormal = contact.supportImpulse();
                  wheelxx.diagnosticStopImpulse = contact.stop() == null ? 0.0 : contact.stop().impulse;
                  wheelxx.diagnosticForwardImpulse = contact.forward().impulse;
                  wheelxx.diagnosticSideImpulse = contact.side().impulse;
                  wheelxx.diagnosticLimited = wheelxx.diagnosticNormal <= 1.0E-8 || contact.normal().impulse >= contact.maxNormal() - 1.0E-8;
                  if (wheelxx.queuedForce.isFinite()) {
                     total.applyImpulseAtPoint(host, wheelxx.queuedForcePos, wheelxx.queuedForce);
                  }
               }
            }

            RigidBodyHandle.of(host).applyForcesAndReset(total);
            TrackDiagnostics.solver(host, contacts.size(), result, System.nanoTime() - start);

            for (SableTrackBlockEntity wheelxxx : frame.wheels) {
               wheelxxx.recordPhysicsDiagnostics(host, timeStep);
               wheelxxx.pendingContact = null;
            }

            iterator.remove();
         }
      }

      SUPPORT_COUNTS.keySet().removeIf(hostx -> hostx.isRemoved() || hostx.getLevel() == level);
   }

   public static void clearPhysicsQueue() {
      SUSPENSION_FRAMES.clear();
      SUPPORT_COUNTS.clear();
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

   public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
      if (!this.isRemoved() && !subLevel.isRemoved() && timeStep > 0.0 && !(timeStep > 0.1) && this.effectivePart().appliesPhysics()) {
         long start = System.nanoTime();
         this.queuedForce.zero();
         this.diagnosticNormal = 0.0;
         this.diagnosticRpm = 0.0;
         this.diagnosticForward = 0.0;
         this.diagnosticSide = 0.0;
         this.diagnosticRawExtension = Double.NaN;
         this.diagnosticCompression = 0.0;
         this.diagnosticSpring = 0.0;
         this.diagnosticDamping = 0.0;
         this.diagnosticStopImpulse = this.diagnosticStopPenetration = this.diagnosticForwardImpulse = this.diagnosticSideImpulse = 0.0;
         this.diagnosticHit = null;
         this.diagnosticLimited = false;
         this.diagnosticContact = false;
         this.diagnosticQueries = 0;
         this.pendingContact = null;
         SableTrackBlockEntity.SuspensionFrame frame = SUSPENSION_FRAMES.computeIfAbsent(subLevel, SableTrackBlockEntity.SuspensionFrame::new);
         this.applyTrackPhysics(subLevel, handle, timeStep);
         this.diagnosticNanos = System.nanoTime() - start;
         frame.wheels.add(this);
      }
   }

   private void recordPhysicsDiagnostics(ServerSubLevel subLevel, double timeStep) {
      TrackDiagnostics.sample(
         subLevel,
         this.getBlockPos(),
         this.effectiveRole() == SableTrackRole.DRIVE,
         this.wheelRadius(),
         this.wheelWidth(),
         timeStep,
         this.extension,
         this.maximumExtension(),
         this.queuedForce,
         this.diagnosticNormal,
         this.diagnosticRpm,
         this.diagnosticForward,
         this.diagnosticSide,
         this.diagnosticHit,
         this.diagnosticContact,
         this.diagnosticRawExtension,
         this.diagnosticCompression,
         this.diagnosticSpring,
         this.diagnosticDamping,
         this.diagnosticLimited,
         this.diagnosticQueries,
         this.diagnosticNanos,
         this.maximumCompression(),
         this.contactSpread,
         this.contactMargin,
         this.forwardFriction,
         this.sideFriction,
         this.diagnosticStopImpulse,
         this.diagnosticStopPenetration,
         this.diagnosticForwardImpulse,
         this.diagnosticSideImpulse,
         -this.heightOffset
      );
   }

   private static int supportCount(ServerSubLevel host) {
      return SUPPORT_COUNTS.computeIfAbsent(host, body -> {
         int count = 0;

         for (BlockEntitySubLevelActor actor : body.getPlot().getBlockEntityActors()) {
            if (actor instanceof SableTrackBlockEntity) {
               SableTrackBlockEntity wheel = (SableTrackBlockEntity)actor;
               if (!wheel.isRemoved() && wheel.effectiveRole() == SableTrackRole.SUSPENSION) {
                  count++;
               }
            }
         }

         return Math.max(1, count);
      });
   }

   private void applyTrackPhysics(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
      SableTrackPart part = this.effectivePart();
      Direction facing = (Direction)this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      this.diagnosticRpm = (double)this.getSharedTrackSpeed(facing);
      if (part.role() != SableTrackRole.SUSPENSION) {
         this.extension = 0.0;
         this.liftedUp = true;
      } else {
         Vec3 localPos = this.getTrackCenter(facing);
         this.queuedForcePos.set(localPos.x, localPos.y, localPos.z);
         MassData massData = subLevel.getMassTracker();
         double normalMass = 1.0 / massData.getInverseNormalMass(this.queuedForcePos, OrientedBoundingBox3d.UP);
         double supportedMass = massData.getMass() / (double)supportCount(subLevel);
         if (normalMass > 0.0 && Double.isFinite(normalMass) && supportedMass > 0.0) {
            Pose3d pose = subLevel.logicalPose();
            Vec3i side = Direction.get(AxisDirection.POSITIVE, facing.getAxis()).getNormal();
            Vector3dc sideD = this.getRotatedAxis(side);
            Vector3dc forwardD = this.getRotatedAxis(new Vec3i(side.getZ(), 0, side.getX()));
            SableTrackBlockEntity.TerrainCastResult terrain = this.computeExtensionToTerrain(forwardD, pose, part);
            double rawExtension = terrain.maxExtension() - this.wheelRadius();
            this.diagnosticRawExtension = rawExtension;
            this.diagnosticHit = terrain.minInteractingBlock();
            SuspensionLimits limits = this.suspensionLimits();
            this.extension = limits.clamp(rawExtension);
            this.liftedUp = terrain.minInteractingBlock() == null || !limits.reachable(rawExtension);
            if (!this.liftedUp) {
               this.diagnosticContact = true;
               double effectiveStrength = this.strength == null ? 16.0 : (double)this.strength.getValue();
               double springStrength = effectiveStrength * 8.0 * this.springMultiplier;
               double dampingStrength = 1.6 * Math.sqrt(springStrength * supportedMass) * this.dampingMultiplier;
               double springError = this.maximumExtension() - this.extension;
               Vector3d velocity = Sable.HELPER.getVelocity(this.level, JOMLConversion.toJOML(localPos));
               if (terrain.subLevel() != null) {
                  Vec3 hitLocal = terrain.subLevel().logicalPose().transformPositionInverse(terrain.worldHit());
                  velocity.sub(Sable.HELPER.getVelocity(this.level, JOMLConversion.toJOML(hitLocal)));
               }

               Vector3d localVelocity = pose.transformNormalInverse(velocity);
               Vector3d normal = new Vector3d((double)terrain.normal().getStepX(), (double)terrain.normal().getStepY(), (double)terrain.normal().getStepZ());
               if (terrain.subLevel() != null) {
                  terrain.subLevel().logicalPose().transformNormal(normal);
               }

               pose.transformNormalInverse(normal);
               normal.normalize();
               double normalVelocity = localVelocity.dot(normal);
               double dampingForce = -normalVelocity * dampingStrength;
               double bumpStart = limits.bumpStart(this.bumpClearanceMultiplier);
               double bumpError = Math.max(0.0, bumpStart - rawExtension);
               double bumpStiffness = springStrength * 4.0 * this.bumpForceMultiplier;
               double springForce = springError * springStrength + bumpError * bumpStiffness;
               double stiffness = springStrength + (bumpError > 0.0 ? bumpStiffness : 0.0);
               this.diagnosticStopPenetration = limits.penetration(rawExtension);
               double implicitDamping = dampingStrength + stiffness * timeStep;
               double softness = 1.0 / (timeStep * implicitDamping);
               double maxImpulse = supportedMass * 80.0 * timeStep * this.maxImpulseMultiplier;
               this.diagnosticCompression = springError;
               this.diagnosticSpring = springForce;
               this.diagnosticDamping = dampingForce;
               this.touchingFriction = fudgeFriction(PhysicsBlockPropertyHelper.getFriction(this.level.getBlockState(terrain.minInteractingBlock())));
               double brakeStrength = (double)this.level.getSignal(this.getBlockPos().above(), Direction.DOWN) / 15.0;
               double surfaceBraking = Math.min(this.touchingFriction, 1.0);
               double strengthMul = Math.min(normalMass, effectiveStrength) * 16.0;
               Vector3d forward = new Vector3d(forwardD).fma(-forwardD.dot(normal), normal).normalize();
               Vector3d lateral = new Vector3d(normal).cross(forward);
               if (lateral.dot(sideD) < 0.0) {
                  lateral.negate();
               }

               this.diagnosticForward = localVelocity.dot(forward);
               this.diagnosticSide = localVelocity.dot(lateral);
               double forwardDamping = Math.max(1.0E-6, (0.075 + brakeStrength * 0.3) * surfaceBraking * part.sideGripMultiplier() * strengthMul);
               double sideDamping = Math.max(
                  1.0E-6, 0.6 * this.touchingFriction * strengthMul * part.sideGripMultiplier() * this.gripMultiplier * this.sideFriction
               );
               double motorForce = this.diagnosticRpm * (1.0 - brakeStrength) * surfaceBraking * -0.45 * part.driveMultiplier() * this.driveMultiplier;
               SableTrackBlockEntity.SuspensionFrame frame = SUSPENSION_FRAMES.get(subLevel);
               Vector3d lever = new Vector3d(this.queuedForcePos).sub(frame.centerOfMass);
               this.pendingContact = new SuspensionContactSolver.Contact(
                  contactRow(frame, lever, normal, normalVelocity, springForce / implicitDamping, softness),
                  contactRow(frame, lever, normal, normalVelocity, limits.stopTarget(rawExtension, normal.y, timeStep), 0.0),
                  contactRow(frame, lever, forward, this.diagnosticForward, motorForce / forwardDamping, (1.0 - brakeStrength) / (timeStep * forwardDamping)),
                  contactRow(frame, lever, lateral, this.diagnosticSide, 0.0, 1.0 / (timeStep * sideDamping)),
                  maxImpulse,
                  this.touchingFriction * this.gripMultiplier * this.forwardFriction,
                  this.touchingFriction * this.gripMultiplier * this.sideFriction
               );
            }
         }
      }
   }

   private static SuspensionContactSolver.Row contactRow(
      SableTrackBlockEntity.SuspensionFrame frame, Vector3dc lever, Vector3dc direction, double velocity, double target, double softness
   ) {
      return new SuspensionContactSolver.Row(
         direction.x(), direction.y(), direction.z(), lever.x(), lever.y(), lever.z(), frame.inverseMass, frame.inverseInertia, velocity, target, softness
      );
   }

   public static double fudgeFriction(double realValue) {
      return realValue < 1.0 ? 0.1 + 0.9 * realValue : realValue;
   }

   public void tick() {
      if (this.level != null && !this.level.isClientSide && this.travelMigrationPending) {
         this.travelMigrationPending = false;
         this.setChanged();
         this.sendData();
         Tracks.LOGGER
            .info(
               "[TS-TRAVEL] event=saved_semantics_migrated pos={} downOffset={} extension={} compressionStroke={} relativeLimits=[{},{}]",
               new Object[]{
                  this.worldPosition, -this.heightOffset, this.maximumExtension(), this.maxCompression, this.minimumExtension(), this.maximumExtension()
               }
            );
      }

      TrackDriveNetwork.maintain(this);
      super.tick();
      if (!this.isRemoved()) {
         if (this.beltIndex == 0 && this.beltId != null) {
            if (this.level.isClientSide) {
               this.passiveLastRender = this.passiveRender;
               if (this.passiveMotion) {
                  double age = (double)Math.max(0L, Math.min(4L, this.level.getGameTime() - this.passiveSampleTick));
                  double expected = this.passiveDistance + this.passiveSpeed * age / 20.0;
                  this.passiveRender = this.passiveRender + (expected - this.passiveRender) * 0.65;
               }
            } else {
               if (this.passiveMotion && this.level.getGameTime() - this.passivePhysicsTick > 2L) {
                  this.passiveSpeed = 0.0;
                  this.passiveSampleTick = this.level.getGameTime();
               }

               if (this.level.getGameTime() % 2L == 0L
                  && (
                     this.passiveMotion != this.lastSentPassive
                        || !Double.isFinite(this.lastSentPassiveDistance)
                        || Math.abs(this.passiveSpeed - this.lastSentPassiveSpeed) > 0.01
                        || Math.abs(this.passiveDistance - this.lastSentPassiveDistance) > 0.002
                        || this.passiveMotion && this.level.getGameTime() % 20L == 0L
                  )) {
                  this.lastSentPassive = this.passiveMotion;
                  this.lastSentPassiveDistance = this.passiveDistance;
                  this.lastSentPassiveSpeed = this.passiveSpeed;
                  PacketDistributor.sendToPlayersInDimension(
                     (ServerLevel)this.level,
                     new AssemblyPackets.Motion(
                        this.worldPosition, this.beltId, this.passiveSampleTick, this.passiveDistance, this.passiveSpeed, this.passiveMotion
                     ),
                     new CustomPacketPayload[0]
                  );
                  if (this.level.getGameTime() % 20L == 0L) {
                     this.setChanged();
                  }
               }
            }
         }

         if (this.removalRecipient != null && this.removalTick != this.level.getGameTime()) {
            this.removalRecipient = null;
         }

         if (this.beltId != null && this.level.getGameTime() % 20L == 0L) {
            if (!this.level.isClientSide) {
               TrackAssemblies.reconcile(this);
            } else {
               this.invalidateRenderBoundingBox();
            }
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
            if (part.role() != SableTrackRole.SUSPENSION) {
               this.extension = this.lastExtension = this.targetExtension = 0.0;
            } else if (this.level.isClientSide) {
               this.lastExtension = this.extension;
               this.extension = Mth.lerp(0.7, this.extension, this.targetExtension);
            } else if (Sable.HELPER.getContaining(this) == null) {
               this.extension = this.maximumExtension();
               this.liftedUp = true;
            }

            if (!this.level.isClientSide && ++this.suspensionSyncTicks >= 2) {
               this.suspensionSyncTicks = 0;
               if (!Double.isFinite(this.lastSentExtension)
                  || Math.abs(this.extension - this.lastSentExtension) > 0.002
                  || this.liftedUp != this.lastSentLifted) {
                  this.lastSentExtension = this.extension;
                  this.lastSentLifted = this.liftedUp;
                  this.sendData();
               }
            }

            Direction facing = (Direction)this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
            float speed = this.getSharedTrackSpeed(facing);
            double rpt = (double)(-speed)
               * Math.PI
               * 2.0
               / 60.0
               / 20.0
               * (double)(15 - this.level.getSignal(this.getBlockPos().above(), Direction.DOWN))
               / 15.0;
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
                  double circumference = Math.PI * this.wheelRadius() * 2.0;
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
   }

   private SableTrackBlockEntity.TerrainCastResult computeExtensionToTerrain(Vector3dc forwardD, Pose3dc pose, SableTrackPart part) {
      Direction facing = (Direction)this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      Vec3 center = this.getTrackCenter(facing);
      double bestDistance = this.wheelRadius() + this.maximumExtension() + this.contactMargin + 1.0;
      Direction bestNormal = Direction.UP;
      SubLevel bestSubLevel = null;
      BlockPos bestBlock = null;
      Vec3 bestWorldHit = Vec3.ZERO;
      double spacing = this.wheelRadius() * 0.4 * this.contactSpread;

      for (int i = 0; i < 5; i++) {
         double offset = i < 3 ? (double)(i - 1) * spacing : 0.0;
         double axial = i < 3 ? 0.0 : (double)(i == 3 ? -1 : 1) * this.wheelWidth() * 0.4 * this.contactSpread;
         Vec3 sample = center.add(JOMLConversion.toMojang(forwardD).scale(offset)).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(axial));
         double top = Math.max(0.0, -this.minimumExtension()) + this.contactMargin;
         ClipContext clip = new ClipContext(
            sample.add(0.0, top, 0.0),
            sample.subtract(0.0, this.wheelRadius() + this.maximumExtension() + this.contactMargin, 0.0),
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            Fluid.NONE,
            CollisionContext.empty()
         );
         ((ClipContextExtension)clip).sable$setIgnoredSubLevel(Sable.HELPER.getContaining(this));
         this.diagnosticQueries++;
         BlockHitResult hit = this.level.clip(clip);
         if (hit.getType() != Type.MISS) {
            SubLevel hitBody = Sable.HELPER.getContaining(this.level, hit.getLocation());
            Vec3 worldHit = hitBody == null ? hit.getLocation() : hitBody.logicalPose().transformPosition(hit.getLocation());
            Vec3 localHit = pose.transformPositionInverse(worldHit);
            Vector3d normal = new Vector3d((double)hit.getDirection().getStepX(), (double)hit.getDirection().getStepY(), (double)hit.getDirection().getStepZ());
            if (hitBody != null) {
               hitBody.logicalPose().transformNormal(normal);
            }

            pose.transformNormalInverse(normal);
            if (!(normal.y < 0.5) && !(localHit.y > center.y + top)) {
               double distance = center.y - localHit.y + this.wheelRadius() - Math.sqrt(this.wheelRadius() * this.wheelRadius() - offset * offset);
               if (distance < bestDistance) {
                  bestDistance = distance;
                  bestNormal = hit.getDirection();
                  bestSubLevel = hitBody;
                  bestBlock = hit.getBlockPos().immutable();
                  bestWorldHit = worldHit;
               }
            }
         }
      }

      return new SableTrackBlockEntity.TerrainCastResult(bestDistance, bestNormal, bestSubLevel, bestBlock, bestWorldHit);
   }

   @NotNull
   private Vector3dc getRotatedAxis(Vec3i normal) {
      return new Vector3d((double)normal.getX(), (double)normal.getY(), (double)normal.getZ());
   }

   private Vec3 getTrackCenter(Direction facing) {
      return this.getWheelMountCenter(1.0F);
   }

   public Vec3 getWheelMountCenter(float partialTicks) {
      Direction facing = (Direction)this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      return this.getAnchorCenter()
         .add(Vec3.atLowerCornerOf(facing.getClockWise().getNormal()).scale(this.getLerpedLateralOffset(partialTicks)))
         .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(this.getLerpedLongitudinalOffset(partialTicks)))
         .add(0.0, this.getLerpedHeightOffset(partialTicks), 0.0);
   }

   public Vec3 offsetDelta(double lateral, double longitudinal, double height) {
      Direction facing = (Direction)this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
      return Vec3.atLowerCornerOf(facing.getClockWise().getNormal())
         .scale(lateral - this.lateralOffset)
         .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(longitudinal - this.longitudinalOffset))
         .add(0.0, height - this.heightOffset, 0.0);
   }

   public Vec3 getWheelCenter(float partialTicks) {
      return this.getWheelMountCenter(partialTicks).add(0.0, -this.getLerpedExtension(partialTicks), 0.0);
   }

   public Vec3 getAnchorCenter() {
      if (this.getBlockState().getBlock() instanceof SableTrackBlock block && block.isDirectWheel()) {
         return this.getBlockPos().getCenter();
      }

      return this.getBlockPos().relative((Direction)this.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING)).getCenter();
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

   public double adjustLateralOffset(int direction) {
      return this.adjustOffset("lateral", direction);
   }

   public double adjustLongitudinalOffset(int direction) {
      return this.adjustOffset("longitudinal", direction);
   }

   public double adjustHeightOffset(int direction) {
      return this.adjustOffset("height", direction);
   }

   private double adjustOffset(String key, int direction) {
      TrackSetting setting = TrackSetting.byKey(key);
      double limit = key.equals("height") ? 0.75 : 1.0;
      double value = Mth.clamp((double)Math.round((this.getSettingValue(key) + (double)direction * 0.125) / 0.125) * 0.125, -limit, limit);
      this.setSettingValue(setting.key, value, OffsetScope.ROLE);
      return this.getSettingValue(key);
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
         case "scale" -> this.wheelRadius() / this.effectivePart().radius();
         case "hidden" -> this.visualSuspensionHidden ? 1.0 : 0.0;
         case "wheel_hidden" -> this.wheelHidden ? 1.0 : 0.0;
         case "speed" -> this.speedMultiplier;
         case "max_extension" -> this.effectiveRole() == SableTrackRole.SUSPENSION ? this.maximumExtension() : TrackSetting.MAX_EXTENSION.defaultValue;
         case "max_compression" -> this.effectiveRole() == SableTrackRole.SUSPENSION ? this.maximumCompression() : TrackSetting.MAX_COMPRESSION.defaultValue;
         case "contact_spread" -> this.contactSpread;
         case "contact_margin" -> this.contactMargin;
         case "friction_forward" -> this.forwardFriction;
         case "friction_side" -> this.sideFriction;
         case "through_axle" -> (double)SableTrackBlock.axleMode(this.getBlockState()).id;
         case "width" -> this.wheelWidth();
         case "lateral" -> this.lateralOffset;
         case "longitudinal" -> this.longitudinalOffset;
         case "height" -> this.heightOffset;
         default -> this.getTuning(key);
      };
   }

   public boolean setSettingValue(String key, double value) {
      return this.setSettingValue(key, value, OffsetScope.ROLE);
   }

   public boolean setSettingValue(String key, double value, OffsetScope scope) {
      TrackSetting setting = TrackSetting.byKey(key);
      if (setting != null && setting.accepts(value) && this.level != null && !this.level.isClientSide) {
         switch (key) {
            case "scale":
            case "width":
            case "hidden":
            case "wheel_hidden":
            case "speed":
            case "through_axle":
            case "max_extension":
            case "max_compression":
            case "contact_spread":
            case "contact_margin":
            case "friction_forward":
            case "friction_side":
               throw new IllegalArgumentException("请通过调试 GUI 应用此配置");
            case "strength":
               if (this.strength == null) {
                  return false;
               }

               this.strength.value = (int)value;
               this.applyStrengthToConnectedTrack((int)value);
               break;
            case "lateral":
            case "longitudinal":
            case "height":
               this.applyOffsetToConnectedTrack(key, value, scope);
               break;
            default:
               this.applyTuningToConnectedTrack(key, value);
         }

         return true;
      } else {
         return false;
      }
   }

   private void onTuningChanged() {
      this.settingsConfigured = true;
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
      return this.effectiveRole() != SableTrackRole.SUSPENSION ? 0.0 : Mth.lerp((double)partialTicks, this.lastExtension, this.extension);
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

   public float getSharedTrackAngle(Direction facing, float partialTicks) {
      return this.beltId == null ? this.getLerpedAngle(partialTicks) : TrackAssemblies.angle(this, partialTicks);
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
      if (this.beltId != null) {
         return TrackAssemblies.rpm(this);
      } else {
         return this.effectiveRole() == SableTrackRole.DRIVE
            ? (float)((double)(facing.getAxis() == Axis.X ? -this.getSpeed() : this.getSpeed()) * this.speedMultiplier)
            : 0.0F;
      }
   }

   public boolean isVisualSuspensionHidden() {
      return this.visualSuspensionHidden;
   }

   public DyeColor getBeltColor() {
      return this.beltColor;
   }

   public boolean applyBeltColorToConnectedTrack(DyeColor color) {
      if (this.level != null && !this.level.isClientSide) {
         boolean changed = false;

         for (SableTrackBlockEntity member : TrackAssemblies.members(this)) {
            changed |= member.setBeltColor(color);
         }

         return changed;
      } else {
         return false;
      }
   }

   public void resetTuningToConnectedTrack() {
      if (this.level != null && !this.level.isClientSide) {
         for (SableTrackBlockEntity member : TrackAssemblies.members(this)) {
            member.resetTuning();
         }
      }
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
      this.settingsConfigured = true;
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
      if (this.level != null && !this.level.isClientSide) {
         for (SableTrackBlockEntity member : TrackAssemblies.members(this)) {
            if (member.strength != null) {
               member.strength.value = value;
            }

            member.settingsConfigured = true;
            member.lastPropagatedStrength = value;
            member.protectedStrengthValue = value;
            member.setChanged();
            member.sendData();
         }
      }
   }

   private void applyTuningToConnectedTrack(String key, double value) {
      if (this.level != null && !this.level.isClientSide) {
         for (SableTrackBlockEntity member : TrackAssemblies.members(this)) {
            member.setTuning(key, value);
         }
      }
   }

   private void applyOffsetToConnectedTrack(String key, double value) {
      this.applyOffsetToConnectedTrack(key, value, OffsetScope.ROLE);
   }

   private void applyOffsetToConnectedTrack(String key, double value, OffsetScope scope) {
      if (this.level != null && !this.level.isClientSide) {
         LinkedHashMap<SableTrackBlockEntity, Vec3> deltas = new LinkedHashMap<>();

         for (SableTrackBlockEntity member : TrackAssemblies.members(this)) {
            if (scope.includes(member == this, member.effectiveRole() == this.effectiveRole())) {
               deltas.put(
                  member,
                  member.offsetDelta(
                     key.equals("lateral") ? value : member.lateralOffset,
                     key.equals("longitudinal") ? value : member.longitudinalOffset,
                     key.equals("height") ? value : member.heightOffset
                  )
               );
            }
         }

         TrackAssemblies.GeometryEdit edit = TrackAssemblies.prepareOffsets(this, deltas);

         for (SableTrackBlockEntity memberx : deltas.keySet()) {
            memberx.validateTravelSettings(Map.of(TrackSetting.byKey(key), value));
         }

         for (SableTrackBlockEntity memberx : deltas.keySet()) {
            memberx.setOffset(key, value);
         }

         edit.commit("offset_" + key + "_" + scope);
      }
   }

   private void setOffset(String key, double value) {
      this.settingsConfigured = true;
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
      if (this.beltId != null && this.beltIndex == 0) {
         tag.putDouble("PassiveDistance", this.passiveDistance);
         if (clientPacket) {
            tag.putBoolean("PassiveMotion", this.passiveMotion);
            tag.putDouble("PassiveSpeed", this.passiveSpeed);
            tag.putLong("PassiveSampleTick", this.passiveSampleTick);
         }
      }

      if (Double.isFinite(this.radiusOverride)) {
         tag.putDouble("WheelRadius", this.radiusOverride);
      }

      tag.putDouble("WheelWidth", this.wheelWidth);
      tag.put("WheelMaterial", NbtUtils.writeBlockState(this.material));
      tag.put("WheelMaterialItem", this.materialItem.saveOptional(registries));
      tag.putBoolean("WheelHidden", this.wheelHidden);
      tag.putDouble("BeltSpeedMultiplier", this.speedMultiplier);
      tag.putInt("SuspensionSemantics", 2);
      if (Double.isFinite(this.maxExtensionOverride)) {
         tag.putDouble("MaximumExtension", this.maxExtensionOverride);
      }

      tag.putDouble("MaximumCompression", this.maxCompression);
      tag.putDouble("ContactSpread", this.contactSpread);
      tag.putDouble("ContactMargin", this.contactMargin);
      tag.putDouble("ForwardFriction", this.forwardFriction);
      tag.putDouble("SideFriction", this.sideFriction);
      tag.putBoolean("IndependentOffsets", true);
      tag.putBoolean("SettingsConfigured", this.settingsConfigured);
      if (this.beltId != null) {
         tag.putUUID("AssemblyId", this.beltId);
         tag.putInt("AssemblyNode", this.beltIndex);
      }

      tag.putDouble("Extension", this.effectiveRole() != SableTrackRole.SUSPENSION ? 0.0 : this.extension);
      tag.putBoolean("SuspensionLifted", this.liftedUp);
      tag.putDouble("TouchingFriction", this.touchingFriction);
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
      boolean wasPassive = this.passiveMotion && tag.hasUUID("AssemblyId") && tag.getUUID("AssemblyId").equals(this.beltId);
      this.passiveDistance = Double.isFinite(tag.getDouble("PassiveDistance")) ? tag.getDouble("PassiveDistance") : 0.0;
      this.passiveMotion = clientPacket && tag.getBoolean("PassiveMotion");
      this.passiveSpeed = clientPacket && Double.isFinite(tag.getDouble("PassiveSpeed")) ? tag.getDouble("PassiveSpeed") : 0.0;
      this.passiveSampleTick = tag.getLong("PassiveSampleTick");
      if (!clientPacket || !wasPassive) {
         this.passiveLastRender = this.passiveRender = this.passiveDistance;
      }

      this.radiusOverride = Double.isFinite(tag.getDouble("WheelRadius"))
            && tag.getDouble("WheelRadius") > 0.0
            && tag.getDouble("WheelRadius") <= TrackSetting.SCALE.max
         ? tag.getDouble("WheelRadius")
         : Double.NaN;
      this.wheelWidth = TrackSetting.WIDTH.accepts(tag.getDouble("WheelWidth")) ? tag.getDouble("WheelWidth") : 1.0;
      this.material = tag.contains("WheelMaterial")
         ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("WheelMaterial"))
         : AllBlocks.COPYCAT_BASE.getDefaultState();
      if (this.material.isAir()) {
         this.material = AllBlocks.COPYCAT_BASE.getDefaultState();
      }

      this.materialItem = ItemStack.parseOptional(registries, tag.getCompound("WheelMaterialItem"));
      if (!this.materialItem.isEmpty()) {
         this.materialItem.setCount(1);
      }

      this.wheelHidden = tag.getBoolean("WheelHidden");
      this.speedMultiplier = TrackSetting.SPEED.accepts(tag.getDouble("BeltSpeedMultiplier")) ? tag.getDouble("BeltSpeedMultiplier") : 1.0;
      this.beltId = tag.hasUUID("AssemblyId") ? tag.getUUID("AssemblyId") : null;
      this.beltIndex = tag.getInt("AssemblyNode");
      this.invalidateRenderBoundingBox();
      this.heldItem = ItemStack.parseOptional(registries, tag.getCompound("HeldTrackItem"));
      this.maxExtensionOverride = tag.contains("MaximumExtension") && TrackSetting.MAX_EXTENSION.accepts(tag.getDouble("MaximumExtension"))
         ? tag.getDouble("MaximumExtension")
         : Double.NaN;
      this.maxCompression = readSetting(tag, "MaximumCompression", TrackSetting.MAX_COMPRESSION);
      this.heightOffset = readSetting(tag, "HeightOffset", TrackSetting.HEIGHT);
      if (this.effectiveRole() == SableTrackRole.SUSPENSION) {
         double oldExtension = this.maximumExtension();
         double oldCompression = this.maxCompression;
         SuspensionLimits restored = SuspensionLimits.restore(oldExtension, this.maxCompression, -this.heightOffset, tag.getInt("SuspensionSemantics") < 2);
         if (restored.extension() != oldExtension) {
            this.maxExtensionOverride = restored.extension();
         }

         this.maxCompression = restored.compression();
         if (!clientPacket && (tag.getInt("SuspensionSemantics") < 2 || restored.extension() != oldExtension || this.maxCompression != oldCompression)) {
            this.travelMigrationPending = true;
         }
      }

      this.contactSpread = readSetting(tag, "ContactSpread", TrackSetting.CONTACT_SPREAD);
      this.contactMargin = readSetting(tag, "ContactMargin", TrackSetting.CONTACT_MARGIN);
      this.forwardFriction = readSetting(tag, "ForwardFriction", TrackSetting.FRICTION_FORWARD);
      this.sideFriction = readSetting(tag, "SideFriction", TrackSetting.FRICTION_SIDE);
      if (tag.contains("Extension")) {
         double received = tag.getDouble("Extension");
         if (Double.isFinite(received)) {
            this.targetExtension = Mth.clamp(received, this.minimumExtension(), this.maximumExtension());
            if (!clientPacket) {
               this.lastExtension = this.extension = this.targetExtension;
            }
         }
      }

      this.extension = Mth.clamp(this.extension, this.minimumExtension(), this.maximumExtension());
      this.lastExtension = Mth.clamp(this.lastExtension, this.minimumExtension(), this.maximumExtension());
      this.liftedUp = tag.getBoolean("SuspensionLifted");
      if (tag.contains("TouchingFriction")) {
         this.touchingFriction = tag.getDouble("TouchingFriction");
      }

      if (tag.contains("LateralOffset")) {
         this.lateralOffset = tag.getDouble("LateralOffset");
      }

      if (tag.contains("LongitudinalOffset")) {
         this.longitudinalOffset = tag.getDouble("LongitudinalOffset");
      }

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

      if (!tag.getBoolean("IndependentOffsets") && this.effectiveRole() == SableTrackRole.DRIVE) {
         this.lateralOffset = this.lastLateralOffset = this.longitudinalOffset = this.lastLongitudinalOffset = this.heightOffset = this.lastHeightOffset = 0.0;
      }

      super.read(tag, registries, clientPacket);
      this.settingsConfigured = tag.getBoolean("SettingsConfigured");
      if (!tag.contains("SettingsConfigured")) {
         for (TrackSetting setting : TrackSetting.values()) {
            if (setting != TrackSetting.SCALE && setting != TrackSetting.WIDTH && Math.abs(this.getSettingValue(setting.key) - setting.defaultValue) > 1.0E-8) {
               this.settingsConfigured = true;
            }
         }
      }

      if (this.strength != null) {
         this.protectedStrengthValue = this.strength.getValue();
         this.lastPropagatedStrength = this.protectedStrengthValue;
      }
   }

   private static double readSetting(CompoundTag tag, String key, TrackSetting setting) {
      return tag.contains(key) && setting.accepts(tag.getDouble(key)) ? tag.getDouble(key) : setting.defaultValue;
   }

   protected AABB createRenderBoundingBox() {
      AABB box = new AABB(this.getBlockPos());
      TrackAssemblies.Assembly assembly = TrackAssemblies.get(this);
      if (assembly != null && this.beltIndex == 0) {
         if (assembly != null) {
            BlockPos origin = assembly.origin(this);

            for (BlockPos offset : assembly.offsets()) {
               box = box.minmax(new AABB(origin.offset(offset)));
            }
         }

         return box.inflate(10.0);
      } else {
         Vec3 mount = this.getWheelMountCenter(1.0F);
         double reach = Math.max(this.wheelRadius(), this.wheelWidth() / 2.0) + Math.max(this.maximumExtension(), this.maximumCompression()) + 0.5;
         return box.minmax(new AABB(mount, mount).inflate(reach));
      }
   }

   private static final class SuspensionFrame {
      final List<SableTrackBlockEntity> wheels = new ArrayList<>();
      final double inverseMass;
      final double[] inverseInertia;
      final Vector3d centerOfMass;

      SuspensionFrame(ServerSubLevel host) {
         MassData mass = host.getMassTracker();
         Matrix3dc inertia = mass.getInverseInertiaTensor();
         this.inverseMass = mass.getInverseMass();
         this.centerOfMass = new Vector3d(mass.getCenterOfMass());
         this.inverseInertia = new double[]{
            inertia.m00(), inertia.m10(), inertia.m20(), inertia.m01(), inertia.m11(), inertia.m21(), inertia.m02(), inertia.m12(), inertia.m22()
         };
      }
   }

   private static record TerrainCastResult(
      double maxExtension, @NotNull Direction normal, @Nullable SubLevel subLevel, @Nullable BlockPos minInteractingBlock, Vec3 worldHit
   ) {
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
