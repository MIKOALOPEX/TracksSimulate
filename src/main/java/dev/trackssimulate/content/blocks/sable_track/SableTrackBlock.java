// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.blocks.sable_track;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.trackssimulate.index.TracksBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SableTrackBlock extends HorizontalKineticBlock implements IBE<SableTrackBlockEntity> {
   public static final net.minecraft.world.level.block.state.properties.IntegerProperty AXLE_MODE = net.minecraft.world.level.block.state.properties.IntegerProperty.create("axle_mode",0,3);
   // Retained only to read rebuild.19 blockstates without changing their ports.
   public static final BooleanProperty THROUGH_AXLE = BooleanProperty.create("through_axle");
   public static final BooleanProperty HIDDEN = BooleanProperty.create("hidden");
   public static final BooleanProperty SUSPENSION_MODEL = BooleanProperty.create("suspension_model");
   private static final VoxelShape HIDDEN_SELECTION_SHAPE = Block.box(0.0, 14.0, 0.0, 16.0, 16.0, 16.0);
   private final SableTrackRole role;
   private final SableTrackPart builtInPart;
   private static final VoxelShape AXLE_X = Block.box(0, 6, 6, 16, 10, 10);
   private static final VoxelShape AXLE_Z = Block.box(6, 6, 0, 10, 10, 16);

   public SableTrackBlock(Properties properties, SableTrackRole role) {
      this(properties, role, SableTrackPart.NONE);
   }

   public SableTrackBlock(Properties properties, SableTrackPart part) {
      this(properties, part.role(), part);
   }

   private SableTrackBlock(Properties properties, SableTrackRole role, SableTrackPart part) {
      super(properties);
      this.role = role;
      this.builtInPart = part;
      this.registerDefaultState((BlockState)((BlockState)this.defaultBlockState().setValue(HIDDEN, false)).setValue(SUSPENSION_MODEL, false).setValue(THROUGH_AXLE,false).setValue(AXLE_MODE,0));
   }

   public SableTrackRole role() {
      return this.role;
   }

   public SableTrackPart builtInPart() {
      return this.builtInPart;
   }

   public boolean isDirectWheel() {
      return this.builtInPart != SableTrackPart.NONE;
   }

   public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
      if (state.hasBlockEntity() && state.getBlock() != newState.getBlock()) {
         SableTrackBlockEntity be = (SableTrackBlockEntity)level.getBlockEntity(pos);
         if(!level.isClientSide && !isMoving && be!=null)dev.trackssimulate.assembly.TrackAssemblies.remove(be,be.removalRecipient(),"wheel_removed");
         if (!level.isClientSide && !isDirectWheel() && be != null && !be.getHeldItem().isEmpty()) {
            Direction facing = (Direction)state.getValue(HORIZONTAL_FACING);
            BlockPos dropPos = pos.relative(facing);
            Containers.dropItemStack(level, (double)dropPos.getX(), (double)dropPos.getY(), (double)dropPos.getZ(), be.getHeldItem());
         }

         if(!level.isClientSide&&!isMoving&&be!=null)WheelMaterials.dropOnRemoval(be);
         level.removeBlockEntity(pos);
      }

      super.onRemove(state, level, pos, newState, isMoving);
   }

   protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
      super.createBlockStateDefinition(builder);
      builder.add(new Property[]{HIDDEN, SUSPENSION_MODEL, THROUGH_AXLE, AXLE_MODE});
   }

   public BlockState getStateForPlacement(BlockPlaceContext context) {
      Direction preferred = this.getPreferredHorizontalFacing(context);
      boolean crouching = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
      if (preferred != null && !crouching) {
         return (BlockState)this.defaultBlockState().setValue(HORIZONTAL_FACING, preferred.getOpposite());
      } else {
         Direction horizontalDirection = context.getHorizontalDirection();
         return (BlockState)this.defaultBlockState().setValue(HORIZONTAL_FACING, crouching ? horizontalDirection : horizontalDirection.getOpposite());
      }
   }

   public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
      boolean drive=role==SableTrackRole.DRIVE||role==SableTrackRole.MOUNT
         &&world.getBlockEntity(pos) instanceof SableTrackBlockEntity wheel&&wheel.effectiveRole()==SableTrackRole.DRIVE;
      if(!drive)return face==state.getValue(HORIZONTAL_FACING).getOpposite();
      var mode=axleMode(state);var front=state.getValue(HORIZONTAL_FACING);
      return mode.back&&face==front.getOpposite()||mode.front&&face==front;
   }

   public static dev.trackssimulate.config.DriveAxleMode axleMode(BlockState state) {
      return dev.trackssimulate.config.DriveAxleMode.fromSaved(state.getValue(AXLE_MODE),state.getValue(THROUGH_AXLE));
   }

   @Override protected boolean areStatesKineticallyEquivalent(BlockState oldState,BlockState newState) {
      return super.areStatesKineticallyEquivalent(oldState,newState)
         &&axleMode(oldState)==axleMode(newState);
   }
   @Override public InteractionResult onWrenched(BlockState state,UseOnContext context) {
      if(context.getPlayer()!=null&&context.getLevel().getBlockEntity(context.getClickedPos()) instanceof SableTrackBlockEntity wheel
         &&WheelMaterials.remove(wheel,context.getPlayer()))return InteractionResult.SUCCESS;
      return super.onWrenched(state,context);
   }
   protected ItemInteractionResult useItemOn(
      ItemStack heldItem, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult
   ) {
      if(isDirectWheel()&&level.getBlockEntity(pos) instanceof SableTrackBlockEntity wheel&&WheelMaterials.apply(wheel,player,heldItem,hitResult.getDirection()))return ItemInteractionResult.SUCCESS;
      if (com.simibubi.create.AllItems.WRENCH.isIn(heldItem)) {
         return super.useItemOn(heldItem, state, level, pos, player, hand, hitResult);
      } else if (!isDirectWheel() && heldItem.isEmpty() && player.isShiftKeyDown() && hitResult.getDirection() == Direction.UP) {
         if (!level.isClientSide) {
            this.toggleHiddenMount(state, level, pos);
         }

         return ItemInteractionResult.CONSUME;
      } else if (heldItem.isEmpty() && player.isShiftKeyDown() && hitResult.getDirection() == state.getValue(HORIZONTAL_FACING)) {
         if (!level.isClientSide) {
            this.withBlockEntityDo(level, pos, SableTrackBlockEntity::toggleVisualSuspensionHidden);
            level.playSound(null, pos, (SoundEvent)SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 1.1F);
         }

         return ItemInteractionResult.CONSUME;
      } else if (heldItem.getItem() instanceof DyeItem dyeItem) {
         if (!level.isClientSide) {
            this.withBlockEntityDo(level, pos, mount -> mount.applyBeltColorToConnectedTrack(dyeItem.getDyeColor()));
            if (!player.hasInfiniteMaterials()) {
               heldItem.shrink(1);
            }

            level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.PLAYERS, 0.8F, 1.0F);
         }

         return ItemInteractionResult.CONSUME;
      } else if (heldItem.is(Items.WATER_BUCKET)) {
         boolean[] changed = new boolean[]{false};
         if (!level.isClientSide) {
            this.withBlockEntityDo(level, pos, mount -> changed[0] = mount.applyBeltColorToConnectedTrack(null));
            if (changed[0] && !player.hasInfiniteMaterials()) {
               heldItem.shrink(1);
               player.getInventory().placeItemBackInInventory(new ItemStack(Items.BUCKET));
            }

            if (changed[0]) {
               level.playSound(null, pos, SoundEvents.AXOLOTL_SPLASH, SoundSource.PLAYERS, 0.8F, 1.0F);
            }
         }

         return level.isClientSide
            ? ItemInteractionResult.SUCCESS
            : (changed[0] ? ItemInteractionResult.CONSUME : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
      } else if (isDirectWheel()) {
         // Let the held BlockItem place another wheel next to this one, and let
         // the tuning key open its screen. A direct wheel has no removable insert.
         return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
      } else {
         SableTrackPart heldPart = SableTrackPart.fromStack(heldItem);
         if (!heldItem.isEmpty() && heldPart == SableTrackPart.NONE) {
            return super.useItemOn(heldItem, state, level, pos, player, hand, hitResult);
         } else if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
         } else {
            boolean[] handled = new boolean[]{false};
            this.withBlockEntityDo(level, pos, mount -> {
               if (heldItem.isEmpty() || heldPart != SableTrackPart.NONE) {
                  ItemStack previous = mount.getHeldItem().copy();
                  mount.setHeldItem(heldItem.isEmpty() ? ItemStack.EMPTY : heldItem.copyWithCount(1));
                  if (!heldItem.isEmpty() && !player.hasInfiniteMaterials()) {
                     heldItem.shrink(1);
                  }

                  if (!previous.isEmpty()) {
                     player.getInventory().placeItemBackInInventory(previous);
                  }

                  float pitch = 0.8F + level.random.nextFloat() * 0.4F;
                  level.playSound(null, pos, heldItem.isEmpty() ? SoundEvents.ITEM_PICKUP : SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.PLAYERS, 0.75F, pitch);
                  handled[0] = true;
               }
            });
            return handled[0] ? ItemInteractionResult.CONSUME : super.useItemOn(heldItem, state, level, pos, player, hand, hitResult);
         }
      }
   }

   public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
      var be=context.getLevel().getBlockEntity(context.getClickedPos());
      if(be instanceof SableTrackBlockEntity wheel)wheel.removalRecipient(context.getPlayer());
      // Create still owns the break event, permission cancellation and wheel
      // refund. Our actual onRemove claims the belt only after that succeeds.
      try {return super.onSneakWrenched(state, context);}
      finally {if(be instanceof SableTrackBlockEntity wheel)wheel.removalRecipient(null);}
   }

   @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player) {
      if(level.getBlockEntity(pos) instanceof SableTrackBlockEntity wheel)wheel.removalRecipient(player);
      return super.playerWillDestroy(level,pos,state,player);
   }

   private void toggleHiddenMount(BlockState state, Level level, BlockPos pos) {
      level.setBlock(pos, (BlockState)state.cycle(HIDDEN), 2);
      level.playSound(null, pos, (SoundEvent)SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, state.getValue(HIDDEN) ? 0.8F : 1.2F);
   }

   public Axis getRotationAxis(BlockState state) {
      return ((Direction)state.getValue(HORIZONTAL_FACING)).getAxis();
   }

   public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return state.getValue(HIDDEN) ? HIDDEN_SELECTION_SHAPE : Shapes.block();
   }

   protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      // Only the physical axle is a fixed collider. Suspension support remains
      // in the existing terrain sampler; there is no full-cube chassis collider.
      if (isDirectWheel()) return getRotationAxis(state) == Axis.X ? AXLE_X : AXLE_Z;
      return state.getValue(HIDDEN) && context != CollisionContext.empty() ? Shapes.empty() : Shapes.block();
   }

   protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
      if (isDirectWheel()) return Shapes.empty();
      return state.getValue(HIDDEN) ? Shapes.empty() : Shapes.block();
   }

   protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      if (isDirectWheel()) return getCollisionShape(state, level, pos, context);
      return state.getValue(HIDDEN) ? Shapes.empty() : super.getVisualShape(state, level, pos, context);
   }

   protected boolean useShapeForLightOcclusion(BlockState state) {
      if (isDirectWheel()) return false;
      return !(Boolean)state.getValue(HIDDEN) && super.useShapeForLightOcclusion(state);
   }

   protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
      if (isDirectWheel()) return true;
      return (Boolean)state.getValue(HIDDEN) || super.propagatesSkylightDown(state, level, pos);
   }

   protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
      if (isDirectWheel()) return 1.0F;
      return state.getValue(HIDDEN) ? 1.0F : super.getShadeBrightness(state, level, pos);
   }

   protected RenderShape getRenderShape(BlockState state) {
      return state.getValue(HIDDEN) ? RenderShape.INVISIBLE : super.getRenderShape(state);
   }

   public Class<SableTrackBlockEntity> getBlockEntityClass() {
      return SableTrackBlockEntity.class;
   }

   public BlockEntityType<? extends SableTrackBlockEntity> getBlockEntityType() {
      return (BlockEntityType<? extends SableTrackBlockEntity>)TracksBlockEntityTypes.SABLE_TRACK.get();
   }
}
