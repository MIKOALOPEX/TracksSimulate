// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({KineticBlockEntityRenderer.class})
public abstract class KineticBlockEntityRendererMixin {
   @Inject(
      method = {"renderRotatingBuffer(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/createmod/catnip/render/SuperByteBuffer;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;I)V"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private static void tracks$skipHiddenWheelMountShaft(
      KineticBlockEntity be, SuperByteBuffer superByteBuffer, PoseStack poseStack, VertexConsumer vertexConsumer, int light, CallbackInfo ci
   ) {
      if (be instanceof WheelMountBlockEntity wheelMount && tracks$isMountHidden(wheelMount.getBlockState())) {
         ci.cancel();
      }
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
