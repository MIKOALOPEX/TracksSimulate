package dev.trackssimulate;

import dev.trackssimulate.track.TrackInteractions;
import dev.trackssimulate.track.TrackSelections;
import dev.trackssimulate.physics.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(TracksSimulate.MOD_ID)
public final class TracksSimulate {
    public static final String MOD_ID = "trackssimulate";

    public TracksSimulate(IEventBus modBus) {
        TrackContent.register(modBus);
        modBus.addListener(TrackSelections::register);
        modBus.addListener(SuspensionNetwork::register);
        modBus.addListener(BeltNetwork::register);
        NeoForge.EVENT_BUS.addListener(TrackInteractions::rightClick);
        NeoForge.EVENT_BUS.addListener(TrackSelections::tick);
        NeoForge.EVENT_BUS.addListener(TrackSelections::logout);
        NeoForge.EVENT_BUS.addListener(TrackSelections::stopped);
        NeoForge.EVENT_BUS.addListener(RoadWheelPhysics::stopped);
        NeoForge.EVENT_BUS.addListener(RoadWheelPhysics::beforeServerTick);
        NeoForge.EVENT_BUS.addListener(RoadWheelPhysics::unloaded);
        NeoForge.EVENT_BUS.addListener(BeltPhysics::stopped);
        NeoForge.EVENT_BUS.addListener(BeltPhysics::unloaded);
    }
}
