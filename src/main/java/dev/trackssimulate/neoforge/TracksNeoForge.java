// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.neoforge;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.config.TracksServerConfig;


import dev.trackssimulate.network.SelectTrackTuningModePayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod("trackssimulate")
public class TracksNeoForge {
   public TracksNeoForge(IEventBus modBus, ModContainer modContainer) {
      modBus.addListener(TracksNeoForge::init);
      modBus.addListener(TracksNeoForge::registerPayloads);
      modContainer.registerConfig(Type.SERVER, TracksServerConfig.SPEC);
      Tracks.init();
      var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
      bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,dev.trackssimulate.assembly.TrackSelection::rightClick);
      bus.addListener(dev.trackssimulate.assembly.TrackSelection::tick);
      bus.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e)->dev.trackssimulate.assembly.TrackSelection.forget(e.getEntity()));
      net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
         dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity.clearPhysicsQueue();
         dev.trackssimulate.diagnostics.TrackDiagnostics.clear();
         dev.trackssimulate.assembly.TrackSelection.clearAll();
         dev.trackssimulate.assembly.TrackAssemblies.clearCaches();
      });
      dev.trackssimulate.index.TracksCreativeTab.register(modBus);
      Tracks.getRegistrate().registerEventListeners(modBus);
   }

   private static void init(FMLCommonSetupEvent event) {
      event.enqueueWork(() -> {
         var mods=net.neoforged.fml.ModList.get();
         Tracks.LOGGER.info("[TS-ENV] build=0.6.0-rebuild.24 baseline=Tracks-1.0.1 create={} sable={} simulated={} offroad={}",
             mods.getModContainerById("create").orElseThrow().getModInfo().getVersion(),
             mods.getModContainerById("sable").orElseThrow().getModInfo().getVersion(),
             mods.getModContainerById("simulated").orElseThrow().getModInfo().getVersion(),
             mods.getModContainerById("offroad").orElseThrow().getModInfo().getVersion());
      });
   }

   private static void registerPayloads(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("trackssimulate").versioned("1.14.0");
      registrar.playToClient(dev.trackssimulate.network.TrackConfigFeedback.TYPE,dev.trackssimulate.network.TrackConfigFeedback.CODEC,
         (p,c)->c.enqueueWork(()->dev.trackssimulate.client.TrackSetupScreen.feedback(p)));
      dev.trackssimulate.assembly.AssemblyPackets.register(registrar);
      registrar.playToServer(dev.trackssimulate.network.AdjustTrackPayload.TYPE, dev.trackssimulate.network.AdjustTrackPayload.STREAM_CODEC, dev.trackssimulate.network.AdjustTrackPayload::handle);
      registrar.playToServer(dev.trackssimulate.network.SetTrackSettingPayload.TYPE, dev.trackssimulate.network.SetTrackSettingPayload.STREAM_CODEC, dev.trackssimulate.network.SetTrackSettingPayload::handle);
      registrar.playToServer(SelectTrackTuningModePayload.TYPE, SelectTrackTuningModePayload.STREAM_CODEC, SelectTrackTuningModePayload::handle);
   }
}
