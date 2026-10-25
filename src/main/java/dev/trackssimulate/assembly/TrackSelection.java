package dev.trackssimulate.assembly;

import com.simibubi.create.AllItems;
import com.simibubi.create.AllDataComponents;
import dev.ryanhcode.sable.Sable;
import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.trackssimulate.trackpath.OrderedBeltPath;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.*;

/** Server-owned drafts, held only for the current hand/slot/session. */
public final class TrackSelection {
    private static final Map<UUID,Draft> DRAFTS=new HashMap<>();
    public static View clientView;
    public static boolean clientPending;
    public static final UUID NONE=new UUID(0,0);
    private static final class Draft {
        final UUID id=UUID.randomUUID();final Level level;final InteractionHand hand;final int slot;
        final ItemStack held,other;final List<BlockPos> nodes=new ArrayList<>();
        Draft(Player p,InteractionHand h){level=p.level();hand=h;slot=p.getInventory().selected;held=p.getItemInHand(h);other=p.getItemInHand(h==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);}
        boolean valid(Player p){return p.level()==level&&p.isAlive()&&!p.isSpectator()&&p.getInventory().selected==slot&&p.getItemInHand(hand)==held&&AllItems.BELT_CONNECTOR.isIn(held)&&p.getItemInHand(hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND)==other&&p.containerMenu==p.inventoryMenu;}
    }
    public static void clearAll(){DRAFTS.clear();}
    public static void forget(Player p){DRAFTS.remove(p.getUUID());}
    private static void clear(Player p){DRAFTS.remove(p.getUUID());sync(p);}
    private static void sync(Player p) {
        if(!(p instanceof ServerPlayer sp))return;var d=DRAFTS.get(p.getUUID());
        PacketDistributor.sendToPlayer(sp,d==null?new View(NONE,InteractionHand.MAIN_HAND,0,List.of()):new View(d.id,d.hand,d.slot,List.copyOf(d.nodes)));
    }
    public static void tick(PlayerTickEvent.Post event){var p=event.getEntity();if(p.level().isClientSide)return;var d=DRAFTS.get(p.getUUID());if(d!=null&&!d.valid(p))clear(p);}
    public static void rightClick(PlayerInteractEvent.RightClickBlock e) {
        var p=e.getEntity();var level=e.getLevel();var pos=e.getPos();var item=e.getItemStack();
        if(!AllItems.BELT_CONNECTOR.isIn(item)||!(level.getBlockEntity(pos) instanceof SableTrackBlockEntity be)) {
            if(!level.isClientSide&&DRAFTS.containsKey(p.getUUID()))clear(p);return;
        }
        e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);
        if(level.isClientSide){clientPending=true;return;}
        if(!p.mayBuild()||!level.mayInteract(p,pos))return;
        item.remove(AllDataComponents.BELT_FIRST_SHAFT);
        var d=DRAFTS.get(p.getUUID());
        if(d!=null&&(!d.valid(p)||d.hand!=e.getHand())){clear(p);d=null;}
        if(p.isShiftKeyDown()) {
            if(d!=null){d.nodes.removeLast();if(d.nodes.isEmpty())clear(p);else sync(p);say(p,"已撤销最后一个点");}
            else {if(be.beltId()==null){say(p,"此轮未连接履带");return;}TrackAssemblies.remove(be,p,"belt_item");say(p,p.hasInfiniteMaterials()?"已拆除履带":"已拆除履带，返还 1 个传送带");}
            return;
        }
        if(be.beltId()!=null){say(p,"此轮已连接；手持传送带潜行右键可拆带");return;}
        if(!be.effectivePart().isWheel()){say(p,"请选择已安装的轮子");return;}
        if(d==null){d=new Draft(p,e.getHand());DRAFTS.put(p.getUUID(),d);}
        if(!d.nodes.isEmpty()) {
            if(!(level.getBlockEntity(d.nodes.getFirst()) instanceof SableTrackBlockEntity first)){clear(p);return;}
            if(TrackAssemblies.axis(first)!=TrackAssemblies.axis(be)||first.getBlockPos().get(TrackAssemblies.axis(first))!=pos.get(TrackAssemblies.axis(first))
                ||Sable.HELPER.getContaining(first)!=Sable.HELPER.getContaining(be)){say(p,"此功能块要求同一车体、同轴平面的轮子");return;}
            if(pos.distSqr(d.nodes.getFirst())>128*128){say(p,"选点范围超过 128 格");return;}
            if(pos.equals(d.nodes.getFirst())) {
                if(d.nodes.size()<2){say(p,"至少选择两个轮子，再点首轮闭合");return;}
                var members=new ArrayList<SableTrackBlockEntity>();
                for(var node:d.nodes) {
                    if(!level.hasChunkAt(node)||!(level.getBlockEntity(node) instanceof SableTrackBlockEntity w)||w.beltId()!=null){say(p,"轮子已改变，请重新选点");clear(p);return;}
                    if(TrackAssemblies.axis(w)!=TrackAssemblies.axis(first)||node.get(TrackAssemblies.axis(first))!=first.getBlockPos().get(TrackAssemblies.axis(first))||Sable.HELPER.getContaining(w)!=Sable.HELPER.getContaining(first)||!level.mayInteract(p,node)) {say(p,"轮组姿态或权限已改变，请重新选点");clear(p);return;}
                    members.add(w);
                }
                var path=OrderedBeltPath.planInstalled(TrackAssemblies.installationWheels(members),TrackAssemblies.wheels(members,1));
                if(!path.valid()){say(p,path.error());Tracks.LOGGER.info("[TS-SELECTION] rejected nodes={} reason={} installation={} pose={} sides={}",d.nodes,path.error(),TrackAssemblies.installationWheels(members),TrackAssemblies.wheels(members,1),path.sides());return;}
                TrackAssemblies.create(members,p,path.sides());
                if(!p.hasInfiniteMaterials())item.shrink(1);
                clear(p);say(p,"履带已连接（"+members.size()+" 轮）；潜行右键拆带");return;
            }
            if(d.nodes.contains(pos)){say(p,"轮子已选中；点首轮闭合，潜行右键撤销末点");return;}
            if(d.nodes.size()>=OrderedBeltPath.MAX_NODES){say(p,"最多 64 个轮子");return;}
            if(pos.distSqr(d.nodes.getLast())>64*64){say(p,"相邻选点距离超过 64 格");return;}
        }
        d.nodes.add(pos.immutable());sync(p);say(p,"已选 "+d.nodes.size()+" 个轮；点首轮闭合，潜行右键撤销");
    }
    private static void say(Player p,String text){p.displayClientMessage(Component.literal(text),true);}
    public static void register(PayloadRegistrar r) {
        r.playToClient(View.TYPE,View.CODEC,(p,c)->c.enqueueWork(()->{clientView=p.nodes.isEmpty()?null:p;clientPending=false;}));
        r.playToServer(Cancel.TYPE,Cancel.CODEC,(p,c)->c.enqueueWork(()->{var d=DRAFTS.get(c.player().getUUID());if(d!=null&&(p.id.equals(NONE)||d.id.equals(p.id)))clear(c.player());}));
    }
    public record View(UUID id,InteractionHand hand,int slot,List<BlockPos> nodes) implements CustomPacketPayload {
        public static final Type<View> TYPE=new Type<>(Tracks.path("selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf,View> CODEC=new StreamCodec<>() {
            public View decode(RegistryFriendlyByteBuf b){UUID id=b.readUUID();var hand=b.readEnum(InteractionHand.class);int slot=b.readVarInt(),n=b.readVarInt();if(n<0||n>64)throw new IllegalArgumentException("Selection size");var nodes=new ArrayList<BlockPos>();for(int i=0;i<n;i++)nodes.add(b.readBlockPos());return new View(id,hand,slot,List.copyOf(nodes));}
            public void encode(RegistryFriendlyByteBuf b,View p){b.writeUUID(p.id);b.writeEnum(p.hand);b.writeVarInt(p.slot);b.writeVarInt(p.nodes.size());for(var pos:p.nodes)b.writeBlockPos(pos);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Cancel(UUID id) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE=new Type<>(Tracks.path("selection_cancel"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Cancel> CODEC=new StreamCodec<>() {
            public Cancel decode(RegistryFriendlyByteBuf b){return new Cancel(b.readUUID());}
            public void encode(RegistryFriendlyByteBuf b,Cancel p){b.writeUUID(p.id);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
