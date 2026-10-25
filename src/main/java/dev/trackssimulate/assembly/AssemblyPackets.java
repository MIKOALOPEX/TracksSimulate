package dev.trackssimulate.assembly;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.*;

public final class AssemblyPackets {
    public static void register(PayloadRegistrar r) {
        r.playToClient(Motion.TYPE,Motion.CODEC,(p,c)->c.enqueueWork(()->{
            var level=c.player().level();if(!level.hasChunkAt(p.pos))return;
            if(level.getBlockEntity(p.pos) instanceof SableTrackBlockEntity wheel)
                wheel.receivePassiveMotion(p.id,p.tick,p.distance,p.speed,p.active);
        }));
        r.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->c.enqueueWork(()->{if(p.assembly==null)TrackAssemblies.CLIENT.remove(p.id);else TrackAssemblies.CLIENT.put(p.id,p.assembly);}));
        r.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{
            var level=c.player().level();if(!level.hasChunkAt(p.pos))return;
            if(level.getBlockEntity(p.pos) instanceof SableTrackBlockEntity be&&p.id.equals(be.beltId())) {
                var sub=dev.ryanhcode.sable.Sable.HELPER.getContaining(be);var at=p.pos.getCenter();if(sub!=null)at=sub.logicalPose().transformPosition(at);
                if(c.player().distanceToSqr(at)<=128*128)c.reply(new Snapshot(p.id,TrackAssemblies.get(be)));
            }
        }));
        TrackSelection.register(r);
    }
    public static void broadcast(ServerLevel level,UUID id,TrackAssemblies.Assembly a){PacketDistributor.sendToPlayersInDimension(level,new Snapshot(id,a));}
    /** Root-only dynamic update; topology, configuration and material NBT are not retransmitted. */
    public record Motion(BlockPos pos,UUID id,long tick,double distance,double speed,boolean active) implements CustomPacketPayload {
        public static final Type<Motion> TYPE=new Type<>(Tracks.path("assembly_motion"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Motion> CODEC=new StreamCodec<>() {
            public Motion decode(RegistryFriendlyByteBuf b) {
                var p=new Motion(b.readBlockPos(),b.readUUID(),b.readLong(),b.readDouble(),b.readDouble(),b.readBoolean());
                if(!Double.isFinite(p.distance)||!Double.isFinite(p.speed))throw new IllegalArgumentException("Invalid motion snapshot");
                return p;
            }
            public void encode(RegistryFriendlyByteBuf b,Motion p){b.writeBlockPos(p.pos);b.writeUUID(p.id);b.writeLong(p.tick);b.writeDouble(p.distance);b.writeDouble(p.speed);b.writeBoolean(p.active);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(UUID id,TrackAssemblies.Assembly assembly) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(Tracks.path("assembly"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf b){var id=b.readUUID();if(!b.readBoolean())return new Snapshot(id,null);var axis=b.readEnum(Direction.Axis.class);int n=b.readVarInt();if(n<2||n>64)throw new IllegalArgumentException("Assembly size");var points=new ArrayList<BlockPos>();for(int i=0;i<n;i++)points.add(b.readBlockPos());var sides=new ArrayList<Integer>();for(int i=0;i<n;i++){int side=b.readByte();if(side!=1&&side!=-1)throw new IllegalArgumentException("Winding");sides.add(side);}return new Snapshot(id,new TrackAssemblies.Assembly(id,points,axis,sides,false,b.readVarInt()));}
            public void encode(RegistryFriendlyByteBuf b,Snapshot p){b.writeUUID(p.id);b.writeBoolean(p.assembly!=null);if(p.assembly!=null){b.writeEnum(p.assembly.axis());b.writeVarInt(p.assembly.offsets().size());for(var pos:p.assembly.offsets())b.writeBlockPos(pos);for(int side:p.assembly.sides())b.writeByte(side);b.writeVarInt(p.assembly.routingVersion());}}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Request(BlockPos pos,UUID id) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(Tracks.path("assembly_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readBlockPos(),b.readUUID());}
            public void encode(RegistryFriendlyByteBuf b,Request p){b.writeBlockPos(p.pos);b.writeUUID(p.id);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
