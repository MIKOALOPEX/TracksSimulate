package dev.trackssimulate.client;


import dev.trackssimulate.assembly.*;
import dev.trackssimulate.trackpath.OrderedBeltPath;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

@EventBusSubscriber(modid="trackssimulate",value=Dist.CLIENT)
public final class TrackSelectionPreview {
    // Create 6.0.10 EjectorTargetHandler: selected target, valid trajectory, invalid trajectory.
    private static final int YELLOW=0xFFCB74,GREEN=0x9EDF73,RED=0xFF7171;
    private static final float LINE_WIDTH=.0625f;
    private static final Set<String> visible=new HashSet<>();
    private static List<BlockPos> lastNodes=List.of();
    private static OrderedBeltPath.Result cached;
    private static OrderedBeltPath.Result planned;
    private static List<OrderedBeltPath.Wheel> lastWheels=List.of();
    private static List<OrderedBeltPath.Wheel> lastInstallation=List.of();
    private static Object lastLevel,lastView;
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null) { forget();return; }
        var view=TrackSelection.clientView;
        if(mc.screen!=null||!mc.player.isAlive()) { cancel();return; }
        if(view!=null&&(view.slot()!=mc.player.getInventory().selected
            ||!isBelt(mc.player.getItemInHand(view.hand())))) { cancel();return; }
        Set<String> shown=new HashSet<>();
        if(!isBelt(mc.player.getMainHandItem())&&!isBelt(mc.player.getOffhandItem())) { clean(shown);return; }
        List<BlockPos> nodes=view==null?new ArrayList<>():new ArrayList<>(view.nodes());
        SableTrackBlockEntity first=null;
        if(!nodes.isEmpty()&&mc.level.getBlockEntity(nodes.getFirst()) instanceof SableTrackBlockEntity w) first=w;
        for(var node:nodes) if(mc.level.getBlockEntity(node) instanceof SableTrackBlockEntity wheel) {
            box(shown,"selected/"+node.asLong(),wheel);
        }
        if(mc.hitResult instanceof BlockHitResult hit&&mc.level.getBlockEntity(hit.getBlockPos()) instanceof SableTrackBlockEntity hovered
            &&hovered.beltId()==null) {
            boolean compatible=first==null||(TrackAssemblies.axis(first)==TrackAssemblies.axis(hovered)&&first.getBlockPos().get(TrackAssemblies.axis(first))==hovered.getBlockPos().get(TrackAssemblies.axis(first))
                &&Sable.HELPER.getContaining(first)==Sable.HELPER.getContaining(hovered));
            if(compatible) {
                var node=hovered.getBlockPos();
                if(nodes.stream().noneMatch(n->n.equals(node))) {
                    box(shown,"hover",hovered);
                    if(nodes.size()<OrderedBeltPath.MAX_NODES) nodes.add(node);
                }
                if(first==null) first=hovered;
            }
        }
        if(first!=null&&nodes.size()>=2) {
            var members=new ArrayList<SableTrackBlockEntity>();
            for(var node:nodes)if(mc.level.getBlockEntity(node) instanceof SableTrackBlockEntity w)members.add(w);
            var wheels=TrackAssemblies.wheels(members,1);
            var installation=TrackAssemblies.installationWheels(members);
            boolean topologyChanged=!nodes.equals(lastNodes)||lastLevel!=mc.level||!installation.equals(lastInstallation);
            if(topologyChanged) {
                long begin=System.nanoTime();planned=OrderedBeltPath.plan(installation);
                dev.trackssimulate.Tracks.LOGGER.info("[TS-PREVIEW] nodes={} installation={} valid={} reason={} sides={} planMs={}",
                    nodes,installation,planned.valid(),planned.error(),planned.sides(),(System.nanoTime()-begin)/1e6);
            }
            if(topologyChanged||!wheels.equals(lastWheels)) {
                lastNodes=List.copyOf(nodes);lastLevel=mc.level;lastWheels=wheels;lastInstallation=installation;
                cached=wheels.size()!=nodes.size()?null:planned.valid()?OrderedBeltPath.resolve(wheels,planned.sides()):planned;
            }
            int index=0;
            if(cached!=null&&!cached.segments().isEmpty())for(var segment:cached.segments()) {
                int count=segment.arc()?Math.max(1,(int)Math.ceil(Math.abs(segment.sweep())/.15)):1;
                for(int i=0;i<count;i++)line(shown,"route/"+(index++),point(segment,(double)i/count,first,members),point(segment,(double)(i+1)/count,first,members),cached.valid()?GREEN:RED);
            }
            else for(int i=0;i<nodes.size();i++)line(shown,"invalid/"+i,Vec3.atCenterOf(nodes.get(i)),Vec3.atCenterOf(nodes.get((i+1)%nodes.size())),RED);
        }
        clean(shown);
    }
    private static boolean isBelt(net.minecraft.world.item.ItemStack item) { return BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals("create:belt_connector"); }
    private static Vec3 point(OrderedBeltPath.Segment s,double t,SableTrackBlockEntity first,List<SableTrackBlockEntity> members) {
        var axis=TrackAssemblies.axis(first);var origin=first.getBlockPos().getCenter();
        var ca=members.get(s.from()).getWheelCenter(1).subtract(origin);var cb=members.get(s.to()).getWheelCenter(1).subtract(origin);
        double a=axis==Direction.Axis.X?ca.x:ca.z,b=axis==Direction.Axis.X?cb.x:cb.z,z=a+(b-a)*t;
        return origin.add(TrackAssemblies.vector(s.at(t),axis)).add(axis==Direction.Axis.X?new Vec3(z,0,0):new Vec3(0,0,z));
    }
    private static String key(Set<String> set,String suffix) { String key="trackssimulate/selection/"+suffix;set.add(key);return key; }
    private static void box(Set<String> shown,String key,SableTrackBlockEntity w) {
        double r=w.wheelRadius()+.04,half=w.wheelWidth()/2+.04;
        double x=TrackAssemblies.axis(w)==Direction.Axis.X?half:r,y=TrackAssemblies.axis(w)==Direction.Axis.Y?half:r,z=TrackAssemblies.axis(w)==Direction.Axis.Z?half:r;
        Vec3 c=w.getWheelCenter(1);
        // Sable's Catnip outline mixins transform plot coordinates at render time.
        Outliner.getInstance().showAABB(key(shown,key),new AABB(c.x-x,c.y-y,c.z-z,c.x+x,c.y+y,c.z+z)).colored(YELLOW).lineWidth(LINE_WIDTH).disableLineNormals();
    }
    private static void line(Set<String> shown,String key,Vec3 a,Vec3 b,int color) {
        if(a.distanceToSqr(b)>1e-10) Outliner.getInstance().showLine(key(shown,key),a,b).colored(color).lineWidth(LINE_WIDTH).disableLineNormals();
    }
    private static void clean(Set<String> keep) { for(String key:visible) if(!keep.contains(key)) Outliner.getInstance().remove(key);visible.clear();visible.addAll(keep); }
    private static void forget() { TrackSelection.clientView=null;TrackSelection.clientPending=false;lastNodes=List.of();cached=null;lastLevel=null;lastView=null;clean(Set.of()); }
    private static void cancel() {
        var mc=Minecraft.getInstance();
        if(mc.getConnection()!=null&&(TrackSelection.clientView!=null||TrackSelection.clientPending))
            PacketDistributor.sendToServer(new TrackSelection.Cancel(TrackSelection.clientView==null?TrackSelection.NONE:TrackSelection.clientView.id()));
        forget();
    }
    @SubscribeEvent public static void screen(ScreenEvent.Opening e) { cancel(); }
    @SubscribeEvent public static void scroll(InputEvent.MouseScrollingEvent e) { cancel(); }
    @SubscribeEvent public static void key(InputEvent.Key e) {
        if(e.getAction()==0) return;
        var o=Minecraft.getInstance().options;
        boolean cancel=o.keySwapOffhand.matches(e.getKey(),e.getScanCode())||o.keyDrop.matches(e.getKey(),e.getScanCode())||o.keyInventory.matches(e.getKey(),e.getScanCode());
        for(var slot:o.keyHotbarSlots) cancel|=slot.matches(e.getKey(),e.getScanCode());
        if(cancel) cancel();
    }
    @SubscribeEvent public static void air(PlayerInteractEvent.RightClickEmpty e) { cancel(); }
    @SubscribeEvent public static void disconnect(ClientPlayerNetworkEvent.LoggingOut e) { forget();TrackAssemblies.CLIENT.clear();TrackAssemblies.clearCaches(); }
}
