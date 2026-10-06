package dev.trackssimulate.wheel;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.trackssimulate.TrackContent;
import dev.trackssimulate.track.TrackPath;
import dev.trackssimulate.physics.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.schematic.requirement.SpecialBlockEntityItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import java.util.*;

public final class WheelBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor,SpecialBlockEntityItemRequirement {
    private UUID loopId;
    private List<BlockPos> relativeNodes=List.of();
    private int[] routeHints=new int[0];
    private int[] routeDirections=new int[0];
    private TrackPath.Path cachedPath;
    private long pathCheckedAt=Long.MIN_VALUE;
    private boolean pathValid;
    private double phase,previousPhase,beltSpeed,angle,previousAngle;
    private boolean driveConflict;
    private boolean driven,beltFault;
    private double driveTarget;
    private long beltWaitSince=Long.MIN_VALUE;
    private BeltSettings beltSettings=BeltSettings.DEFAULT;
    private String beltStatus="等待装配为 Sable 车体";
    private int syncTicks;
    private SuspensionSettings suspension=SuspensionSettings.DEFAULT;
    private WheelGeometry geometry=WheelGeometry.DEFAULT;
    private boolean wheelVisible=true,strutVisible=true;
    private double extension,previousExtension,targetExtension;
    private boolean suspensionFailed;
    private boolean hasSuspensionSample;
    private double savedExtension;
    private double beltSuspensionSpeed;
    private double previousBeltMass;
    private boolean beltSuspensionInitialized;
    private double lastSentExtension;
    private String physicsStatus="静态安装";
    private TrackPath.Path renderPath;
    private long renderedAt=Long.MIN_VALUE;
    private float renderedPartial;
    private List<TrackPath.Wheel> cachedWheels=List.of();
    private BlockState material=AllBlocks.COPYCAT_BASE.getDefaultState();
    private ItemStack materialItem=ItemStack.EMPTY;

    public BlockState material() {return material;}
    public boolean hasMaterial() {return !AllBlocks.COPYCAT_BASE.has(material);}
    public ItemStack materialItem() {return materialItem.copy();}
    public void discardMaterialItem() {materialItem=ItemStack.EMPTY;}
    public void setMaterial(BlockState state,ItemStack paidItem) {
        material=state;materialItem=paidItem.isEmpty()?ItemStack.EMPTY:paidItem.copyWithCount(1);
        setChanged();sendData();
    }
    @Override public ItemRequirement getRequiredItems(BlockState state) {
        return materialItem.isEmpty()?ItemRequirement.NONE:new ItemRequirement(ItemRequirement.ItemUseType.CONSUME,materialItem.copy());
    }

