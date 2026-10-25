package dev.trackssimulate.assembly;

import dev.ryanhcode.sable.Sable;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.trackssimulate.trackpath.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** On-demand loaded-assembly queries; no world scan, chunk load or external collider registration.
 * Call on the owning level/physics thread. Snapshots themselves are detached and immutable. */
public final class AssemblyGeometry {
    public record Snapshot(UUID assembly,long revision,BlockPos origin,BeltSurface surface) {}
    public record WorldHit(Snapshot snapshot,BeltSurface.Query query,Vec3 position,Vec3 normal) {}
    private record Cached(long tick,TrackAssemblies.Assembly topology,List<OrderedBeltPath.Wheel> wheels,
                          List<Double> axial,List<Double> widths,Snapshot snapshot) {}
    private static final Map<Level,Map<UUID,Cached>> CACHE=new WeakHashMap<>();
    private static long revision;
    public static void clear(){CACHE.clear();}
    public static void forget(Level level,UUID id){var cache=CACHE.get(level);if(cache!=null)cache.remove(id);}
    public static Snapshot capture(SableTrackBlockEntity member) {
        if(member.getLevel()==null||member.getLevel().isClientSide)return null; // Server-authoritative query facade.
        var a=TrackAssemblies.get(member);if(a==null)return null;
        var members=TrackAssemblies.members(member);var level=member.getLevel();var body=Sable.HELPER.getContaining(member);
        if(members.size()!=a.offsets().size()||members.stream().anyMatch(w->w.isRemoved()||TrackAssemblies.axis(w)!=a.axis()||Sable.HELPER.getContaining(w)!=body))return null;
        var root=members.getFirst();var origin=root.getBlockPos();var center=origin.getCenter();
        var wheels=TrackAssemblies.wheels(members,1);
        var axial=members.stream().map(w->{var p=w.getWheelCenter(1).subtract(center);return a.axis()==net.minecraft.core.Direction.Axis.X?p.x:p.z;}).toList();
        var widths=members.stream().map(SableTrackBlockEntity::wheelWidth).toList();
        var cache=CACHE.computeIfAbsent(level,l->new LinkedHashMap<>(16,.75f,true));var old=cache.get(a.id());long tick=level.getGameTime();
        Snapshot snapshot;
        if(old!=null&&old.topology==a&&old.snapshot.origin.equals(origin)&&old.wheels.equals(wheels)&&old.axial.equals(axial)&&old.widths.equals(widths))snapshot=old.snapshot;
        else snapshot=new Snapshot(a.id(),++revision,origin,BeltSurface.build(OrderedBeltPath.resolve(wheels,a.sides()),axial,widths,a.axis()==net.minecraft.core.Direction.Axis.X));
        cache.put(a.id(),new Cached(tick,a,wheels,axial,widths,snapshot));
        if(cache.size()>512)cache.remove(cache.keySet().iterator().next());
        return snapshot;
    }
    /** Query a single known assembly using WORLD endpoints. Missing members return null.
     * A non-null result must still check query.complete() before trusting its closest hit. */
    public static WorldHit intersectWorld(SableTrackBlockEntity member,Vec3 from,Vec3 to,int spanBudget) {
        var snapshot=capture(member);if(snapshot==null)return null;
        var body=Sable.HELPER.getContaining(member);var center=snapshot.origin.getCenter();
        var a=body==null?from:body.logicalPose().transformPositionInverse(from);
        var b=body==null?to:body.logicalPose().transformPositionInverse(to);
        var query=snapshot.surface.intersect(vector(a.subtract(center)),vector(b.subtract(center)),spanBudget);
        if(query.hit()==null)return new WorldHit(snapshot,query,null,null);
        var p=vector(query.hit().position()).add(center);var n=vector(query.hit().normal());
        if(body!=null) {
            p=body.logicalPose().transformPosition(p);
            var j=new org.joml.Vector3d(n.x,n.y,n.z);body.logicalPose().transformNormal(j);n=new Vec3(j.x,j.y,j.z);
        }
        return new WorldHit(snapshot,query,p,n);
    }
    public static BeltSurface.V vector(Vec3 v){return new BeltSurface.V(v.x,v.y,v.z);}
    public static Vec3 vector(BeltSurface.V v){return new Vec3(v.x(),v.y(),v.z());}
    private AssemblyGeometry() {}
}
