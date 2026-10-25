package dev.trackssimulate.network;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.ryanhcode.sable.Sable;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AdjustTrackPayload(BlockPos pos, String key, int direction) implements CustomPacketPayload {
    private static final Set<String> KEYS=Set.of("strength","spring","damping","bump_clearance","bump_force","max_impulse","drive","grip","lateral","longitudinal","height","reset","hide");
    public static final Type<AdjustTrackPayload> TYPE=new Type<>(Tracks.path("adjust_track"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AdjustTrackPayload> STREAM_CODEC=new StreamCodec<>() {
        public AdjustTrackPayload decode(RegistryFriendlyByteBuf b){return new AdjustTrackPayload(b.readBlockPos(),b.readUtf(32),b.readVarInt());}
        public void encode(RegistryFriendlyByteBuf b,AdjustTrackPayload p){b.writeBlockPos(p.pos);b.writeUtf(p.key,32);b.writeVarInt(p.direction);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(AdjustTrackPayload payload,IPayloadContext context){
        context.enqueueWork(()->{
            var player=context.player();var level=player.level();
            if(!KEYS.contains(payload.key)||Math.abs(payload.direction)!=1||!level.hasChunkAt(payload.pos)||!player.mayBuild()||!level.mayInteract(player,payload.pos))return;
            var sub=Sable.HELPER.getContaining(level,payload.pos);
            var point=payload.pos.getCenter();if(sub!=null)point=sub.logicalPose().transformPosition(point);
            if(player.distanceToSqr(point)>64||!(level.getBlockEntity(payload.pos) instanceof SableTrackBlockEntity track))return;
            try { switch(payload.key){
                case "lateral"->track.adjustLateralOffset(payload.direction);
                case "longitudinal"->track.adjustLongitudinalOffset(payload.direction);
                case "height"->track.adjustHeightOffset(payload.direction);
                case "reset"->track.resetTuningToConnectedTrack();
                case "hide"->track.toggleVisualSuspensionHidden();
                default->track.adjustTuning(payload.key,payload.direction);
            }
            Tracks.LOGGER.info("[TS-CONFIG] player={} mount={} key={} direction={} value={}",player.getGameProfile().getName(),payload.pos,payload.key,payload.direction,track.getSettingValue(payload.key));
            } catch(IllegalArgumentException e) {
                context.reply(new TrackConfigFeedback(payload.pos,false,e.getMessage()));
                Tracks.LOGGER.info("[TS-CONFIG] operation=rejected mount={} key={} reason={}",payload.pos,payload.key,e.getMessage());
            }
        });
    }
}
