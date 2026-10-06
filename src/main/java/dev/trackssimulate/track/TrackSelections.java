package dev.trackssimulate.track;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import dev.trackssimulate.wheel.WheelBlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

/** Unfinished choices belong to a short-lived player session, never to an ItemStack. */
public final class TrackSelections {
    public record Node(BlockPos pos,int hint) {}
    public static final UUID NONE=new UUID(0,0);
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    public static View clientView;
    public static boolean clientPending;
    public static final class Session {
        final UUID id=UUID.randomUUID();
        final Level level;
        final InteractionHand hand;
        final int slot;
        final ItemStack held,other;
        public final List<Node> nodes=new ArrayList<>();
        Session(Player p,InteractionHand h) {
            level=p.level();hand=h;slot=p.getInventory().selected;held=p.getItemInHand(h);
            other=p.getItemInHand(h==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);
        }
        boolean valid(Player p) {
            return p.isAlive()&&!p.isSpectator()&&p.level()==level&&slot==p.getInventory().selected
                &&p.getItemInHand(hand)==held&&!held.isEmpty()
                &&p.getItemInHand(hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND)==other
                &&p.containerMenu==p.inventoryMenu;
        }
        boolean nodesPresent() {
            for(Node node:nodes) if(!level.hasChunkAt(node.pos)||!(level.getBlockEntity(node.pos) instanceof WheelBlockEntity w)||w.loopId()!=null) return false;
            return true;
        }
    }
    public static Session get(Player p) {
        Session s=SESSIONS.get(p.getUUID());
        if(s!=null&&(!s.valid(p)||!s.nodesPresent())) { clear(p);return null; }return s;
    }
    public static Session begin(Player p,InteractionHand hand) { Session s=new Session(p,hand);SESSIONS.put(p.getUUID(),s);return s; }
    public static void sync(Player p) {
        Session s=get(p);
        if(p instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp,s==null?View.empty():new View(s.id,s.hand,s.slot,s.level.dimension().location(),List.copyOf(s.nodes)));
    }
    public static void clear(Player p) { SESSIONS.remove(p.getUUID());if(p instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp,View.empty()); }
    public static void tick(PlayerTickEvent.Post e) { if(!e.getEntity().level().isClientSide) get(e.getEntity()); }
    public static void logout(PlayerEvent.PlayerLoggedOutEvent e) { SESSIONS.remove(e.getEntity().getUUID()); }
    public static void stopped(ServerStoppedEvent e) { SESSIONS.clear(); }
    public static void register(RegisterPayloadHandlersEvent e) {
        var r=e.registrar("2");
        r.playToClient(View.TYPE,View.CODEC,(p,c)->{ clientView=p.nodes.isEmpty()?null:p;clientPending=false; });
        r.playToServer(Cancel.TYPE,Cancel.CODEC,(p,c)->{
            Session s=get(c.player());if(s!=null&&(p.id.equals(NONE)||s.id.equals(p.id))) clear(c.player());
        });
    }
    public record View(UUID id,InteractionHand hand,int slot,ResourceLocation dimension,List<Node> nodes) implements CustomPacketPayload {
        public static final Type<View> TYPE=new Type<>(ResourceLocation.parse("trackssimulate:selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf,View> CODEC=new StreamCodec<>() {
            public View decode(RegistryFriendlyByteBuf b) {
                UUID id=b.readUUID();InteractionHand hand=b.readEnum(InteractionHand.class);int slot=b.readVarInt();ResourceLocation dim=b.readResourceLocation();
                int n=b.readVarInt();if(n<0||n>TrackPath.MAX_WHEELS) throw new IllegalArgumentException("Selection size");
                List<Node> nodes=new ArrayList<>();for(int i=0;i<n;i++) nodes.add(new Node(b.readBlockPos(),b.readUnsignedByte()));
                return new View(id,hand,slot,dim,List.copyOf(nodes));
            }
            public void encode(RegistryFriendlyByteBuf b,View p) {
                b.writeUUID(p.id);b.writeEnum(p.hand);b.writeVarInt(p.slot);b.writeResourceLocation(p.dimension);b.writeVarInt(p.nodes.size());
                for(Node n:p.nodes) { b.writeBlockPos(n.pos);b.writeByte(n.hint); }
            }
        };
        public static View empty() { return new View(NONE,InteractionHand.MAIN_HAND,0,ResourceLocation.parse("minecraft:overworld"),List.of()); }
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Cancel(UUID id) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE=new Type<>(ResourceLocation.parse("trackssimulate:cancel_selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Cancel> CODEC=new StreamCodec<>() {
            public Cancel decode(RegistryFriendlyByteBuf b) { return new Cancel(b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b,Cancel p) { b.writeUUID(p.id); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private TrackSelections() {}
}
