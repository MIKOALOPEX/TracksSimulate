package dev.trackssimulate.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.trackssimulate.*;
import dev.trackssimulate.wheel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.*;

@EventBusSubscriber(modid=TracksSimulate.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class TrackClient {
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TrackContent.WHEEL_ENTITY.get(),WheelRenderer::new);
    }
    @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener)manager->WheelMeshes.clear());
    }
    @SubscribeEvent public static void items(RegisterClientExtensionsEvent event) {
        var mc=Minecraft.getInstance();
        var renderer=new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(),mc.getEntityModels()) {
            @Override public void renderByItem(ItemStack item,ItemDisplayContext display,PoseStack stack,MultiBufferSource buffers,int light,int overlay) {
                if(!(item.getItem() instanceof WheelItem wheelItem)) return;
                stack.pushPose();stack.translate(.5,.5,.5);
                WheelMeshes.draw(((WheelBlock)wheelItem.getBlock()).kind,com.simibubi.create.AllBlocks.COPYCAT_BASE.getDefaultState(),null,null,stack,buffers,light,overlay);
                stack.popPose();
            }
        };
        event.registerItem(new IClientItemExtensions() {
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() { return renderer; }
        },TrackContent.DRIVE_ITEM.get(),TrackContent.ROAD_ITEM.get(),TrackContent.RETURN_ITEM.get());
    }
}
