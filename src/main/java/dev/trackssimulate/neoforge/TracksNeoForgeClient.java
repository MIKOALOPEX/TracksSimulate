// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.neoforge;

import com.mojang.blaze3d.platform.InputConstants.Type;
import dev.trackssimulate.TracksClient;

import dev.trackssimulate.client.TrackSetupScreen;
import dev.trackssimulate.content.blocks.sable_track.SableTrackRenderer;
import dev.trackssimulate.content.blocks.wheel_mount.AdjustableWheelMountRenderer;
import dev.trackssimulate.content.items.SuspensionKeyItem;
import dev.trackssimulate.index.TracksBlockEntityTypes;
import dev.trackssimulate.index.TracksItems;

import dev.ryanhcode.offroad.index.OffroadBlockEntityTypes;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(
   value = "trackssimulate",
   dist = {Dist.CLIENT}
)
public class TracksNeoForgeClient {
   private static final KeyMapping OPEN_TUNING = new KeyMapping("key.trackssimulate.open_tuning", Type.KEYSYM, 74, "key.categories.tracks");

   public TracksNeoForgeClient(IEventBus modBus) {
      modBus.addListener(TracksNeoForgeClient::clientSetup);
      modBus.addListener(TracksNeoForgeClient::registerKeys);
      NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, TracksNeoForgeClient::clientTick);
      NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e)->dev.trackssimulate.assembly.TrackAssemblies.clearClientCaches());
      modBus.addListener((net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent e) ->
         e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> dev.trackssimulate.client.RebuiltWheelMesh.clear()));
      TracksClient.init();
   }

   private static void clientSetup(FMLClientSetupEvent event) {
      event.enqueueWork(() -> {
         BlockEntityRenderers.register((BlockEntityType)OffroadBlockEntityTypes.WHEEL_MOUNT.get(), AdjustableWheelMountRenderer::new);
         BlockEntityRenderers.register((BlockEntityType)TracksBlockEntityTypes.SABLE_TRACK.get(), SableTrackRenderer::new);
      });
   }

   private static void registerKeys(RegisterKeyMappingsEvent event) {
      event.register(OPEN_TUNING);
   }

   private static void clientTick(Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      TracksClient.holdingSuspensionKey = minecraft.player != null
         && (
            minecraft.player.getMainHandItem().is(TracksItems.SUSPENSION_KEY.asItem())
               || minecraft.player.getOffhandItem().is(TracksItems.SUSPENSION_KEY.asItem())
         );
      if (minecraft.player != null && TracksClient.holdingSuspensionKey) {
         ItemStack key = minecraft.player.getMainHandItem().is(TracksItems.SUSPENSION_KEY.asItem())
            ? minecraft.player.getMainHandItem()
            : minecraft.player.getOffhandItem();
         SuspensionKeyItem.TuningMode mode = SuspensionKeyItem.getMode(key);
         TracksClient.holdingSuspensionKeyInPositionMode = mode == SuspensionKeyItem.TuningMode.POSITION;
         TracksClient.holdingSuspensionKeyInResetMode = mode == SuspensionKeyItem.TuningMode.RESET;
      } else {
         TracksClient.holdingSuspensionKeyInPositionMode = false;
         TracksClient.holdingSuspensionKeyInResetMode = false;
      }

      while (OPEN_TUNING.consumeClick()) {
         if (minecraft.player != null) {
            openTuningScreen();
         }
      }
   }

   public static void openTuningScreen(net.minecraft.core.BlockPos pos) {
      Minecraft.getInstance().setScreen(new TrackSetupScreen(pos));
   }

   public static void openTuningScreen() {
      var mc = Minecraft.getInstance();
      if (mc.level != null && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
          && mc.level.getBlockEntity(hit.getBlockPos()) instanceof dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity)
         mc.setScreen(new TrackSetupScreen(hit.getBlockPos()));
   }
}