    public WheelBlockEntity(BlockPos pos,BlockState state) { super(TrackContent.WHEEL_ENTITY.get(),pos,state); }
    public WheelKind kind() { return ((WheelBlock)getBlockState().getBlock()).kind; }
    public WheelGeometry geometry() { return geometry; }
    public double radius() { return kind().radius*geometry.radiusScale(); }
    public double width() { return kind().width*geometry.widthScale(); }
    public boolean wheelVisible() { return wheelVisible; }
    public boolean strutVisible() { return strutVisible; }
    public Vec3 anchorOffset() {
        var o=geometry.offset(axis().ordinal());return new Vec3(o.x(),o.y(),o.z());
    }
    public Vec3 wheelCenter(float partial) { return Vec3.atCenterOf(worldPosition).add(anchorOffset()).add(0,-renderExtension(partial),0); }
    public double axialCenter() { return axial(worldPosition,axis())+.5+geometry.axial(); }
    public boolean samePlane(WheelBlockEntity other) { return axis()==other.axis()&&Math.abs(axialCenter()-other.axialCenter())<1e-6; }
    public Direction.Axis axis() { return getBlockState().getValue(WheelBlock.AXIS); }
    public UUID loopId() { return loopId; }
    public BeltSettings beltSettings() { return beltSettings; }
    public double beltSpeed() { return beltSpeed; }
    public double speedForMass(double mass) {
        double speed=previousBeltMass>0?beltSpeed*Math.min(1,Math.sqrt(previousBeltMass/mass)):beltSpeed;
        previousBeltMass=mass;return speed;
    }
    public boolean driven() { return driven; }
    public double driveTarget() { return driveTarget; }
    public String beltStatus() { return beltStatus; }
    public void beltWaiting() {
        if(beltWaitSince==Long.MIN_VALUE) beltWaitSince=level.getGameTime();
        else if(level.getGameTime()-beltWaitSince>20) throw new IllegalArgumentException("负重轮物理对象未就绪");
    }
    public void beltReady() { beltWaitSince=Long.MIN_VALUE; }
    public boolean beltContactActive() { var r=root();return r!=null&&!r.beltFault&&r.path()!=null; }
    public void configureBelt(BeltSettings settings) {
        beltSettings=settings;beltFault=false;beltReady();pathCheckedAt=Long.MIN_VALUE;renderedAt=Long.MIN_VALUE;setChanged();sendData();
    }
    public void physicsContactExtension(double value) {
        if(!Double.isFinite(value)||Math.abs(value)>8) throw new IllegalArgumentException("悬挂位置异常");
        extension=value;
        if(suspensionEnabled()) { savedExtension=value;hasSuspensionSample=true; }
        pathCheckedAt=Long.MIN_VALUE;renderedAt=Long.MIN_VALUE;
    }
    public void beltSample(double speed,double dt,int contacts,double load) {
        if(!Double.isFinite(speed)||Math.abs(speed)>256) throw new IllegalArgumentException("履带速度异常");
        beltSpeed=speed;phase+=speed*dt;
        beltStatus=String.format(Locale.ROOT,"%s；带速 %.2f 格/秒；接触 %d；反力 %.0f",driveConflict?"动力冲突，制动":driven?"动力驱动":"自由滚动",speed,contacts,load);
    }
    public void beltFailed(String message) { beltFault=true;beltSpeed=0;beltStatus="带面物理暂停："+message+"；保存履带参数可重试";setChanged();sendData(); }
    public List<BlockPos> nodes() { return relativeNodes.stream().map(worldPosition::offset).toList(); }
    public boolean controller() { return loopId!=null&&!relativeNodes.isEmpty()&&relativeNodes.getFirst().equals(BlockPos.ZERO); }
    public WheelBlockEntity root() {
        if(level==null||loopId==null||relativeNodes.isEmpty()) return null;
        BlockPos p=worldPosition.offset(relativeNodes.getFirst());
        if(!level.hasChunkAt(p)) return null;
        return level.getBlockEntity(p) instanceof WheelBlockEntity w&&loopId.equals(w.loopId)&&w.controller()?w:null;
    }
    public void link(UUID id,List<BlockPos> nodes,int[] hints,int[] directions) {
        routeDirections=directions.clone();
        routeHints=hints.clone();loopId=id;relativeNodes=nodes.stream().map(p->p.subtract(worldPosition)).toList();cachedPath=null;pathCheckedAt=Long.MIN_VALUE;
        phase=previousPhase=beltSpeed=0;driveConflict=false;beltFault=false;refreshStress();setChanged();sendData();
    }
    public void unlink() { routeDirections=new int[0];routeHints=new int[0];loopId=null;relativeNodes=List.of();cachedPath=null;pathCheckedAt=Long.MIN_VALUE;phase=previousPhase=beltSpeed=0;refreshStress();setChanged();sendData(); }
    private void refreshStress() {
        if(level!=null&&!level.isClientSide&&hasNetwork()) getOrCreateNetwork().updateStressFor(this,calculateStressApplied());
    }
    public TrackPath.Path path() {
        if(!controller()||level==null) return null;
        if(pathCheckedAt==level.getGameTime()) return pathValid?cachedPath:null;
        pathCheckedAt=level.getGameTime();pathValid=false;
        List<TrackPath.Wheel> wheels=new ArrayList<>();
        for(BlockPos p:nodes()) {
            if(!level.hasChunkAt(p)||!(level.getBlockEntity(p) instanceof WheelBlockEntity w)||!loopId.equals(w.loopId)||w.axis()!=axis()) return null;
            if(!samePlane(w)) return null;
            wheels.add(w.planningWheel(worldPosition,routeHints.length>wheels.size()?routeHints[wheels.size()]:0));
        }
        if(cachedPath==null) {
            try { cachedPath=routeDirections.length==wheels.size()?TrackPath.withDirections(wheels,Arrays.stream(routeDirections).boxed().toList()):TrackPath.build(wheels);
                routeDirections=cachedPath.wheelDirections().stream().mapToInt(Integer::intValue).toArray();
            } catch(IllegalArgumentException ignored) { return null; }
        } else if(!wheels.equals(cachedWheels)) {
            try { cachedPath=TrackPath.deform(wheels,cachedPath); } catch(IllegalArgumentException ignored) { return null; }
        }
        cachedWheels=List.copyOf(wheels);
        pathValid=true;return cachedPath;
    }
    public TrackPath.Point planarCenter(BlockPos origin,float partial) {
        TrackPath.Point p=planar(worldPosition.subtract(origin),axis());
        return new TrackPath.Point(p.x()+geometry.forward(),p.y()+geometry.vertical()-(axis()==Direction.Axis.Y?0:renderExtension(partial)));
    }
    public TrackPath.Path renderPath(float partial) {
        TrackPath.Path topology=path();if(topology==null||level==null) return null;
        if(renderedAt==level.getGameTime()&&renderedPartial==partial&&renderPath!=null) return renderPath;
        List<TrackPath.Wheel> wheels=new ArrayList<>();
        for(BlockPos p:nodes()) {
            if(!(level.getBlockEntity(p) instanceof WheelBlockEntity w)) return null;
            wheels.add(w.pathWheel(worldPosition,partial,0));
        }
        try { renderPath=TrackPath.deform(wheels,topology); } catch(IllegalArgumentException ignored) { return null; }
        renderedAt=level.getGameTime();renderedPartial=partial;return renderPath;
    }
    public SuspensionSettings suspensionSettings() { return suspension; }
    public boolean suspensionEnabled() { return kind()==WheelKind.ROAD&&axis()!=Direction.Axis.Y&&getBlockState().getValue(WheelBlock.SUSPENSION)&&!suspensionFailed; }
    public boolean roadColliderEnabled() { return kind()==WheelKind.ROAD&&!suspensionFailed; }
    public double renderExtension(float partial) { return previousExtension+(extension-previousExtension)*partial; }
    public String physicsStatus() { return physicsStatus; }
    public double suspensionStart() { return !suspensionEnabled()?0:hasSuspensionSample?suspension.clamp(savedExtension):suspension.rest(); }
    public dev.trackssimulate.physics.contact.BeltSuspension beltSuspension(dev.trackssimulate.physics.contact.ContactSolver.Body host,dev.trackssimulate.physics.contact.V3 axis,double dt) {
        initializeBeltSuspension();
        return new dev.trackssimulate.physics.contact.BeltSuspension(host,axis,suspension,renderExtension(1),beltSuspensionSpeed,dt);
    }
    public void initializeBeltSuspension() {
        if(!beltSuspensionInitialized) {physicsContactExtension(suspensionStart());beltSuspensionSpeed=0;beltSuspensionInitialized=true;}
    }
    public void beltSuspensionSample(dev.trackssimulate.physics.contact.BeltSuspension state) {
        beltSuspensionSpeed=state.speed();physicsContactExtension(state.extension());
    }
    public void resetBeltSuspension() {beltSuspensionInitialized=false;beltSuspensionSpeed=0;}
    public double configuredExtension(SuspensionSettings settings,boolean enabled) {
        return kind()==WheelKind.ROAD&&enabled&&axis()!=Direction.Axis.Y&&Sable.HELPER.getContaining(this) instanceof ServerSubLevel
            ?hasSuspensionSample?settings.clamp(savedExtension):settings.rest():0;
    }
    /** Shared by hover previews, committed selection and legacy routes lacking saved directions. */
    public TrackPath.Wheel planningWheel(BlockPos origin,int hint) {
        return pathWheel(origin,1,hint);
    }
    public TrackPath.Wheel pathWheel(BlockPos origin,float partial,int hint) {
        int preferred=kind()==WheelKind.ROAD&&axis()!=Direction.Axis.Y?3:0;
        return new TrackPath.Wheel(planarCenter(origin,partial),radius()+.025,hint,preferred,preferred==3,kind()==WheelKind.DRIVE);
    }
    public void physicsFailed() { suspensionFailed=true;setChanged();sendData(); }
    public void physicsSample(double value,String status) {
        previousExtension=extension;
        boolean changed=Math.abs(lastSentExtension-value)>.0001||!physicsStatus.equals(status);
        extension=value;physicsStatus=status;
        if(suspensionEnabled()&&Sable.HELPER.getContaining(this) instanceof ServerSubLevel) { savedExtension=value;hasSuspensionSample=true; }
        pathCheckedAt=Long.MIN_VALUE;renderedAt=Long.MIN_VALUE;
        if(changed) { lastSentExtension=value;setChanged();sendData(); }
    }
    public void configureSuspension(SuspensionSettings settings,boolean enabled) {
        if(settings.equals(suspension)&&enabled==getBlockState().getValue(WheelBlock.SUSPENSION)&&!suspensionFailed) return;
        RoadWheelPhysics.release(this);suspension=settings;suspensionFailed=false;
        if(level!=null) level.setBlock(worldPosition,getBlockState().setValue(WheelBlock.SUSPENSION,enabled),3);
        if(!enabled) hasSuspensionSample=false;
        extension=previousExtension=configuredExtension(settings,enabled);
        physicsStatus="等待物理更新";setChanged();sendData();
    }
    public void configureWheel(SuspensionSettings settings,boolean enabled,WheelGeometry nextGeometry,boolean visible,boolean showStrut) {
        resetBeltSuspension();
        if(!geometry.equals(nextGeometry)) {
            RoadWheelPhysics.release(this);geometry=nextGeometry;suspensionFailed=false;
        }
        wheelVisible=visible;strutVisible=showStrut;
        configureSuspension(settings,enabled);
        WheelBlockEntity root=root();
        if(root!=null) { root.pathCheckedAt=Long.MIN_VALUE;root.renderedAt=Long.MIN_VALUE; }
        pathCheckedAt=Long.MIN_VALUE;renderedAt=Long.MIN_VALUE;setChanged();sendData();
    }
    @Override public void sable$tick(ServerSubLevel subLevel) {
        if(controller())BeltPhysics.register(this,subLevel);
        if(kind()==WheelKind.ROAD) RoadWheelPhysics.tick(this,subLevel);
        if(controller()&&driven&&Math.abs(driveTarget)>.001&&!beltFault)
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.require(subLevel.getLevel()).getPipeline().wakeUp(subLevel);
    }
    @Override public void sable$physicsTick(ServerSubLevel subLevel,dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle handle,double dt) {
        if(controller()&&!beltFault) BeltPhysics.step(this,subLevel,dt);
    }
    @Override public void remove() { RoadWheelPhysics.release(this);super.remove(); }
    @Override public void onChunkUnloaded() { RoadWheelPhysics.release(this);super.onChunkUnloaded(); }
    public static int axial(BlockPos p,Direction.Axis axis) { return axis.choose(p.getX(),p.getY(),p.getZ()); }
    public static TrackPath.Point planar(BlockPos p,Direction.Axis axis) {
        return switch(axis) { case X->new TrackPath.Point(p.getZ(),p.getY());case Y->new TrackPath.Point(p.getX(),p.getZ());case Z->new TrackPath.Point(p.getX(),p.getY()); };
    }
    public double renderPhase(float partial) { return previousPhase+(phase-previousPhase)*partial; }
    public double renderAngle(float partial) {
        WheelBlockEntity root=root();
        if(root!=null&&root.path()!=null) return Math.toDegrees(root.renderPhase(partial)/radius())*root.directionAt(worldPosition)*(axis()==Direction.Axis.Z?1:-1);
        return previousAngle+(angle-previousAngle)*partial;
    }
    public int directionAt(BlockPos pos) {
        TrackPath.Path p=path();int index=nodes().indexOf(pos);
        return p!=null&&index>=0?p.wheelDirections().get(index):1;
    }
    public double beltWidth() {
        double width=Double.POSITIVE_INFINITY;
        if(level!=null) for(BlockPos p:nodes()) if(level.getBlockEntity(p) instanceof WheelBlockEntity w) width=Math.min(width,w.width());
        if(!Double.isFinite(width)) width=1;
        return width*0.85*beltSettings.widthScale();
    }
    public boolean conflict() { return driveConflict; }
    @Override public void tick() {
        super.tick();
        if(level instanceof net.minecraft.server.level.ServerLevel server&&loopId!=null&&dev.trackssimulate.track.BeltLifecycle.isBroken(server,loopId)) unlink();
        if(level!=null&&!level.isClientSide&&getBlockState().getValue(WheelBlock.BELTED)!=beltContactActive())
            level.setBlock(worldPosition,getBlockState().setValue(WheelBlock.BELTED,beltContactActive()),3);
        if(level!=null&&kind()==WheelKind.ROAD) {
            if(level.isClientSide) { previousExtension=extension;extension=targetExtension; }
            else if(!roadColliderEnabled()||!(Sable.HELPER.getContaining(this) instanceof ServerSubLevel)) {
                RoadWheelPhysics.release(this);
                if(!suspensionFailed) physicsSample(0,"静态安装（装配为 Sable 车体后承载）");
            }
        }
        previousAngle=angle;angle+=getSpeed()*0.3;
        previousPhase=phase;
        if(!controller()) {
            if(level!=null&&!level.isClientSide&&loopId!=null&&++syncTicks%20==0) {
                if(relativeNodes.isEmpty()) { dev.trackssimulate.track.BeltLifecycle.breakLoop(this,null,"empty_loop");return; }
                BlockPos p=worldPosition.offset(relativeNodes.getFirst());
                if(level.hasChunkAt(p)&&root()==null) dev.trackssimulate.track.BeltLifecycle.breakLoop(this,null,"missing_root");
            }
            return;
        }
        TrackPath.Path path=path();
        if(level==null) return;
        if(!level.isClientSide) {
            double speed=0;boolean hasDrive=false,conflict=false;
            if(path!=null) for(BlockPos p:nodes()) {
                WheelBlockEntity w=(WheelBlockEntity)level.getBlockEntity(p);
                if(w.kind()!=WheelKind.DRIVE||!w.hasSource()) continue;
                double candidate=w.getSpeed()*Math.PI*2/60*w.radius()*directionAt(p)*(axis()==Direction.Axis.Z?1:-1);
                if(hasDrive&&Math.abs(candidate-speed)>0.01) conflict=true;
                else speed=candidate;
                hasDrive=true;
            }
            double next=path==null||conflict?0:beltSettings.drivenSpeed(speed);
            boolean changed=conflict!=driveConflict||driven!=hasDrive;
            driven=hasDrive;driveTarget=next;driveConflict=conflict;
            if(!(Sable.HELPER.getContaining(this) instanceof ServerSubLevel)) { beltSpeed=beltSettings.limitedSpeed(next);phase+=beltSpeed/20; }
            if(changed||++syncTicks%2==0) { setChanged();sendData(); }
            if(path==null&&syncTicks%20==0) {
                boolean missing=false;
                for(BlockPos p:nodes()) if(level.hasChunkAt(p)&&(!(level.getBlockEntity(p) instanceof WheelBlockEntity w)||!loopId.equals(w.loopId))) missing=true;
                if(missing) dev.trackssimulate.track.BeltLifecycle.breakLoop(this,null,"missing_member");
            }
        } else if(path!=null) phase+=beltSpeed/20;
    }
    @Override public float calculateStressApplied() { lastStressApplied=kind()==WheelKind.DRIVE&&loopId!=null?4:0;return lastStressApplied; }
    @Override protected void write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket) {
        super.write(tag,registries,clientPacket);
        tag.put("WheelMaterial",NbtUtils.writeBlockState(material));
        if(!materialItem.isEmpty())tag.put("WheelMaterialItem",materialItem.save(registries));
        if(loopId!=null) { tag.putIntArray("TrackRouteHints",routeHints);tag.putUUID("TrackLoop",loopId);tag.putLongArray("TrackNodes",relativeNodes.stream().mapToLong(BlockPos::asLong).toArray()); }
        tag.putIntArray("TrackDirections",routeDirections);
        CompoundTag spring=new CompoundTag();
        spring.putDouble("Min",suspension.minimum());spring.putDouble("Max",suspension.maximum());spring.putDouble("Rest",suspension.rest());
        spring.putDouble("Stiffness",suspension.stiffness());spring.putDouble("Damping",suspension.damping());spring.putDouble("Mass",suspension.mass());
        tag.put("Suspension",spring);
        CompoundTag belt=new CompoundTag();double[] beltValues=beltSettings.values();
        for(int i=0;i<beltValues.length;i++)belt.putDouble(BeltSettings.KEYS[i],beltValues[i]);tag.put("BeltPhysics",belt);
        if(clientPacket) tag.putString("BeltStatus",beltStatus);
        CompoundTag shape=new CompoundTag();shape.putDouble("RadiusScale",geometry.radiusScale());shape.putDouble("WidthScale",geometry.widthScale());
        shape.putDouble("Forward",geometry.forward());shape.putDouble("Vertical",geometry.vertical());shape.putDouble("Axial",geometry.axial());
        tag.put("WheelGeometry",shape);tag.putBoolean("WheelVisible",wheelVisible);tag.putBoolean("StrutVisible",strutVisible);
        if(hasSuspensionSample) tag.putDouble("SavedExtension",savedExtension);
        if(clientPacket) { tag.putDouble("WheelExtension",extension);tag.putString("PhysicsStatus",physicsStatus);tag.putBoolean("PhysicsFailed",suspensionFailed); }
        tag.putDouble("TrackPhase",phase);tag.putDouble("TrackSpeed",beltSpeed);tag.putBoolean("DriveConflict",driveConflict);
    }
    @Override protected void read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket) {
        super.read(tag,registries,clientPacket);
        material=tag.contains("WheelMaterial")?NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK),tag.getCompound("WheelMaterial")):AllBlocks.COPYCAT_BASE.getDefaultState();
        materialItem=tag.contains("WheelMaterialItem")?ItemStack.parseOptional(registries,tag.getCompound("WheelMaterialItem")):ItemStack.EMPTY;
        if(material.isAir()||hasMaterial()&&(!(materialItem.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)||!material.is(blockItem.getBlock()))) {
            material=AllBlocks.COPYCAT_BASE.getDefaultState();materialItem=ItemStack.EMPTY;
        }
        if(!hasMaterial())materialItem=ItemStack.EMPTY;
        if(!materialItem.isEmpty())materialItem.setCount(1);
        if(tag.contains("BeltPhysics")) {
            var b=tag.getCompound("BeltPhysics");
            try {
                double[] values=BeltSettings.DEFAULT.values();
                for(int i=0;i<values.length;i++)if(b.contains(BeltSettings.KEYS[i]))values[i]=b.getDouble(BeltSettings.KEYS[i]);
                if(!b.contains("LateralFriction"))values[7]=Math.max(1.8,values[0]);
                beltSettings=BeltSettings.from(values);
            }
            catch(IllegalArgumentException ignored) { beltSettings=BeltSettings.DEFAULT; }
        }
        if(clientPacket) beltStatus=tag.getString("BeltStatus");
        WheelGeometry previousGeometry=geometry;
        geometry=WheelGeometry.DEFAULT;
        if(tag.contains("WheelGeometry")) {
            CompoundTag g=tag.getCompound("WheelGeometry");
            try { geometry=new WheelGeometry(g.getDouble("RadiusScale"),g.getDouble("WidthScale"),g.getDouble("Forward"),g.getDouble("Vertical"),g.getDouble("Axial")); }
            catch(IllegalArgumentException ignored) {}
        }
        wheelVisible=!tag.contains("WheelVisible")||tag.getBoolean("WheelVisible");strutVisible=!tag.contains("StrutVisible")||tag.getBoolean("StrutVisible");
        if(!geometry.equals(previousGeometry)) { pathCheckedAt=Long.MIN_VALUE;renderedAt=Long.MIN_VALUE; }
        if(!clientPacket) { savedExtension=tag.getDouble("SavedExtension");hasSuspensionSample=tag.contains("SavedExtension")&&Double.isFinite(savedExtension); }
        if(tag.contains("Suspension")) {
            CompoundTag s=tag.getCompound("Suspension");
            try { suspension=new SuspensionSettings(s.getDouble("Min"),s.getDouble("Max"),s.getDouble("Rest"),s.getDouble("Stiffness"),s.getDouble("Damping"),s.getDouble("Mass")); }
            catch(IllegalArgumentException ignored) { suspension=SuspensionSettings.DEFAULT; }
        }
        if(clientPacket) {
            targetExtension=tag.getDouble("WheelExtension");
            if(!Double.isFinite(targetExtension)||Math.abs(targetExtension)>8) targetExtension=0;
            physicsStatus=tag.getString("PhysicsStatus");suspensionFailed=tag.getBoolean("PhysicsFailed");
            pathCheckedAt=Long.MIN_VALUE;renderedAt=Long.MIN_VALUE;
        }
        UUID next=tag.hasUUID("TrackLoop")?tag.getUUID("TrackLoop"):null;
        long[] positions=tag.getLongArray("TrackNodes");
        if(positions.length<2||positions.length>TrackPath.MAX_WHEELS) next=null;
        List<BlockPos> offsets=next==null?List.of():Arrays.stream(positions).mapToObj(BlockPos::of).toList();
        int[] directions=tag.getIntArray("TrackDirections");
        if(directions.length!=offsets.size()||Arrays.stream(directions).anyMatch(d->d!=1&&d!=-1)) directions=new int[0];
        if(!Arrays.equals(routeDirections,directions)) { cachedPath=null;pathCheckedAt=Long.MIN_VALUE;renderedAt=Long.MIN_VALUE; }
        routeDirections=directions;
        int[] hints=tag.getIntArray("TrackRouteHints");
        if(hints.length!=offsets.size()) hints=new int[offsets.size()];
        for(int i=0;i<hints.length;i++) if(hints[i]<0||hints[i]>4) hints[i]=0;
        if(!Arrays.equals(hints,routeHints)) { cachedPath=null;pathCheckedAt=Long.MIN_VALUE; }
        routeHints=hints;
        if(!Objects.equals(next,loopId)||!offsets.equals(relativeNodes)) { cachedPath=null;pathCheckedAt=Long.MIN_VALUE; }
        loopId=next;relativeNodes=offsets;
        phase=tag.getDouble("TrackPhase");beltSpeed=tag.getDouble("TrackSpeed");
        if(!Double.isFinite(phase)) phase=0;if(!Double.isFinite(beltSpeed)) beltSpeed=0;
        previousPhase=phase;driveConflict=tag.getBoolean("DriveConflict");
    }
}
