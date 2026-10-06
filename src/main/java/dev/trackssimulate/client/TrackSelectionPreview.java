package dev.trackssimulate.client;

import dev.trackssimulate.TracksSimulate;
import dev.trackssimulate.track.*;
import dev.trackssimulate.wheel.WheelBlockEntity;
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

@EventBusSubscriber(modid=TracksSimulate.MOD_ID,value=Dist.CLIENT)
public final class TrackSelectionPreview {
    // Create 6.0.10 EjectorTargetHandler: selected target, valid trajectory, invalid trajectory.
    private static final int YELLOW=0xFFCB74,GREEN=0x9EDF73,RED=0xFF7171;
    private static final float LINE_WIDTH=.0625f;
    private static final Set<String> visible=new HashSet<>();
    private static List<TrackSelections.Node> lastNodes=List.of();
    private static TrackPath.Path cached;
    private static List<TrackPath.Wheel> lastWheels=List.of();
    private static Object lastLevel,lastView;
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null) { forget();return; }
        var view=TrackSelections.clientView;
        if(mc.screen!=null||!mc.player.isAlive()) { cancel();return; }
        if(view!=null&&(view.slot()!=mc.player.getInventory().selected||!view.dimension().equals(mc.level.dimension().location())
            ||!isBelt(mc.player.getItemInHand(view.hand())))) { cancel();return; }
        Set<String> shown=new HashSet<>();
        if(!isBelt(mc.player.getMainHandItem())&&!isBelt(mc.player.getOffhandItem())) { clean(shown);return; }
        List<TrackSelections.Node> nodes=view==null?new ArrayList<>():new ArrayList<>(view.nodes());
        WheelBlockEntity first=null;
        if(!nodes.isEmpty()&&mc.level.getBlockEntity(nodes.getFirst().pos()) instanceof WheelBlockEntity w) first=w;
        for(var node:nodes) if(mc.level.getBlockEntity(node.pos()) instanceof WheelBlockEntity wheel) {
            box(shown,"selected/"+node.pos().asLong(),wheel);
        }
        if(mc.hitResult instanceof BlockHitResult hit&&mc.level.getBlockEntity(hit.getBlockPos()) instanceof WheelBlockEntity hovered
            &&hovered.loopId()==null) {
            boolean compatible=first==null||(first.samePlane(hovered)
                &&Sable.HELPER.getContaining(first)==Sable.HELPER.getContaining(hovered));
            if(compatible) {
                var node=new TrackSelections.Node(hovered.getBlockPos(),TrackInteractions.routeHint(hovered,hit.getLocation()));
                if(nodes.stream().noneMatch(n->n.pos().equals(node.pos()))) {
                    box(shown,"hover",hovered);
                    if(nodes.size()<TrackPath.MAX_WHEELS) nodes.add(node);
                }
                if(first==null) first=hovered;
            }
        }
        if(first!=null&&nodes.size()>=2) {
            List<TrackPath.Wheel> wheels=new ArrayList<>();
            for(var node:nodes) if(mc.level.getBlockEntity(node.pos()) instanceof WheelBlockEntity w)
                wheels.add(w.planningWheel(first.getBlockPos(),node.hint()));
            if(!nodes.equals(lastNodes)||lastLevel!=mc.level||lastView!=view||!wheels.equals(lastWheels)) {
                lastNodes=List.copyOf(nodes);lastLevel=mc.level;lastView=view;lastWheels=List.copyOf(wheels);cached=null;
                try { if(wheels.size()==nodes.size()) cached=TrackPath.build(wheels); } catch(IllegalArgumentException ignored) {}
            }
            if(cached!=null) for(int i=0;i<cached.points().size();i++)
                line(shown,"route/"+i,point(cached.points().get(i),first),point(cached.points().get((i+1)%cached.points().size()),first),cached.crossing()?RED:GREEN);
            if(cached==null) for(int i=0;i<nodes.size();i++)
                line(shown,"invalid/"+i,Vec3.atCenterOf(nodes.get(i).pos()),Vec3.atCenterOf(nodes.get((i+1)%nodes.size()).pos()),RED);
        }
        clean(shown);
    }
    private static boolean isBelt(net.minecraft.world.item.ItemStack item) { return BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals("create:belt_connector"); }
    private static Vec3 point(TrackPath.Point p,WheelBlockEntity first) {
        Vec3 offset=switch(first.axis()) { case X->new Vec3(0,p.y(),p.x());case Y->new Vec3(p.x(),0,p.y());case Z->new Vec3(p.x(),p.y(),0); };
        Vec3 axle=switch(first.axis()) { case X->new Vec3(first.geometry().axial(),0,0);case Y->new Vec3(0,first.geometry().axial(),0);case Z->new Vec3(0,0,first.geometry().axial()); };
        return Vec3.atCenterOf(first.getBlockPos()).add(offset).add(axle);
    }
    private static String key(Set<String> set,String suffix) { String key="trackssimulate/selection/"+suffix;set.add(key);return key; }
    private static void box(Set<String> shown,String key,WheelBlockEntity w) {
        double r=w.radius()+.04,half=w.width()/2+.04;
        double x=w.axis()==Direction.Axis.X?half:r,y=w.axis()==Direction.Axis.Y?half:r,z=w.axis()==Direction.Axis.Z?half:r;
        Vec3 c=w.wheelCenter(1);
        // Sable's Catnip outline mixins transform plot coordinates at render time.
        Outliner.getInstance().showAABB(key(shown,key),new AABB(c.x-x,c.y-y,c.z-z,c.x+x,c.y+y,c.z+z)).colored(YELLOW).lineWidth(LINE_WIDTH).disableLineNormals();
    }
    private static void line(Set<String> shown,String key,Vec3 a,Vec3 b,int color) {
        if(a.distanceToSqr(b)>1e-10) Outliner.getInstance().showLine(key(shown,key),a,b).colored(color).lineWidth(LINE_WIDTH).disableLineNormals();
    }
    private static void clean(Set<String> keep) { for(String key:visible) if(!keep.contains(key)) Outliner.getInstance().remove(key);visible.clear();visible.addAll(keep); }
    private static void forget() { TrackSelections.clientView=null;TrackSelections.clientPending=false;lastNodes=List.of();cached=null;lastLevel=null;lastView=null;clean(Set.of()); }
    private static void cancel() {
        var mc=Minecraft.getInstance();
        if(mc.getConnection()!=null&&(TrackSelections.clientView!=null||TrackSelections.clientPending))
            PacketDistributor.sendToServer(new TrackSelections.Cancel(TrackSelections.clientView==null?TrackSelections.NONE:TrackSelections.clientView.id()));
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
    @SubscribeEvent public static void disconnect(ClientPlayerNetworkEvent.LoggingOut e) { forget(); }
}
