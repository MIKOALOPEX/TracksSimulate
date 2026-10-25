// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountRenderer;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({WheelMountRenderer.class})
public abstract class WheelMountRendererMixin {
   @WrapOperation(
      method = {"renderSafe"},
      at = {@At(
         value = "INVOKE",
         target = "Ldev/ryanhcode/offroad/content/blocks/wheel_mount/WheelMountRenderer;renderRotatingBuffer(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/createmod/catnip/render/SuperByteBuffer;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;I)V"
      )},
      require = 0
   )
   private void tracks$skipHiddenMountShaft(
      KineticBlockEntity be, SuperByteBuffer superByteBuffer, PoseStack poseStack, VertexConsumer vertexConsumer, int light, Operation<Void> original
   ) {
      if (be instanceof WheelMountBlockEntity wheelMount && tracks$isMountHidden(wheelMount.getBlockState())) {
         return;
      }

      original.call(new Object[]{be, superByteBuffer, poseStack, vertexConsumer, light});
   }

   private static boolean tracks$isMountHidden(BlockState state) {
      for (Property<?> property : state.getProperties()) {
         if (property instanceof BooleanProperty booleanProperty && "tracks_hidden".equals(booleanProperty.getName())) {
            return (Boolean)state.getValue(booleanProperty);
         }
      }

      return false;
   }
}
