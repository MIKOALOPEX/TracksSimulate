package dev.trackssimulate.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.*;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.trackssimulate.assembly.*;
import dev.trackssimulate.config.TracksServerConfig;
import dev.trackssimulate.content.blocks.sable_track.*;
import dev.trackssimulate.trackpath.BeltSurface;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;
import java.util.*;

/** Bounded wheel/full-loop terrain contact collection. No native proxy constraints.
 * Terrain is queried once per game tick; finite cached faces follow both bodies each substep.
 * Leaving a cached face triggers one bounded local recast. */
public final class SurfaceContacts {
    public record Candidate(SableTrackBlockEntity source,SableTrackBlockEntity root,Vec3 point,Vec3 normal,
            Vec3 tangent,SubLevel other,Vec3 otherPoint,double penetration,double friction,boolean wheel) {}
    public record Support(SableTrackBlockEntity wheel,double rawExtension,Vec3 normal,SubLevel other,BlockPos block,Vec3 point) {}
    public record Batch(List<Candidate> contacts,List<Support> supports,int routed,int rays,int requested,int rejected,int merged,int inside,int expired,long nanos) {}
    private record Hit(SurfaceProbes.Probe probe,Vec3 point,Vec3 normal,SubLevel other,double friction,ContactValidity.Box face,BlockPos block) {}
    private record Patch(SubLevel other,long x,long y,long z,long nx,long ny,long nz) {}
    private record Cache(long tick,Object topology,BlockPos root,List<SableTrackBlockEntity> members,List<Vec3> centers,List<Hit> hits,int rays,int requested) {}
    private static final Map<ServerSubLevel,Map<Object,Cache>> CACHE=new WeakHashMap<>();
    private static final Map<ServerLevel,Long> PRUNED=new WeakHashMap<>();
    public static void clear(){CACHE.clear();PRUNED.clear();}
    /** Also called when no wheel actors remain, so sleeping/empty bodies cannot retain old members forever. */
    public static void prune(ServerLevel level) {
        long tick=level.getGameTime();var previous=PRUNED.get(level);
        if(previous!=null&&tick>=previous&&tick-previous<20)return;
        PRUNED.put(level,tick);
        var it=CACHE.entrySet().iterator();
        while(it.hasNext()) {
            var e=it.next();if(e.getKey().isRemoved()){it.remove();continue;}
            if(e.getKey().getLevel()!=level)continue;
            e.getValue().entrySet().removeIf(c->tick-c.getValue().tick>40||c.getValue().tick>tick);
            if(e.getValue().isEmpty())it.remove();
        }
    }
    private static Vec3 v(BeltSurface.V p){return AssemblyGeometry.vector(p);}
    private static BeltSurface.V v(Vec3 p){return AssemblyGeometry.vector(p);}
    public static Batch collect(ServerSubLevel host,List<SableTrackBlockEntity> actors,double dt) {
        long started=System.nanoTime();ServerLevel level=host.getLevel();long tick=level.getGameTime();
        var out=new ArrayList<Candidate>();var seen=new HashSet<Object>();int requested=0,rejected=0,expired=0,routed=0;
        var supports=new LinkedHashMap<SableTrackBlockEntity,Support>();
        var stats=new int[3]; // casts, inside-origin rejects, invalid faces
        var excludeCluster=ConnectedBodies.ignoring(host,false);var excludeOwner=ConnectedBodies.ignoring(host,true);
        var cache=CACHE.computeIfAbsent(host,h->new HashMap<>());
        for(var actor:actors) {
            var topology=TrackAssemblies.get(actor);var members=topology==null?List.of(actor):TrackAssemblies.members(actor);
            if(actor.beltId()!=null&&(topology==null||members.size()!=topology.offsets().size())
                ||members.stream().anyMatch(w->w.isRemoved()||Sable.HELPER.getContaining(w)!=host)) {
                // Unavailable belt geometry must not remove an already loaded wheel's support.
                topology=null;members=List.of(actor);rejected++;
            }
            Object key=topology==null?actor.getBlockPos():actor.beltId();if(!seen.add(key))continue;
            var root=members.getFirst();var origin=root.getBlockPos().getCenter();var axis=TrackAssemblies.axis(root);
            var centers=members.stream().map(w->w.getWheelCenter(1)).toList();var old=cache.get(key);
            if(old==null||old.tick!=tick||old.topology!=topology||!old.root.equals(root.getBlockPos())||!old.members.equals(members)) {
                var geometry=topology==null?null:AssemblyGeometry.capture(root);
                if(topology!=null&&(geometry==null||!geometry.surface().valid())){rejected++;geometry=null;}
                var wheels=new ArrayList<SurfaceProbes.Wheel>();
                for(int i=0;i<members.size();i++) {
                    var w=members.get(i);var saved=TrackAssemblies.get(w);
                    wheels.add(new SurfaceProbes.Wheel(v(centers.get(i).subtract(origin)),w.wheelRadius(),w.wheelWidth(),saved==null?1:saved.sides().get(w.beltIndex())));
                }
                var plan=SurfaceProbes.plan(wheels,geometry==null?null:geometry.surface(),axis==Direction.Axis.X,TracksServerConfig.SURFACE_PROBES.get());
                var hits=new ArrayList<Hit>();
                for(var probe:plan.probes()) {
                    var source=members.get(probe.blend()<.5?probe.from():probe.to());
                    var hit=cast(host,probe,v(probe.point()).add(origin),source.connectedContacts()?excludeOwner:excludeCluster,stats);
                    if(hit!=null)hits.add(hit);
                }
                old=new Cache(tick,topology,root.getBlockPos(),members,centers,List.copyOf(hits),0,plan.requested());cache.put(key,old);
                requested+=old.requested;
            }
            for(var hit:old.hits) {
                if(hit.other!=null&&hit.other.isRemoved())continue;
                var p=hit.probe;
                Vec3 shift=centers.get(p.from()).subtract(old.centers.get(p.from())).scale(1-p.blend())
                    .add(centers.get(p.to()).subtract(old.centers.get(p.to())).scale(p.blend()));
                Vec3 point=v(p.point()).add(origin).add(shift);
                Vec3 pointWorld=host.logicalPose().transformPosition(point);
                Vec3 onOther=hit.other==null?pointWorld:hit.other.logicalPose().transformPositionInverse(pointWorld);
                if(!ContactValidity.covers(hit.face,v(onOther),v(hit.point),v(hit.normal))) {
                    expired++;
                    // Refresh only the expired finite face in this substep, within the original probe count.
                    var source=members.get(p.blend()<.5?p.from():p.to());
                    hit=cast(host,p,point,source.connectedContacts()?excludeOwner:excludeCluster,stats);
                    if(hit==null)continue;
                }
                if(hit.other!=null&&(members.get(p.blend()<.5?p.from():p.to()).connectedContacts()?excludeOwner:excludeCluster).test(hit.other))continue;
                Vec3 world=hit.other==null?hit.point:hit.other.logicalPose().transformPosition(hit.point);
                Vec3 local=host.logicalPose().transformPositionInverse(world);
                var j=new Vector3d(hit.normal.x,hit.normal.y,hit.normal.z);
                if(hit.other!=null)hit.other.logicalPose().transformNormal(j);host.logicalPose().transformNormalInverse(j);
                Vec3 normal=new Vec3(j.x,j.y,j.z).normalize();double penetration=local.subtract(point).dot(normal);
                if(penetration<-.75||penetration>2){rejected++;continue;}
                var source=members.get(p.blend()<.5?p.from():p.to());
                var first=members.get(p.from());var second=members.get(p.to());
                double mobility=SuspensionContactRouting.mobility(first.effectiveRole()==SableTrackRole.SUSPENSION,
                    second.effectiveRole()==SableTrackRole.SUSPENSION,p.blend(),normal.y);
                if(mobility>1e-6) {
                    routed++;
                    if(first==second||1-p.blend()>1e-6)offerSupport(supports,first,hit,point,normal,penetration,mobility);
                    if(second!=first&&p.blend()>1e-6)offerSupport(supports,second,hit,point,normal,penetration,mobility);
                    // This contact can only support through spring travel and its compression stop.
                    // Even at full droop with no narrow-ray hit, it must never create a rigid bypass.
                    continue;
                }
                out.add(new Candidate(source,root,point,normal,v(p.tangent()),hit.other,hit.point,penetration,hit.friction,p.wheel()));
            }
        }
        cache.entrySet().removeIf(e->tick-e.getValue().tick>40);
        while(cache.size()>512)cache.remove(cache.keySet().iterator().next());
        // Same body/normal/spatial patch -> one contact, regardless of wheel/belt overlap.
        var merged=new LinkedHashMap<Patch,Candidate>();
        for(var c:out) {
            var key=new Patch(c.other,(long)Math.floor(c.point.x*2),(long)Math.floor(c.point.y*2),(long)Math.floor(c.point.z*2),
                Math.round(c.normal.x*4),Math.round(c.normal.y*4),Math.round(c.normal.z*4));
            var prev=merged.get(key);if(prev==null||c.penetration>prev.penetration)merged.put(key,c);
        }
        var candidates=new ArrayList<>(merged.values());
        int max=TracksServerConfig.SURFACE_CONTACTS.get();
        if(candidates.size()>max) {
            rejected+=candidates.size()-max;
            var deepest=Collections.max(candidates,Comparator.comparingDouble(Candidate::penetration));
            candidates.remove(deepest);
            // Keep the deepest patch, then spatially distribute the rest; never truncate one end of a long belt.
            candidates.sort(Comparator.comparingLong((Candidate c)->c.root.getBlockPos().asLong())
                .thenComparingDouble(c->c.point.x).thenComparingDouble(c->c.point.z).thenComparingDouble(c->c.point.y));
            var distributed=new ArrayList<Candidate>();distributed.add(deepest);
            for(int i=0;i<max-1;i++)distributed.add(candidates.get((int)((long)i*candidates.size()/(max-1))));
            candidates=distributed;
        }
        return new Batch(List.copyOf(candidates),List.copyOf(supports.values()),routed,stats[0],requested,rejected+stats[2],out.size()-merged.size(),stats[1],expired,System.nanoTime()-started);
    }
    private static void offerSupport(Map<SableTrackBlockEntity,Support> supports,SableTrackBlockEntity wheel,Hit hit,
            Vec3 point,Vec3 normal,double penetration,double mobility) {
        if(wheel.effectiveRole()!=SableTrackRole.SUSPENSION)return;
        double raw=SuspensionContactRouting.rawExtension(wheel.getLerpedExtension(1),penetration,normal.y,mobility);
        if(raw>wheel.maximumExtension())return; // separation permits approach; never attracts a hanging wheel
        var support=new Support(wheel,raw,normal,hit.other,hit.block,point);
        var old=supports.get(wheel);
        if(old==null||raw<old.rawExtension)supports.put(wheel,support);
    }
    private static Hit cast(ServerSubLevel host,SurfaceProbes.Probe probe,Vec3 point,java.util.function.Predicate<SubLevel> ignored,int[] stats) {
        var level=host.getLevel();var outward=v(probe.outward());
        var velocity=Sable.HELPER.getVelocity(level,new Vector3d(point.x,point.y,point.z));host.logicalPose().transformNormalInverse(velocity);
        double margin=Math.min(.75,.04+Math.max(0,velocity.dot(new Vector3d(outward.x,outward.y,outward.z)))*.05);
        var from=point.subtract(outward.scale(probe.backtrack()));var to=point.add(outward.scale(margin));
        if(!loaded(level,host.logicalPose().transformPosition(from),host.logicalPose().transformPosition(to))){stats[2]++;return null;}
        var clip=new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,CollisionContext.empty());
        ((ClipContextExtension)clip).sable$setIgnoredSubLevel(host);
        ((ClipContextExtension)clip).sable$setSubLevelIgnoring(ignored);
        stats[0]++;
        var result=level.clip(clip);if(result.getType()==HitResult.Type.MISS)return null;
        if(result.isInside()){stats[1]++;return null;}
        var other=Sable.HELPER.getContaining(level,result.getLocation());if(other!=null&&ignored.test(other))return null;
        var normal=Vec3.atLowerCornerOf(result.getDirection().getNormal());
        var j=new Vector3d(normal.x,normal.y,normal.z);if(other!=null)other.logicalPose().transformNormal(j);host.logicalPose().transformNormalInverse(j);
        if(!ContactValidity.entry(false,v(outward),new BeltSurface.V(j.x,j.y,j.z)))return null;
        ContactValidity.Box face=null;
        for(var box:level.getBlockState(result.getBlockPos()).getCollisionShape(level,result.getBlockPos()).toAabbs()) {
            var moved=box.move(result.getBlockPos());
            var bounds=new ContactValidity.Box(new BeltSurface.V(moved.minX,moved.minY,moved.minZ),new BeltSurface.V(moved.maxX,moved.maxY,moved.maxZ));
            if(ContactValidity.face(bounds,v(result.getLocation()),v(normal))){face=bounds;break;}
        }
        if(face==null){stats[2]++;return null;}
        double friction=SableTrackBlockEntity.fudgeFriction(dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper.getFriction(level.getBlockState(result.getBlockPos())));
        return new Hit(probe,result.getLocation(),normal,other,friction,face,result.getBlockPos().immutable());
    }
    private static boolean loaded(ServerLevel level,Vec3 from,Vec3 to) {
        int x0=(int)Math.floor(Math.min(from.x,to.x))>>4,x1=(int)Math.floor(Math.max(from.x,to.x))>>4;
        int z0=(int)Math.floor(Math.min(from.z,to.z))>>4,z1=(int)Math.floor(Math.max(from.z,to.z))>>4;
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)if(!level.hasChunk(x,z))return false;
        return true;
    }
    private SurfaceContacts() {}
}
