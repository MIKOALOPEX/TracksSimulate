package dev.trackssimulate.network;

import dev.trackssimulate.Tracks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server outcome stays visible inside the setup screen, including a rejected edit. */
public record TrackConfigFeedback(BlockPos pos,java.util.UUID requestId,boolean success,String message) implements CustomPacketPayload {
    public TrackConfigFeedback(BlockPos pos,boolean success,String message){this(pos,new java.util.UUID(0,0),success,message);}
    public static final Type<TrackConfigFeedback> TYPE=new Type<>(Tracks.path("config_feedback"));
    public static final StreamCodec<RegistryFriendlyByteBuf,TrackConfigFeedback> CODEC=new StreamCodec<>() {
        public TrackConfigFeedback decode(RegistryFriendlyByteBuf b){return new TrackConfigFeedback(b.readBlockPos(),b.readUUID(),b.readBoolean(),b.readUtf(2048));}
        public void encode(RegistryFriendlyByteBuf b,TrackConfigFeedback p){b.writeBlockPos(p.pos);b.writeUUID(p.requestId);b.writeBoolean(p.success);b.writeUtf(p.message,2048);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
