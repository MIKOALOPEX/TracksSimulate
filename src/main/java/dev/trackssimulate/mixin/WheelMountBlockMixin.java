// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.mixin;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import dev.trackssimulate.mixin_interface.WheelMountOffsetAccess;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlock;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({WheelMountBlock.class})
public abstract class WheelMountBlockMixin extends HorizontalKineticBlock {
   @Unique
   private static final BooleanProperty TRACKS_HIDDEN = BooleanProperty.create("tracks_hidden");
   @Unique
   private static final VoxelShape TRACKS_HIDDEN_SELECTION_SHAPE = Block.box(0.0, 14.0, 0.0, 16.0, 16.0, 16.0);

   protected WheelMountBlockMixin(Properties properties) {
      super(properties);
   }

   @Inject(
      method = {"<init>"},
      at = {@At("RETURN")}
   )
   private void tracks$initHiddenProperty(Properties properties, CallbackInfo ci) {
      this.registerDefaultState((BlockState)this.defaultBlockState().setValue(TRACKS_HIDDEN, false));
   }

   @Inject(
      method = {"createBlockStateDefinition"},
      at = {@At("TAIL")}
   )
   private void tracks$createHiddenState(Builder<Block, BlockState> builder, CallbackInfo ci) {
      builder.add(new Property[]{TRACKS_HIDDEN});
   }

   @Inject(
      method = {"useItemOn"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void tracks$handleShiftMountToggles(
      ItemStack heldItem,
      BlockState state,
      Level level,
      BlockPos pos,
      Player player,
      InteractionHand hand,
      BlockHitResult hitResult,
      CallbackInfoReturnable<ItemInteractionResult> cir
   ) {
      if (com.simibubi.create.AllItems.WRENCH.isIn(heldItem)) return;
      if (player.isShiftKeyDown()) {
         if (hitResult.getDirection() == Direction.UP) {
            if (!level.isClientSide) {
               tracks$toggleHiddenMount(state, level, pos);
            }

            cir.setReturnValue(ItemInteractionResult.CONSUME);
         } else {
            if (hitResult.getDirection() == state.getValue(HORIZONTAL_FACING)) {
               if (!level.isClientSide && level.getBlockEntity(pos) instanceof WheelMountBlockEntity be) {
                  ((WheelMountOffsetAccess)be).tracks$toggleVisualSuspensionHidden();
                  level.playSound(null, pos, (SoundEvent)SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 1.1F);
               }

               cir.setReturnValue(ItemInteractionResult.CONSUME);
            }
         }
      }
   }

   public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
      return super.onSneakWrenched(state, context);
   }

   protected RenderShape getRenderShape(BlockState state) {
      return state.hasProperty(TRACKS_HIDDEN) && state.getValue(TRACKS_HIDDEN) ? RenderShape.INVISIBLE : super.getRenderShape(state);
   }

   public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return tracks$isHidden(state) ? TRACKS_HIDDEN_SELECTION_SHAPE : super.getShape(state, level, pos, context);
   }

   protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return tracks$isHidden(state) && context != CollisionContext.empty() ? Shapes.empty() : super.getCollisionShape(state, level, pos, context);
   }

   protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
      return tracks$isHidden(state) ? Shapes.empty() : super.getOcclusionShape(state, level, pos);
   }

   @Unique
   private static boolean tracks$isHidden(BlockState state) {
      return state.hasProperty(TRACKS_HIDDEN) && (Boolean)state.getValue(TRACKS_HIDDEN);
   }

   @Unique
   private static void tracks$toggleHiddenMount(BlockState state, Level level, BlockPos pos) {
      level.setBlock(pos, (BlockState)state.cycle(TRACKS_HIDDEN), 2);
      level.playSound(null, pos, (SoundEvent)SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, state.getValue(TRACKS_HIDDEN) ? 0.8F : 1.2F);
   }
}
