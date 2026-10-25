// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.blocks.wheel_mount;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.trackssimulate.mixin_interface.WheelMountOffsetAccess;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import dev.ryanhcode.offroad.content.components.TireLike;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import dev.ryanhcode.offroad.index.OffroadPartialModels;
import dev.simulated_team.simulated.util.SimColors;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;

public class AdjustableWheelMountRenderer extends KineticBlockEntityRenderer<WheelMountBlockEntity> {
   public AdjustableWheelMountRenderer(Context context) {
      super(context);
   }

   protected void renderSafe(WheelMountBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
      BlockState state = this.getRenderedBlockState(be);
      RenderType type = this.getRenderType(be, state);
      renderRotatingBuffer(be, this.getRotatedModel(be, state), ms, buffer.getBuffer(type), light);
      FilteringRenderer.renderOnBlockEntity(be, partialTicks, ms, buffer, light, overlay);
      VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());
      Direction direction = ((Direction)be.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING)).getOpposite();
      BlockState blockState = be.getBlockState();
      WheelMountOffsetAccess offsetAccess = (WheelMountOffsetAccess)be;
      boolean hideSuspension = offsetAccess.tracks$isVisualSuspensionHidden();
      SuperByteBuffer diodeLeft = CachedBuffers.partial(OffroadPartialModels.DIODE_LEFT, blockState);
      SuperByteBuffer diodeRight = CachedBuffers.partial(OffroadPartialModels.DIODE_RIGHT, blockState);
      SuperByteBuffer teleOuter = CachedBuffers.partial(OffroadPartialModels.TELE_OUTER, blockState);
      SuperByteBuffer teleInner = CachedBuffers.partial(OffroadPartialModels.TELE_INNER, blockState);
      SuperByteBuffer teleMount = CachedBuffers.partial(OffroadPartialModels.TELE_MOUNT, blockState);
      SuperByteBuffer springTop = CachedBuffers.partial(OffroadPartialModels.SPRING_UPPER, blockState);
      SuperByteBuffer springBottom = CachedBuffers.partial(OffroadPartialModels.SPRING_LOWER, blockState);
      SuperByteBuffer springMiddle = CachedBuffers.partial(OffroadPartialModels.SPRING_MIDDLE, blockState);
      double wheelPivotOffsetHor = 0.625;
      double springWheelPivotOffsetHor = 0.75;
      double springWheelPivotOffsetVer = -0.125;
      double horizontalWheelPosition = 1.375;
      double verticalWheelPosition = -be.getLerpedExtension(partialTicks) + offsetAccess.tracks$getLerpedHeightOffset(partialTicks);
      double lateralWheelPosition = offsetAccess.tracks$getLerpedLateralOffset(partialTicks);
      double longitudinalWheelPosition = offsetAccess.tracks$getLerpedLongitudinalOffset(partialTicks);
      double teleMountHor = 0.0;
      double teleMountVer = -0.375;
      double springMountHor = 0.4375;
      double springMountVer = 0.4375;
      double teleAngle = Math.atan2(verticalWheelPosition - -0.375, 0.75);
      double teleDistance = new Vector2d(verticalWheelPosition - -0.375, 0.75).length();
      double springAngle = Math.atan2(verticalWheelPosition - -0.125 - 0.4375, 0.1875);
      double springDistance = new Vector2d(verticalWheelPosition - -0.125 - 0.4375, 0.1875).length();
      ms.pushPose();
      ((PoseTransformStack)((PoseTransformStack)((PoseTransformStack)TransformStack.of(ms).center()).rotateYDegrees(AngleHelper.horizontalAngle(direction)))
            .rotateXDegrees(AngleHelper.verticalAngle(direction)))
         .uncenter();
      ms.pushPose();
      if (!hideSuspension) {
         ms.pushPose();
         ms.translate(lateralWheelPosition, -0.375, longitudinalWheelPosition);
         ms.translate(0.5, 0.5, 0.5);
         ms.mulPose(Axis.XP.rotation((float)teleAngle));
         ms.translate(-0.5, -0.5, -0.5);
         teleOuter.light(light).renderInto(ms, vb);
         ms.translate(0.0, 0.0, -(teleDistance - 1.0));
         teleInner.light(light).renderInto(ms, vb);
         ms.popPose();
      }

      ms.pushPose();
      ms.translate(lateralWheelPosition, verticalWheelPosition, longitudinalWheelPosition + 1.625 - 1.375);
      ms.translate(0.5, 0.5, 0.5);
      ms.rotateAround(Axis.YP.rotation((float)offsetAccess.tracks$getLerpedYaw(partialTicks)), 0.0F, 0.0F, -1.0F);
      ms.translate(-0.5, -0.5, -0.5);
      if (!hideSuspension) {
         teleMount.light(light).renderInto(ms, vb);
      }

      ms.translate(0.5, 0.5, 0.5);
      ms.translate(0.0, 0.0, -1.625);
      double signMultiplier = (double)(-be.getLerpedAngle(partialTicks))
         * (direction.getAxisDirection() == AxisDirection.POSITIVE ? 1.0 : -1.0)
         * (direction.getAxis() == net.minecraft.core.Direction.Axis.X ? 1.0 : -1.0);
      ms.mulPose(Axis.ZP.rotation((float)signMultiplier));
      ItemStack itemStack = be.getHeldItem();
      TireLike tireLike = (TireLike)itemStack.get(OffroadDataComponents.TIRE);
      if (tireLike != null) {
         Vec3 rotation = tireLike.rotation();
         ms.mulPose(Axis.XP.rotation((float)Math.toRadians(rotation.x)));
         ms.mulPose(Axis.YP.rotation((float)Math.toRadians(rotation.y)));
         ms.mulPose(Axis.ZP.rotation((float)Math.toRadians(rotation.z)));
         if (tireLike.model().isPresent()) {
            ResourceLocation model = (ResourceLocation)tireLike.model().get();
            ms.translate(tireLike.offset().x, tireLike.offset().y, tireLike.offset().z);
            SuperByteBuffer wheel = CachedBuffers.partial(PartialModel.of(model), state);
            ((SuperByteBuffer)wheel.light(light).translate(-0.5F, 0.0F, -0.5F)).renderInto(ms, vb);
         } else {
            ms.translate(tireLike.offset().x, tireLike.offset().y, tireLike.offset().z);
            Minecraft.getInstance().getItemRenderer().renderStatic(itemStack, ItemDisplayContext.NONE, light, overlay, ms, buffer, be.getLevel(), 0);
         }
      }

      ms.popPose();
      ms.popPose();
      if (!hideSuspension) {
         ms.pushPose();
         ms.translate(0.5 + lateralWheelPosition, 0.9375, 0.5 + longitudinalWheelPosition - 0.4375);
         ms.mulPose(Axis.XP.rotation((float)springAngle + (float) (Math.PI / 2)));
         ms.translate(-0.5 - lateralWheelPosition, -0.9375, -0.5 - longitudinalWheelPosition + 0.4375);
         float springExtension = (float)springDistance;
         float springSpan = springExtension - 0.25F;
         springTop.light(light).renderInto(ms, vb);
         ((SuperByteBuffer)((SuperByteBuffer)((SuperByteBuffer)springMiddle.light(light).translate(0.0F, 0.8125F, 0.0F)).scale(1.0F, springSpan / 0.875F, 1.0F))
               .translateBack(0.0F, 0.8125F, 0.0F))
            .renderInto(ms, vb);
         ((SuperByteBuffer)springBottom.light(light).translate(0.0, -((double)springSpan + -0.875), 0.0)).renderInto(ms, vb);
         ms.popPose();
      }

      diodeLeft.light(light).color(SimColors.redstone((float)offsetAccess.tracks$getClientSteeringSignalLeft() / 15.0F)).renderInto(ms, vb);
      diodeRight.light(light).color(SimColors.redstone((float)offsetAccess.tracks$getClientSteeringSignalRight() / 15.0F)).renderInto(ms, vb);
      ms.popPose();
   }

   public int getViewDistance() {
      return 512;
   }

   protected SuperByteBuffer getRotatedModel(WheelMountBlockEntity te, BlockState state) {
      return CachedBuffers.partialFacing(
         AllPartialModels.SHAFT_HALF, te.getBlockState(), ((Direction)te.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING)).getOpposite()
      );
   }
}
