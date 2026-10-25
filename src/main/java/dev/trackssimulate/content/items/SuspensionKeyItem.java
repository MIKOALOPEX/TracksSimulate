// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.items;

import dev.trackssimulate.content.blocks.sable_track.SableTrackBlock;
import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.trackssimulate.mixin_interface.WheelMountOffsetAccess;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlock;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class SuspensionKeyItem extends Item {
   private static final String MODE_TAG = "TracksSuspensionKeyMode";
   public static final SoundEvent CRAFTER_CLICK = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("create", "crafter_click"));

   public SuspensionKeyItem(Properties properties) {
      super(properties);
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
      ItemStack stack = player.getItemInHand(usedHand);
      SuspensionKeyItem.TuningMode mode = cycleMode(stack, player.isShiftKeyDown() ? -1 : 1);
      if (!level.isClientSide) {
         level.playSound(null, player.blockPosition(), CRAFTER_CLICK, SoundSource.PLAYERS, 0.45F, 1.0F);
         player.displayClientMessage(Component.translatable("item.trackssimulate.suspension_key.mode", new Object[]{mode.title()}).withStyle(ChatFormatting.GOLD), true);
      }

      return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
   }

   public InteractionResult useOn(UseOnContext context) {
      if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof SableTrackBlockEntity wheel) {
         if (context.getLevel().isClientSide)
            dev.trackssimulate.neoforge.TracksNeoForgeClient.openTuningScreen(context.getClickedPos());
         else if(context.getPlayer()!=null&&wheel.beltId()!=null) {
            var player=context.getPlayer();var eye=player.getEyePosition();
            var query=dev.trackssimulate.assembly.AssemblyGeometry.intersectWorld(wheel,eye,eye.add(player.getLookAngle().scale(8)),4096);
            if(query!=null)dev.trackssimulate.Tracks.LOGGER.info("[TS-GEOMETRY] event=debug_open belt={} revision={} valid={} length={} spans={} queryComplete={} tested={} hit={}",
               query.snapshot().assembly(),query.snapshot().revision(),query.snapshot().surface().valid(),query.snapshot().surface().length(),
               query.snapshot().surface().spans().size(),query.query().complete(),query.query().testedSpans(),query.query().hit());
         }
         return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
      }
      SuspensionKeyItem.TuningMode mode = getMode(context.getItemInHand());
      if (mode == SuspensionKeyItem.TuningMode.RESET) {
         return this.resetOn(context);
      } else {
         Level level = context.getLevel();
         BlockPos pos = context.getClickedPos();
         BlockEntity blockEntity = level.getBlockEntity(pos);
         Player player = context.getPlayer();
         int direction = player != null && player.isShiftKeyDown() ? -1 : 1;
         Direction clickedFace = context.getClickedFace();
         if (mode != SuspensionKeyItem.TuningMode.POSITION) {
            if (blockEntity instanceof WheelMountOffsetAccess wheelMount) {
               if (!mode.supportsWheel()) {
                  if (!level.isClientSide) {
                     this.feedback(level, pos, player, mode, 0.0, false);
                  }

                  return InteractionResult.sidedSuccess(level.isClientSide);
               } else {
                  if (!level.isClientSide) {
                     double value = wheelMount.tracks$adjustTuning(mode.key, direction);
                     this.feedback(level, pos, player, mode, value, true);
                  }

                  return InteractionResult.sidedSuccess(level.isClientSide);
               }
            } else if (blockEntity instanceof SableTrackBlockEntity track) {
               if (!level.isClientSide) {
                  double value = track.adjustTuning(mode.key, direction);
                  this.feedback(level, pos, player, mode, value, true);
               }

               return InteractionResult.sidedSuccess(level.isClientSide);
            } else {
               return InteractionResult.PASS;
            }
         } else if (blockEntity instanceof WheelMountOffsetAccess wheelMountx) {
            Direction facing = (Direction)level.getBlockState(pos).getValue(WheelMountBlock.HORIZONTAL_FACING);
            String axisName;
            double value;
            if (clickedFace == facing) {
               axisName = "height";
               value = wheelMountx.tracks$adjustHeightOffset(direction);
            } else if (clickedFace.getAxis() == Axis.Y) {
               axisName = "forward/back";
               value = wheelMountx.tracks$adjustLongitudinalOffset(direction);
            } else {
               axisName = "left/right";
               value = wheelMountx.tracks$adjustLateralOffset(direction);
            }

            this.positionFeedback(level, pos, player, axisName, value);
            return InteractionResult.sidedSuccess(level.isClientSide);
         } else if (blockEntity instanceof SableTrackBlockEntity track) {
            Direction facing = (Direction)track.getBlockState().getValue(SableTrackBlock.HORIZONTAL_FACING);
            String axisName;
            double value;
            if (clickedFace.getAxis() == Axis.Y) {
               axisName = "forward/back";
               value = track.adjustLongitudinalOffset(direction);
            } else if (clickedFace.getAxis() == facing.getClockWise().getAxis()) {
               axisName = "left/right";
               value = track.adjustLateralOffset(direction);
            } else {
               axisName = "height";
               value = track.adjustHeightOffset(direction);
            }

            this.positionFeedback(level, pos, player, axisName, value);
            return InteractionResult.sidedSuccess(level.isClientSide);
         } else {
            return InteractionResult.PASS;
         }
      }
   }

   private InteractionResult resetOn(UseOnContext context) {
      Level level = context.getLevel();
      BlockPos pos = context.getClickedPos();
      BlockEntity blockEntity = level.getBlockEntity(pos);
      Player player = context.getPlayer();
      if (blockEntity instanceof WheelMountOffsetAccess wheelMount) {
         if (!level.isClientSide) {
            wheelMount.tracks$resetTuning();
            this.feedback(level, pos, player, SuspensionKeyItem.TuningMode.RESET, 0.0, true);
         }

         return InteractionResult.sidedSuccess(level.isClientSide);
      } else if (blockEntity instanceof SableTrackBlockEntity track) {
         if (!level.isClientSide) {
            track.resetTuningToConnectedTrack();
            this.feedback(level, pos, player, SuspensionKeyItem.TuningMode.RESET, 0.0, true);
         }

         return InteractionResult.sidedSuccess(level.isClientSide);
      } else {
         return InteractionResult.PASS;
      }
   }

   private void feedback(Level level, BlockPos pos, Player player, SuspensionKeyItem.TuningMode mode, double value, boolean applied) {
      if (!level.isClientSide) {
         level.playSound(null, pos, CRAFTER_CLICK, SoundSource.BLOCKS, 0.45F, 0.9F + level.random.nextFloat() * 0.2F);
         if (player != null) {
            String formattedValue = mode == SuspensionKeyItem.TuningMode.RESET
               ? "-"
               : String.format(Locale.ROOT, mode == SuspensionKeyItem.TuningMode.STRENGTH ? "%.0f" : "%.2fx", value);
            Component message = applied
               ? Component.translatable("item.trackssimulate.suspension_key.tuned", new Object[]{mode.title(), formattedValue})
               : Component.translatable("item.trackssimulate.suspension_key.unsupported", new Object[]{mode.title()});
            player.displayClientMessage(message.copy().withStyle(ChatFormatting.GRAY), true);
         }
      }
   }

   public static SuspensionKeyItem.TuningMode cycleMode(ItemStack stack, int direction) {
      SuspensionKeyItem.TuningMode[] values = SuspensionKeyItem.TuningMode.values();
      int next = Math.floorMod(getMode(stack).ordinal() + direction, values.length);
      CompoundTag tag = ((CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)).copyTag();
      tag.putInt("TracksSuspensionKeyMode", next);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return values[next];
   }

   private void positionFeedback(Level level, BlockPos pos, Player player, String axisName, double value) {
      if (!level.isClientSide) {
         level.playSound(null, pos, CRAFTER_CLICK, SoundSource.BLOCKS, 0.45F, 0.9F + level.random.nextFloat() * 0.2F);
         if (player != null) {
            player.displayClientMessage(
               Component.literal("Position " + axisName + ": " + String.format(Locale.ROOT, "%.3f", value)).withStyle(ChatFormatting.GRAY), true
            );
         }
      }
   }

   public static SuspensionKeyItem.TuningMode getMode(ItemStack stack) {
      CompoundTag tag = ((CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)).copyTag();
      int index = tag.getInt("TracksSuspensionKeyMode");
      SuspensionKeyItem.TuningMode[] values = SuspensionKeyItem.TuningMode.values();
      return values[Math.floorMod(index, values.length)];
   }

   public static enum TuningMode {
      STRENGTH("strength", "item.trackssimulate.suspension_key.mode.strength", false),
      SPRING("spring", "item.trackssimulate.suspension_key.mode.spring", true),
      DAMPING("damping", "item.trackssimulate.suspension_key.mode.damping", false),
      BUMP_CLEARANCE("bump_clearance", "item.trackssimulate.suspension_key.mode.bump_clearance", false),
      BUMP_FORCE("bump_force", "item.trackssimulate.suspension_key.mode.bump_force", false),
      MAX_IMPULSE("max_impulse", "item.trackssimulate.suspension_key.mode.max_impulse", false),
      DRIVE("drive", "item.trackssimulate.suspension_key.mode.drive", true),
      GRIP("grip", "item.trackssimulate.suspension_key.mode.grip", true),
      RESET("reset", "item.trackssimulate.suspension_key.mode.reset", true),
      POSITION("position", "item.trackssimulate.suspension_key.mode.position", true);

      public final String key;
      private final String translationKey;
      private final boolean supportsWheel;

      private TuningMode(final String key, final String translationKey, final boolean supportsWheel) {
         this.key = key;
         this.translationKey = translationKey;
         this.supportsWheel = supportsWheel;
      }

      public Component title() {
         return Component.translatable(this.translationKey);
      }

      public boolean supportsWheel() {
         return this.supportsWheel;
      }
   }
}
