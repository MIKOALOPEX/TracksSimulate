package dev.trackssimulate.assembly;

import com.simibubi.create.AllItems;
import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.*;
import dev.trackssimulate.trackpath.OrderedBeltPath;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** One authoritative topology per assembly. Wheels persist only id/index;
 * missing assemblies clear stale members, so no unbounded broken-id ledger. */
public final class TrackAssemblies extends SavedData {
    public record Assembly(UUID id,List<BlockPos> offsets,Direction.Axis axis,List<Integer> sides,boolean refundable,int routingVersion) {
        public Assembly { offsets=List.copyOf(offsets); sides=List.copyOf(sides); }
        public BlockPos origin(SableTrackBlockEntity be){return be.getBlockPos().subtract(offsets.get(be.beltIndex()));}
    }
    private final Map<UUID,Assembly> assemblies=new HashMap<>();
    private final Set<UUID> attemptedUpgrades=new HashSet<>();
    private final Set<UUID> removing=new HashSet<>();
    public static final Map<UUID,Assembly> CLIENT=new HashMap<>();
    private record MemberCache(long tick,Assembly assembly,BlockPos origin,List<SableTrackBlockEntity> wheels) {}
    private static final Map<net.minecraft.world.level.Level,Map<UUID,MemberCache>> MEMBERS=new java.util.concurrent.ConcurrentHashMap<>();
    public static void clearCaches(){MEMBERS.clear();TrackDriveNetwork.clear();AssemblyGeometry.clear();}
    public static void clearClientCaches(){CLIENT.clear();MEMBERS.keySet().removeIf(l->l.isClientSide);}
    public static TrackAssemblies data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(TrackAssemblies::new,TrackAssemblies::load),"trackssimulate_assemblies_v1");
    }
    private static TrackAssemblies load(CompoundTag tag,HolderLookup.Provider provider) {
        var data=new TrackAssemblies();
        for(var raw:tag.getList("Assemblies",Tag.TAG_COMPOUND)) {
            var t=(CompoundTag)raw;
            try {
                var offsets=Arrays.stream(t.getLongArray("Nodes")).mapToObj(BlockPos::of).toList();
                if(offsets.size()<2||offsets.size()>OrderedBeltPath.MAX_NODES||!offsets.getFirst().equals(BlockPos.ZERO))continue;
                var a=new Assembly(t.getUUID("Id"),offsets,Direction.Axis.valueOf(t.getString("Axis")),Arrays.stream(t.getIntArray("Sides")).boxed().toList(),t.getBoolean("Refundable"),t.getInt("RoutingVersion"));if(a.sides.size()==offsets.size()&&a.sides.stream().allMatch(v->v==1||v==-1))data.assemblies.put(a.id,a);
            }catch(IllegalArgumentException e){Tracks.LOGGER.warn("[TS-ASSEMBLY] invalid saved assembly: {}",e.getMessage());}
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider provider) {
        var list=new ListTag();for(var a:assemblies.values()) {
            var t=new CompoundTag();t.putUUID("Id",a.id);t.putLongArray("Nodes",a.offsets.stream().mapToLong(BlockPos::asLong).toArray());t.putString("Axis",a.axis.name());t.putBoolean("Refundable",a.refundable);t.putInt("RoutingVersion",a.routingVersion);t.putIntArray("Sides",a.sides.stream().mapToInt(Integer::intValue).toArray());list.add(t);
        }
        tag.put("Assemblies",list);return tag;
    }
    public static Assembly get(SableTrackBlockEntity be) {
        if(be.beltId()==null||be.getLevel()==null)return null;
        var a=be.getLevel() instanceof ServerLevel server?data(server).assemblies.get(be.beltId()):CLIENT.get(be.beltId());
        return a!=null&&be.beltIndex()>=0&&be.beltIndex()<a.offsets.size()?a:null;
    }
    public static List<SableTrackBlockEntity> members(SableTrackBlockEntity be) {
        var a=get(be);if(a==null)return List.of(be);
        var level=be.getLevel();long tick=level.getGameTime();
        var cache=MEMBERS.computeIfAbsent(level,l->new java.util.concurrent.ConcurrentHashMap<>());
        var origin=a.origin(be);var previous=cache.get(a.id);
        if(previous!=null&&previous.tick==tick&&previous.assembly==a&&previous.origin.equals(origin))return previous.wheels;
        var out=new ArrayList<SableTrackBlockEntity>();
        for(int i=0;i<a.offsets.size();i++) {
            var p=origin.offset(a.offsets.get(i));
            if(be.getLevel().hasChunkAt(p)&&be.getLevel().getBlockEntity(p) instanceof SableTrackBlockEntity other
                &&a.id.equals(other.beltId())&&other.beltIndex()==i)out.add(other);
        }
        var result=List.copyOf(out);cache.put(a.id,new MemberCache(tick,a,origin,result));
        if(cache.size()>512)cache.entrySet().removeIf(e->tick-e.getValue().tick>200);
        return result;
    }
    public static void reconcile(SableTrackBlockEntity be) {
        var a=get(be);
        if(be.beltId()!=null&&a==null){be.setBelt(null,0);return;}
        if(a==null||be.beltIndex()!=0||a.routingVersion>=OrderedBeltPath.ROUTING_VERSION
            ||!(be.getLevel() instanceof ServerLevel level))return;
        var data=data(level);
        if(data.attemptedUpgrades.contains(a.id)||members(be).size()!=a.offsets.size())return;
        data.attemptedUpgrades.add(a.id);
        try {
            // One topology migration after all members load, not settings sync.
            // Keep UUID, node order, wheel parameters and paid/drop ownership.
            prepareOffsets(be,Map.of()).commit("routing_upgrade_v"+a.routingVersion+"_to_v"+OrderedBeltPath.ROUTING_VERSION);
        }catch(IllegalArgumentException e) {
            Tracks.LOGGER.warn("[TS-ASSEMBLY] event=routing_upgrade_rejected id={} oldVersion={} reason={} installation={}",a.id,a.routingVersion,e.getMessage(),installationWheels(members(be)));
        }
    }
    public static Assembly create(List<SableTrackBlockEntity> wheels,Player player,List<Integer> sides) {
        var level=(ServerLevel)player.level();var origin=wheels.getFirst().getBlockPos();
        var a=new Assembly(UUID.randomUUID(),wheels.stream().map(w->w.getBlockPos().subtract(origin)).toList(),axis(wheels.getFirst()),sides,!player.hasInfiniteMaterials(),OrderedBeltPath.ROUTING_VERSION);
        TrackDriveNetwork.change(wheels,()->{
            data(level).assemblies.put(a.id,a);data(level).setDirty();
            // Linking preserves explicit per-wheel configuration.
            for(int i=0;i<wheels.size();i++)wheels.get(i).setBelt(a.id,i);
        },"belt_created");
        AssemblyPackets.broadcast(level,a.id,a);
        Tracks.LOGGER.info("[TS-ASSEMBLY] event=create id={} nodes={} axis={} origin={} sides={} installation={} pose={}",a.id,wheels.size(),a.axis,origin,a.sides,installationWheels(wheels),wheels(wheels,1));
        return a;
    }
    public static void remove(SableTrackBlockEntity be,Player recipient,String reason) {
        if(!(be.getLevel() instanceof ServerLevel level)||be.beltId()==null)return;
        var a=get(be);if(a==null){be.setBelt(null,0);return;}
        var loaded=members(be);
        // Reserve before detach: Create can synchronously break another member.
        if(!data(level).removing.add(a.id))return;
        try {
            TrackDriveNetwork.change(loaded,()->{
                data(level).assemblies.remove(a.id);
                data(level).attemptedUpgrades.remove(a.id);
                data(level).setDirty();for(var member:loaded)member.setBelt(null,0);
            },"belt_removed_"+reason);
        } finally {data(level).removing.remove(a.id);}
        var cache=MEMBERS.get(level);if(cache!=null)cache.remove(a.id);
        TrackDriveNetwork.forget(level,a.id);
        AssemblyGeometry.forget(level,a.id);
        AssemblyPackets.broadcast(level,a.id,null);
        boolean refund=recipient==null?a.refundable&&level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS):!recipient.hasInfiniteMaterials();
        if(refund) {
            ItemStack stack=AllItems.BELT_CONNECTOR.asStack();
            if(recipient==null) {
                Vec3 at=be.getBlockPos().getCenter();var body=dev.ryanhcode.sable.Sable.HELPER.getContaining(be);
                if(body!=null)at=body.logicalPose().transformPosition(at);
                var drop=new net.minecraft.world.entity.item.ItemEntity(level,at.x,at.y,at.z,stack);
                drop.setDefaultPickUpDelay();level.addFreshEntity(drop);
            }
            else if(!recipient.addItem(stack))recipient.drop(stack,false);
        }
        Tracks.LOGGER.info("[TS-ASSEMBLY] event=remove id={} reason={} refund={} createdPaid={} recipient={} loadedMembers={}",a.id,reason,refund,a.refundable,recipient==null?"world":recipient.getGameProfile().getName(),loaded.size());
    }
    /** Validate final installation geometry before mutating settings; publish one
     * new winding snapshot after the whole explicit edit. Physics never calls this. */
    public record GeometryEdit(ServerLevel level,Assembly before,Assembly after,List<OrderedBeltPath.Wheel> geometry) {
        public void commit(String reason) {
            if(after==null)return;
            var data=data(level);data.assemblies.put(after.id,after);data.attemptedUpgrades.remove(after.id);data.setDirty();
            AssemblyPackets.broadcast(level,after.id,after);
            Tracks.LOGGER.info("[TS-ASSEMBLY] event=replan id={} reason={} routingVersion={} oldSides={} sides={} installation={}",after.id,reason,after.routingVersion,before.sides,after.sides,geometry);
        }
    }
    public static GeometryEdit prepareOffsets(SableTrackBlockEntity target,Map<SableTrackBlockEntity,Vec3> deltas) {
        return prepareGeometry(target,deltas,Map.of(),false);
    }
    public static GeometryEdit prepareGeometry(SableTrackBlockEntity target,Map<SableTrackBlockEntity,Vec3> deltas,
            Map<SableTrackBlockEntity,Double> radii,boolean validatePose) {
        if(target.beltId()==null)return new GeometryEdit(null,null,null,List.of());
        var a=get(target);var members=members(target);
        if(a==null||members.size()!=a.offsets.size())throw new IllegalArgumentException("轮组尚未完整加载，未修改参数，请稍后重试");
        var installation=installationWheels(members);
        var changed=new ArrayList<OrderedBeltPath.Wheel>();
        for(int i=0;i<members.size();i++) {
            var w=installation.get(i);
            changed.add(new OrderedBeltPath.Wheel(w.center().add(point(deltas.getOrDefault(members.get(i),Vec3.ZERO),a.axis)),radii.getOrDefault(members.get(i),w.radius()),w.role()));
        }
        var plan=OrderedBeltPath.plan(changed);
        if(!plan.valid())throw new IllegalArgumentException("未修改参数：安装配置无法形成有效履带（"+plan.error()+"）");
        if(validatePose) {
            var pose=wheels(members,1);var changedPose=new ArrayList<OrderedBeltPath.Wheel>();
            for(int i=0;i<members.size();i++) {
                var w=pose.get(i);
                changedPose.add(new OrderedBeltPath.Wheel(w.center().add(point(deltas.getOrDefault(members.get(i),Vec3.ZERO),a.axis)),radii.getOrDefault(members.get(i),w.radius()),w.role()));
            }
            var resolved=OrderedBeltPath.resolve(changedPose,plan.sides());
            if(!resolved.valid())throw new IllegalArgumentException("未修改参数：当前悬挂姿态无法使用该尺寸/偏移（"+resolved.error()+"）；请调整间距或抬起车辆后重试");
        }
        return new GeometryEdit((ServerLevel)target.getLevel(),a,new Assembly(a.id,a.offsets,a.axis,plan.sides(),a.refundable,OrderedBeltPath.ROUTING_VERSION),List.copyOf(changed));
    }
    public static Direction.Axis axis(SableTrackBlockEntity be){return be.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING).getAxis();}
    public static OrderedBeltPath.P point(Vec3 p,Direction.Axis axis){return new OrderedBeltPath.P(axis==Direction.Axis.X?p.z:p.x,p.y);}
    public static Vec3 vector(OrderedBeltPath.P p,Direction.Axis axis){return axis==Direction.Axis.X?new Vec3(0,p.y(),p.x()):new Vec3(p.x(),p.y(),0);}
    public static List<OrderedBeltPath.Wheel> wheels(List<SableTrackBlockEntity> members,float partial) {
        var first=members.getFirst();Vec3 origin=first.getBlockPos().getCenter();var axis=axis(first);
        return members.stream().map(w->new OrderedBeltPath.Wheel(point(w.getWheelCenter(partial).subtract(origin),axis),w.wheelRadius(),routingRole(w))).toList();
    }
    public static List<OrderedBeltPath.Wheel> installationWheels(List<SableTrackBlockEntity> members) {
        var first=members.getFirst();var origin=first.getBlockPos().getCenter();var axis=axis(first);
        return members.stream().map(w->new OrderedBeltPath.Wheel(point(w.getWheelMountCenter(1).subtract(origin),axis),w.wheelRadius(),routingRole(w))).toList();
    }
    private static OrderedBeltPath.Role routingRole(SableTrackBlockEntity w) {
        return switch(w.effectiveRole()) {
            case SUSPENSION -> OrderedBeltPath.Role.ROAD;
            case GUIDE -> OrderedBeltPath.Role.GUIDE;
            case DRIVE -> OrderedBeltPath.Role.DRIVE;
            default -> throw new IllegalArgumentException("安装节点不是轮子");
        };
    }
    private static double ratio(SableTrackBlockEntity from,SableTrackBlockEntity to) {
        var a=get(to);
        int winding=a==null?1:a.sides.get(from.beltIndex())*a.sides.get(to.beltIndex());
        return winding*from.wheelRadius()/to.wheelRadius();
    }
    public static float rpm(SableTrackBlockEntity be) {
        for(var w:members(be))if(w.effectiveRole()==SableTrackRole.DRIVE&&Math.abs(w.getSpeed())>1e-4)
            return (axis(be)==Direction.Axis.X?-w.getSpeed():w.getSpeed())*(float)(w.speedMultiplier()*ratio(w,be));
        return 0;
    }
    public static float angle(SableTrackBlockEntity be,float partial) {
        var members=members(be);if(members.isEmpty())return be.getLerpedAngle(partial);
        var source=members.getFirst();
        var assembly=get(be);
        if(assembly!=null&&source.beltIndex()==0&&source.passiveMotion())
            return (float)Math.IEEEremainder(source.passiveDistance(partial)*assembly.sides.get(be.beltIndex())/be.wheelRadius(),2*Math.PI);
        for(var w:members)if(w.effectiveRole()==SableTrackRole.SUSPENSION){source=w;break;}
        for(var w:members)if(w.effectiveRole()==SableTrackRole.DRIVE&&Math.abs(w.getSpeed())>1e-4){source=w;break;}
        return source.getLerpedAngle(partial)*(float)ratio(source,be);
    }
}
