// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.index;

import com.tterrag.registrate.util.entry.BlockEntityEntry;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.trackssimulate.content.blocks.sable_track.SableTrackRenderer;
import com.simibubi.create.foundation.data.CreateRegistrate;

public class TracksBlockEntityTypes {
   private static final CreateRegistrate REGISTRATE = Tracks.getRegistrate();
   public static final BlockEntityEntry<SableTrackBlockEntity> SABLE_TRACK = REGISTRATE.blockEntity("sable_track", SableTrackBlockEntity::new)
      .validBlocks(TracksBlocks.TRACK_MOUNT, TracksBlocks.SMALL_SUSPENSION_TRACK,
         TracksBlocks.SUSPENSION_TRACK, TracksBlocks.LARGE_SUSPENSION_TRACK,
         TracksBlocks.SMALL_TRACK_DRIVE_WHEEL, TracksBlocks.TRACK_DRIVE_WHEEL,
         TracksBlocks.LARGE_TRACK_DRIVE_WHEEL, TracksBlocks.TRACK_GUIDE_WHEEL)
      .renderer(() -> SableTrackRenderer::new)
      .register();

   public static void init() {
   }
}
