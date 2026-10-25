package dev.trackssimulate.client;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.assembly.*;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.trackssimulate.trackpath.OrderedBeltPath;
import dev.trackssimulate.trackpath.BeltSurface;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** One loop mesh per assembly, evaluated by its first node only. */
public final class AssemblyBeltCache {
    private Level level;
    private final Map<UUID,Group> groups=new HashMap<>();
    private final Map<UUID,Long> requests=new HashMap<>();
    private final Map<UUID,Long> reports=new HashMap<>();
    public Group get(SableTrackBlockEntity be,float partial) {
        if(level!=be.getLevel()){level=be.getLevel();groups.clear();requests.clear();reports.clear();}
        if(be.beltId()==null)return null;
        long tick=level.getGameTime();
        if(requests.size()>512)requests.entrySet().removeIf(e->tick-e.getValue()>200);
        if(reports.size()>512)reports.entrySet().removeIf(e->tick-e.getValue()>200);
        var assembly=TrackAssemblies.get(be);
        if(assembly==null) {
            if(tick-requests.getOrDefault(be.beltId(),tick-100)>=100){requests.put(be.beltId(),tick);PacketDistributor.sendToServer(new AssemblyPackets.Request(be.getBlockPos(),be.beltId()));}
            reportMissing(be,tick,"waiting_topology",0,0);
            return null;
        }
        var members=TrackAssemblies.members(be);
        if(members.size()!=assembly.offsets().size()){reportMissing(be,tick,"waiting_members",members.size(),assembly.offsets().size());return null;}
        if(be.beltIndex()!=0)return null;
        groups.entrySet().removeIf(e->tick-e.getValue().used>200);
        var group=groups.computeIfAbsent(be.beltId(),id->new Group());
        group.update(be,assembly,members,partial,tick);return group;
    }
    private void reportMissing(SableTrackBlockEntity be,long tick,String reason,int available,int expected) {
        if(tick-reports.getOrDefault(be.beltId(),tick-100)<100)return;
        reports.put(be.beltId(),tick);
        Tracks.LOGGER.info("[TS-PATH] id={} status={} observedAt={} members={}/{}",be.beltId(),reason,be.getBlockPos(),available,expected);
    }
    public static final class Group {
        public List<BeltSurface.Span> spans=List.of();
        public List<float[]> mesh=List.of();
        public Vec3 axle;
        public float phase;
        public int color;
        private List<OrderedBeltPath.Wheel> previous=List.of();
        private List<Double> previousAxial=List.of();
        private List<Double> previousWidths=List.of();
        private List<Integer> sides=List.of();
        private long used,report,solves,nanos;
        private OrderedBeltPath.Result result;
        private void update(SableTrackBlockEntity root,TrackAssemblies.Assembly a,List<SableTrackBlockEntity> members,float partial,long tick) {
            used=tick;
            Vec3 unitAxle=a.axis()==net.minecraft.core.Direction.Axis.X?new Vec3(1,0,0):new Vec3(0,0,1);
            axle=unitAxle;
            phase=(float)(root.getSharedTrackAngle(root.getBlockState().getValue(dev.trackssimulate.content.blocks.sable_track.SableTrackBlock.HORIZONTAL_FACING),partial)*root.wheelRadius()*a.sides().getFirst());
            if(root.passiveMotion()) {
                double travel=root.passiveDistance(partial);
                phase=(float)(travel-Math.floor(travel)); // Texture repeat is independent of every wheel's circumference.
            }
            color=root.getBeltColor()==null?0xffffff:root.getBeltColor().getTextureDiffuseColor();
            var wheels=TrackAssemblies.wheels(members,partial);
            var widths=members.stream().map(SableTrackBlockEntity::wheelWidth).toList();
            var axial=members.stream().map(w->{var c=w.getWheelCenter(partial).subtract(root.getBlockPos().getCenter());return a.axis()==net.minecraft.core.Direction.Axis.X?c.x:c.z;}).toList();
            boolean axialChanged=axial.size()!=previousAxial.size();
            if(!axialChanged)for(int i=0;i<axial.size();i++)if(Math.abs(axial.get(i)-previousAxial.get(i))>1e-4){axialChanged=true;break;}
            if(!samePose(wheels,previous)||axialChanged||!a.sides().equals(sides)||!widths.equals(previousWidths)) {
                long start=System.nanoTime();previous=wheels;previousAxial=axial;previousWidths=widths;sides=a.sides();
                result=OrderedBeltPath.resolve(wheels,sides);
                var surface=BeltSurface.build(result,axial,widths,a.axis()==net.minecraft.core.Direction.Axis.X);
                spans=surface.spans();mesh=BeltRenderMesh.bake(surface);nanos+=System.nanoTime()-start;solves++;
            }
            if(tick-report>=100&&result!=null) {
                double maxArc=result.segments().stream().filter(OrderedBeltPath.Segment::arc).mapToDouble(s->Math.toDegrees(Math.abs(s.sweep()))).max().orElse(0);
                var wrapDegrees=new double[members.size()];
                var contactErrors=new double[members.size()];Arrays.fill(contactErrors,-1);
                for(var segment:result.segments())if(!segment.arc()) {
                    int from=segment.from(),to=segment.to();
                    contactErrors[from]=Math.max(contactErrors[from],Math.abs(segment.a().sub(wheels.get(from).center()).length()-wheels.get(from).radius()));
                    contactErrors[to]=Math.max(contactErrors[to],Math.abs(segment.b().sub(wheels.get(to).center()).length()-wheels.get(to).radius()));
                }
                for(var segment:result.segments())if(segment.arc())wrapDegrees[segment.from()]+=Math.toDegrees(segment.sweep());
                Tracks.LOGGER.info("[TS-PATH] id={} policy=ordered_committed_sides routingVersion={} nodes={} width={} radii={} roles={} valid={} reason={} sides={} length={} maxArcDegrees={} nodeWrapDegrees={} nodeContactError={} tangentError={} meshSpans={} solves={} solveMs={}",a.id(),a.routingVersion(),members.size(),widths,members.stream().map(SableTrackBlockEntity::wheelRadius).toList(),members.stream().map(SableTrackBlockEntity::effectiveRole).toList(),result.valid(),result.error(),sides,result.length(),maxArc,Arrays.toString(wrapDegrees),Arrays.toString(contactErrors),result.tangentError(),spans.size(),solves,nanos/1e6);
                report=tick;solves=nanos=0;
            }
        }
        private static double lerp(double a,double b,double t){return a+(b-a)*t;}
        private static boolean samePose(List<OrderedBeltPath.Wheel> a,List<OrderedBeltPath.Wheel> b) {
            if(a.size()!=b.size())return false;
            for(int i=0;i<a.size();i++)if(a.get(i).role()!=b.get(i).role()||Math.abs(a.get(i).radius()-b.get(i).radius())>1e-8||a.get(i).center().sub(b.get(i).center()).length()>1e-4)return false;
            return true;
        }
    }
}
