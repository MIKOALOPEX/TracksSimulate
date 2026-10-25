// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.network;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SelectTrackTuningModePayload(BlockPos pos, String key) implements CustomPacketPayload {
   public static final Type<SelectTrackTuningModePayload> TYPE = new Type(Tracks.path("select_track_tuning_mode"));
   public static final StreamCodec<RegistryFriendlyByteBuf, SelectTrackTuningModePayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, SelectTrackTuningModePayload>() {
      public SelectTrackTuningModePayload decode(RegistryFriendlyByteBuf buffer) {
         return new SelectTrackTuningModePayload(buffer.readBlockPos(), buffer.readUtf(64));
      }

      public void encode(RegistryFriendlyByteBuf buffer, SelectTrackTuningModePayload payload) {
         buffer.writeBlockPos(payload.pos());
         buffer.writeUtf(payload.key(), 64);
      }
   };

   public static void handle(SelectTrackTuningModePayload payload, IPayloadContext context) {
      context.enqueueWork(
         () -> {
            var player=context.player();var level=player.level();
            if (!java.util.Set.of("strength","spring","damping","bump_clearance","bump_force","max_impulse","drive","grip").contains(payload.key())
                || !player.mayBuild() || !level.hasChunkAt(payload.pos())) return;
            var point=payload.pos().getCenter();
            var sub=dev.ryanhcode.sable.Sable.HELPER.getContaining(level,payload.pos());
            if(sub!=null)point=sub.logicalPose().transformPosition(point);
            if (level.getBlockEntity(payload.pos()) instanceof SableTrackBlockEntity track && player.distanceToSqr(point)<64.0) {
               track.selectScrollTuningMode(payload.key());
            }
         }
      );
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
