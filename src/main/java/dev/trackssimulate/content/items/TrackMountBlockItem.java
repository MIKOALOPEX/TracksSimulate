// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.items;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

public class TrackMountBlockItem extends BlockItem {
   public TrackMountBlockItem(Block block, Properties properties) {
      super(block, properties);
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getClickedFace() != Direction.UP) {
         return super.useOn(context);
      } else {
         BlockPos raisedPos = context.getClickedPos().relative(context.getClickedFace()).above();
         BlockPlaceContext raisedContext = BlockPlaceContext.at(new BlockPlaceContext(context), raisedPos, Direction.UP);
         return this.place(raisedContext);
      }
   }
}
