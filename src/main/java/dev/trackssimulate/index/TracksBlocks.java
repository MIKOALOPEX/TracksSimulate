// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.index;

import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.SharedProperties;
import com.simibubi.create.foundation.data.TagGen;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntry;
import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlock;
import dev.trackssimulate.content.blocks.sable_track.SableTrackRole;
import dev.trackssimulate.content.blocks.sable_track.SableTrackPart;
import dev.trackssimulate.content.items.TrackMountBlockItem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.material.MapColor;

public class TracksBlocks {
   private static final CreateRegistrate REGISTRATE = Tracks.getRegistrate();
   // Keep the old mount registered for rebuild.1 worlds; new construction uses wheels directly.
   public static final BlockEntry<SableTrackBlock> TRACK_MOUNT = REGISTRATE.block(
         "track_mount", properties -> new SableTrackBlock(properties, SableTrackRole.MOUNT))
      .initialProperties(SharedProperties::stone)
      .properties(p -> p.mapColor(MapColor.COLOR_GRAY).noOcclusion().isRedstoneConductor((state, level, pos) -> false))
      .transform(TagGen.axeOrPickaxe())
      .addLayer(() -> RenderType::cutoutMipped)
      .tag(AllBlockTags.SAFE_NBT.tag)
      .blockstate(BlockStateGen.horizontalBlockProvider(true))
      .item(TrackMountBlockItem::new)
      .transform(ModelGen.customItemModel())
      .register();

   public static final BlockEntry<SableTrackBlock> SMALL_SUSPENSION_TRACK = wheel("small_suspension_track", SableTrackPart.SMALL_SUSPENSION);
   public static final BlockEntry<SableTrackBlock> SUSPENSION_TRACK = wheel("suspension_track", SableTrackPart.SUSPENSION);
   public static final BlockEntry<SableTrackBlock> LARGE_SUSPENSION_TRACK = wheel("large_suspension_track", SableTrackPart.LARGE_SUSPENSION);
   public static final BlockEntry<SableTrackBlock> SMALL_TRACK_DRIVE_WHEEL = wheel("small_track_drive_wheel", SableTrackPart.SMALL_DRIVE);
   public static final BlockEntry<SableTrackBlock> TRACK_DRIVE_WHEEL = wheel("track_drive_wheel", SableTrackPart.DRIVE);
   public static final BlockEntry<SableTrackBlock> LARGE_TRACK_DRIVE_WHEEL = wheel("large_track_drive_wheel", SableTrackPart.LARGE_DRIVE);

   public static final BlockEntry<SableTrackBlock> TRACK_GUIDE_WHEEL = wheel("track_guide_wheel", SableTrackPart.GUIDE);

   private static BlockEntry<SableTrackBlock> wheel(String name, SableTrackPart part) {
      return REGISTRATE.block(name, properties -> new SableTrackBlock(properties, part))
         .initialProperties(SharedProperties::stone)
         .properties(p -> p.mapColor(MapColor.COLOR_GRAY).noOcclusion().isRedstoneConductor((state, level, pos) -> false))
         .transform(TagGen.axeOrPickaxe())
         .tag(AllBlockTags.SAFE_NBT.tag)
         .blockstate(BlockStateGen.horizontalBlockProvider(true))
         .item()
         .transform(ModelGen.customItemModel())
         .register();
   }

   public static void init() {
   }
}
