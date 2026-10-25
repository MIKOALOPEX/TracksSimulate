package dev.trackssimulate.assembly;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.*;

/** Adds bounded, reciprocal 1:1 edges to Create's real rotation graph.
 * Create owns sources, network stress, overloads and speed conflicts. No copied
 * RPM/stress values and no second kinetic solver. Server main thread only. */
public final class TrackDriveNetwork {
    private TrackDriveNetwork() {}
    private static final Map<Level,Map<UUID,Snapshot>> SNAPSHOTS=new WeakHashMap<>();
    private record Snapshot(long checked, Object body, List<SableTrackBlockEntity> drives) {}
    public static void clear(){SNAPSHOTS.clear();}
    public static void forget(Level level,UUID belt) {
        var snapshots=SNAPSHOTS.get(level);if(snapshots!=null)snapshots.remove(belt);
    }
    private static boolean drive(SableTrackBlockEntity w){return w.effectiveRole()==SableTrackRole.DRIVE;}
    private static boolean live(SableTrackBlockEntity w) {
        var level=w.getLevel();return level!=null&&!w.isRemoved()&&level.hasChunkAt(w.getBlockPos())
            &&level.getBlockEntity(w.getBlockPos())==w;
    }
    public static boolean sameBelt(SableTrackBlockEntity a,SableTrackBlockEntity b) {
        if(a==b||!drive(a)||!drive(b)||!live(a)||!live(b)||a.getLevel()!=b.getLevel()
            ||a.beltId()==null||!a.beltId().equals(b.beltId()))return false;
        var belt=TrackAssemblies.get(a);
        if(belt==null||TrackAssemblies.get(b)!=belt||!belt.origin(a).equals(belt.origin(b)))return false;
        return TrackAssemblies.axis(a)==TrackAssemblies.axis(b)&&Sable.HELPER.getContaining(a)==Sable.HELPER.getContaining(b);
    }
    public static boolean connected(SableTrackBlockEntity from,KineticBlockEntity to) {
        return to instanceof SableTrackBlockEntity other&&!from.beltKineticsMuted&&!other.beltKineticsMuted&&sameBelt(from,other);
    }
    public static List<BlockPos> locations(SableTrackBlockEntity from,List<BlockPos> neighbours) {
        if(!drive(from))return neighbours;
        // During detach Create still needs the OLD possible neighbour positions
        // to clear source trees, while propagation over their old edges is muted.
        var peers=from.beltKineticsMuted?from.detachingBeltPeers:peers(from);
        for(var p:peers)if(from.getLevel().hasChunkAt(p)&&!neighbours.contains(p))neighbours.add(p);
        return neighbours;
    }
    private static List<BlockPos> peers(SableTrackBlockEntity from) {
        if(from.beltId()==null)return List.of();
        return TrackAssemblies.members(from).stream().filter(w->sameBelt(from,w)).map(SableTrackBlockEntity::getBlockPos).toList();
    }
    /** Detach old graph first, commit every edit, then let Create reattach next
     * tick. Never let propagation observe a half-applied group edit. */
    public static void change(Collection<SableTrackBlockEntity> wheels,Runnable mutation,String reason) {
        var affected=wheels.stream().filter(w->live(w)&&drive(w)&&!w.getLevel().isClientSide&&!w.beltKineticsMuted).distinct().toList();
        for(var w:affected)w.detachingBeltPeers=peers(w);
        for(var w:affected)w.beltKineticsMuted=true;
        try {
            for(var w:affected)if(live(w)){w.detachKinetics();w.removeSource();w.sendData();}
            mutation.run();
        } finally {
            for(var w:affected) {
                w.beltKineticsMuted=false;w.detachingBeltPeers=List.of();
                if(live(w))w.updateSpeed=true;
            }
        }
        if(!affected.isEmpty())Tracks.LOGGER.info("[TS-KINETIC] event=rebuild reason={} drives={} positions={}",reason,affected.size(),affected.stream().map(SableTrackBlockEntity::getBlockPos).toList());
    }
    /** Bounded membership checks, once per belt per second. Restores links after
     * chunk load/ship assembly; never searches worlds or other substructures. */
    public static void maintain(SableTrackBlockEntity wheel) {
        var level=wheel.getLevel();
        if(level==null||level.isClientSide||!drive(wheel)||wheel.beltKineticsMuted)return;
        // A loaded source from an obsolete saved belt must not keep powering us.
        if(wheel.source!=null&&level.hasChunkAt(wheel.source)
            &&level.getBlockEntity(wheel.source) instanceof SableTrackBlockEntity source
            &&!com.simibubi.create.content.kinetics.RotationPropagator.isConnected(wheel,source))
            change(List.of(wheel),()->{},"obsolete_saved_source");
        var belt=TrackAssemblies.get(wheel);if(belt==null)return;
        long tick=level.getGameTime();
        var cache=SNAPSHOTS.computeIfAbsent(level,l->new HashMap<>());
        var previous=cache.get(belt.id());
        if(previous!=null&&tick-previous.checked<20&&tick>=previous.checked)return;
        var body=Sable.HELPER.getContaining(wheel);
        var drives=TrackAssemblies.members(wheel).stream().filter(w->live(w)&&drive(w)
            &&TrackAssemblies.axis(w)==belt.axis()&&Sable.HELPER.getContaining(w)==body).toList();
        if(previous==null)for(var w:drives)w.updateSpeed=true;
        else if(previous.body!=body||!previous.drives.equals(drives)) {
            var all=new ArrayList<>(previous.drives);all.addAll(drives);
            change(all,()->{},"loaded_members_changed");
        }
        cache.put(belt.id(),new Snapshot(tick,body,drives));
        if(cache.size()>512)cache.entrySet().removeIf(e->tick-e.getValue().checked>200);
        if(previous==null||tick/100!=previous.checked/100) {
            Tracks.LOGGER.info("[TS-KINETIC] event=snapshot belt={} loadedDrives={} nodes={}",belt.id(),drives.size(),
                drives.stream().map(w->"{pos="+w.getBlockPos()+",axle="+SableTrackBlock.axleMode(w.getBlockState())
                    +",rpm="+w.getTheoreticalSpeed()+",effective="+w.getSpeed()+",network="+w.network
                    +",source="+w.source+",stress="+w.kineticStress()+",capacity="+w.kineticCapacity()
                    +",overloaded="+w.isOverStressed()+"}").toList());
        }
    }
}
