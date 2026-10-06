package dev.trackssimulate.track;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.trackssimulate.wheel.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.util.*;

public final class TrackInteractions {
    private static final ResourceLocation SHAFT=ResourceLocation.parse("create:shaft");
    private static final ResourceLocation BELT=ResourceLocation.parse("create:belt_connector");
    private static final ResourceLocation WRENCH=ResourceLocation.parse("create:wrench");

    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        Player player=event.getEntity();Level level=event.getLevel();BlockPos pos=event.getPos();ItemStack stack=event.getItemStack();
        boolean wheel=level.getBlockEntity(pos) instanceof WheelBlockEntity;
        ResourceLocation item=BuiltInRegistries.ITEM.getKey(stack.getItem());
        boolean selecting=level.isClientSide?TrackSelections.clientView!=null||TrackSelections.clientPending:TrackSelections.get(player)!=null;
        if(selecting&&(!item.equals(BELT)||!wheel)) {
            if(level.isClientSide) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new TrackSelections.Cancel(TrackSelections.clientView==null?TrackSelections.NONE:TrackSelections.clientView.id()));
                TrackSelections.clientView=null;TrackSelections.clientPending=false;
            }
            else TrackSelections.clear(player);
        }
        if(stack.getItem() instanceof WheelItem wheelItem) {
            if(!BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).equals(SHAFT)) return;
            cancel(event);
            if(level.isClientSide||!allowed(player,level,pos)) return;
            var old=level.getBlockState(pos);
            if(old.hasProperty(BlockStateProperties.WATERLOGGED)&&old.getValue(BlockStateProperties.WATERLOGGED)) { say(player,"首版请在未浸水的传动杆上安装");return; }
            var state=wheelItem.getBlock().defaultBlockState().setValue(WheelBlock.AXIS,old.getValue(BlockStateProperties.AXIS))
                .setValue(WheelBlock.POSITIVE,event.getFace()==null||event.getFace().getAxisDirection()==Direction.AxisDirection.POSITIVE);
            if(level.setBlock(pos,state,3)) {
                if(!player.isCreative()) stack.shrink(1);
                say(player,"轮子已安装；用 Create 传送带依次选轮，再点首轮闭合");
            }
            return;
        }
        if(item.equals(BELT)&&wheel) {
            cancel(event);
            if(level.isClientSide) { TrackSelections.clientPending=true;return; }
            if(!allowed(player,level,pos)) return;
            stack.remove(AllDataComponents.BELT_FIRST_SHAFT);
            // Remove the previous prototype's persistent draft if this is an old belt item.
            if(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains("TracksSimulateSelection"))
                CustomData.update(DataComponents.CUSTOM_DATA,stack,t->t.remove("TracksSimulateSelection"));
            var session=TrackSelections.get(player);
            if(session!=null&&session.hand!=event.getHand()) { TrackSelections.clear(player);session=null; }
            if(player.isShiftKeyDown()) {
                if(session!=null&&!session.nodes.isEmpty()) {
                    session.nodes.removeLast();if(session.nodes.isEmpty()) TrackSelections.clear(player);else TrackSelections.sync(player);
                    selectionStatus(player,session,"已撤销最后一个点");
                } else { removeLoop(player,(WheelBlockEntity)level.getBlockEntity(pos));TrackSelections.clear(player); }
                return;
            }
            select(player,stack,(WheelBlockEntity)level.getBlockEntity(pos),event.getHand(),event.getHitVec().getLocation());
            TrackSelections.sync(player);
            return;
        }
        if(stack.is(dev.trackssimulate.TrackContent.DEBUG_STICK.get())&&wheel) {
            cancel(event);
            if(level.isClientSide&&allowed(player,level,pos)) dev.trackssimulate.client.SuspensionScreen.open((WheelBlockEntity)level.getBlockEntity(pos));
            return;
        }
        if(item.equals(WRENCH)&&wheel) {
            // Leave sneaking to Create's IWrenchable removal, including its BreakEvent.
            if(player.isShiftKeyDown()) return;
            cancel(event);
            WheelMaterials.remove((WheelBlockEntity)level.getBlockEntity(pos),player);
            return;
        }
        if(wheel&&WheelMaterials.apply((WheelBlockEntity)level.getBlockEntity(pos),player,stack,event.getFace()))cancel(event);
    }
    private static void select(Player player,ItemStack stack,WheelBlockEntity target,InteractionHand hand,Vec3 hit) {
        Level level=target.getLevel();BlockPos pos=target.getBlockPos();
        var session=TrackSelections.get(player);
        List<BlockPos> selected=session==null?new ArrayList<>():new ArrayList<>(session.nodes.stream().map(TrackSelections.Node::pos).toList());
        if(target.loopId()!=null) { say(player,"该轮已连接；先潜行使用传送带拆除整环");return; }
        if(selected.isEmpty()) { TrackSelections.begin(player,hand).nodes.add(new TrackSelections.Node(pos,routeHint(target,hit)));say(player,"已选第 1 个轮子；负重轮按下部承托连接，导轮/托带轮可点边缘指定绕行侧");return; }
        if(!level.hasChunkAt(selected.getFirst())||!(level.getBlockEntity(selected.getFirst()) instanceof WheelBlockEntity first)) {
            TrackSelections.clear(player);say(player,"首轮不存在或未加载，已清空选择");return;
        }
        if(!target.samePlane(first)
            ||Sable.HELPER.getContaining(target)!=Sable.HELPER.getContaining(first)) { say(player,"首版要求同一车体、平行轮轴且处于同一平面");return; }
        if(pos.equals(selected.getFirst())) {
            List<TrackPath.Wheel> wheels=new ArrayList<>();
            for(BlockPos p:selected) {
                if(!level.hasChunkAt(p)||!(level.getBlockEntity(p) instanceof WheelBlockEntity w)||w.loopId()!=null||!allowed(player,level,p)
                    ||!w.samePlane(first)||Sable.HELPER.getContaining(w)!=Sable.HELPER.getContaining(first)) { say(player,"选择期间轮组发生变化，请撤销后重选");return; }
                wheels.add(w.planningWheel(first.getBlockPos(),session.nodes.get(wheels.size()).hint()));
            }
            try {
                TrackPath.Path path=TrackPath.build(wheels);UUID id=UUID.randomUUID();
                for(BlockPos p:selected) ((WheelBlockEntity)level.getBlockEntity(p)).link(id,selected,session.nodes.stream().mapToInt(TrackSelections.Node::hint).toArray(),path.wheelDirections().stream().mapToInt(Integer::intValue).toArray());
                TrackSelections.clear(player);if(!player.isCreative()) stack.shrink(1);
                if(path.crossing()) {
                    var warning=Component.literal("注意：已放置的履带存在交叉！可潜行使用传送带拆带并调整选点顺序。").withStyle(net.minecraft.ChatFormatting.RED);
                    player.displayClientMessage(warning,true);player.sendSystemMessage(warning);
                } else say(player,"履带已闭合："+selected.size()+" 轮，长度 "+String.format(Locale.ROOT,"%.2f",path.length())+" 格，无交叉（装配原型）");
            } catch(IllegalArgumentException e) { say(player,e.getMessage()+"；已保留选择，可潜行撤销"); }
        } else {
            if(selected.contains(pos)) { say(player,"不能重复选择中间轮；点击首轮闭合");return; }
            if(selected.size()>=TrackPath.MAX_WHEELS||selected.getFirst().distSqr(pos)>TrackPath.MAX_SELECTION_RADIUS*TrackPath.MAX_SELECTION_RADIUS) { say(player,"超过原型轮数/范围上限");return; }
            session.nodes.add(new TrackSelections.Node(pos,routeHint(target,hit)));selected.add(pos);selectionStatus(player,session,"已选第 "+selected.size()+" 个轮子");
        }
    }
    private static void selectionStatus(Player player,TrackSelections.Session session,String prefix) {
        if(session.nodes.size()<2) { say(player,prefix+"；点首轮闭合，潜行撤销");return; }
        List<TrackPath.Wheel> wheels=new ArrayList<>();
        BlockPos origin=session.nodes.getFirst().pos();
        if(!(player.level().getBlockEntity(origin) instanceof WheelBlockEntity first)) return;
        for(var node:session.nodes) {
            if(!(player.level().getBlockEntity(node.pos()) instanceof WheelBlockEntity w)) return;
            wheels.add(w.planningWheel(origin,node.hint()));
        }
        try {
            TrackPath.Path path=TrackPath.build(wheels);
            if(path.crossing()) player.displayClientMessage(Component.literal(prefix+"；当前闭环仍有交叉，预览为红色，可撤销调整").withStyle(net.minecraft.ChatFormatting.RED),true);
            else say(player,prefix+"；已重新规划，无交叉，点首轮闭合");
        } catch(IllegalArgumentException ex) { player.displayClientMessage(Component.literal(prefix+"；"+ex.getMessage()).withStyle(net.minecraft.ChatFormatting.RED),true); }
    }
    private static void removeLoop(Player player,WheelBlockEntity target) {
        if(target.loopId()==null) { say(player,"该轮没有履带");return; }
        Level level=target.getLevel();List<BlockPos> nodes=target.nodes();UUID id=target.loopId();
        for(BlockPos p:nodes) if(!level.hasChunkAt(p)||!allowed(player,level,p)) { say(player,"需要整环已加载且可编辑后才能拆带");return; }
        BeltLifecycle.breakLoop(target,player,"manual");
        say(player,"已拆除整环履带，轮子保留");
    }
    private static boolean allowed(Player player,Level level,BlockPos pos) { return player.mayBuild()&&level.mayInteract(player,pos)&&level.getWorldBorder().isWithinBounds(pos); }
    public static int routeHint(WheelBlockEntity wheel,Vec3 hit) {
        if(wheel.kind()==WheelKind.ROAD&&wheel.axis()!=Direction.Axis.Y) return 0;
        Vec3 d=hit.subtract(Vec3.atCenterOf(wheel.getBlockPos()));
        var body=Sable.HELPER.getContaining(wheel);
        if(body!=null&&d.lengthSqr()>16) d=body.logicalPose().transformPositionInverse(hit).subtract(Vec3.atCenterOf(wheel.getBlockPos()));
        double u=wheel.axis()==Direction.Axis.X?d.z:d.x;
        double v=wheel.axis()==Direction.Axis.Y?d.z:d.y;
        if(Math.max(Math.abs(u),Math.abs(v))<.18) return 0;
        return Math.abs(v)>=Math.abs(u)?(v>=0?1:3):(u>=0?2:4);
    }
    private static void cancel(PlayerInteractEvent.RightClickBlock e) { e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS); }
    private static void say(Player p,String text) { p.displayClientMessage(Component.literal(text),true); }
    private TrackInteractions() {}
}
