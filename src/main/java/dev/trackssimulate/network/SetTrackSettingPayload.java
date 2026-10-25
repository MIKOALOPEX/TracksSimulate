package dev.trackssimulate.network;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.config.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;

/** One bounded editor submission, including a topology expectation and an acknowledgement id. */
public record SetTrackSettingPayload(BlockPos pos, UUID requestId, UUID expectedBelt, OffsetScope scope,
        Map<TrackSetting,Double> values) implements CustomPacketPayload {
    public SetTrackSettingPayload { values=Map.copyOf(values); }
    public static final Type<SetTrackSettingPayload> TYPE=new Type<>(Tracks.path("set_track_setting"));
    public static final StreamCodec<RegistryFriendlyByteBuf,SetTrackSettingPayload> STREAM_CODEC=new StreamCodec<>() {
        public SetTrackSettingPayload decode(RegistryFriendlyByteBuf b) {
            var pos=b.readBlockPos();var id=b.readUUID();var belt=b.readBoolean()?b.readUUID():null;var scope=b.readEnum(OffsetScope.class);
            int count=b.readVarInt();if(count<1||count>TrackSetting.values().length)throw new IllegalArgumentException("Invalid field count");
            var values=new EnumMap<TrackSetting,Double>(TrackSetting.class);
            for(int i=0;i<count;i++)if(values.put(b.readEnum(TrackSetting.class),b.readDouble())!=null)throw new IllegalArgumentException("Duplicate field");
            return new SetTrackSettingPayload(pos,id,belt,scope,values);
        }
        public void encode(RegistryFriendlyByteBuf b,SetTrackSettingPayload p) {
            b.writeBlockPos(p.pos);b.writeUUID(p.requestId);b.writeBoolean(p.expectedBelt!=null);if(p.expectedBelt!=null)b.writeUUID(p.expectedBelt);
            b.writeEnum(p.scope);b.writeVarInt(p.values.size());p.values.forEach((k,v)->{b.writeEnum(k);b.writeDouble(v);});
        }
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(SetTrackSettingPayload p,IPayloadContext context) {
        context.enqueueWork(()->{
            if(!(context.player() instanceof ServerPlayer player))return;
            try {
                String result=TrackSettingsSync.apply(player,p.pos,p.expectedBelt,p.scope,p.values);
                context.reply(new TrackConfigFeedback(p.pos,p.requestId,true,result));
            }catch(IllegalArgumentException e) {
                context.reply(new TrackConfigFeedback(p.pos,p.requestId,false,e.getMessage()));
                Tracks.LOGGER.info("[TS-CONFIG] operation=push_rejected source={} scope={} requested={} reason={}",p.pos,p.scope,p.values,e.getMessage());
            }
        });
    }
}
