package dev.trackssimulate.index;

import dev.trackssimulate.Tracks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TracksCreativeTab {
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Tracks.MOD_ID);
    static {
        TABS.register("tracks", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.trackssimulate"))
            .icon(() -> TracksBlocks.SMALL_TRACK_DRIVE_WHEEL.asStack())
            .displayItems((parameters, output) -> {
                output.accept(TracksBlocks.SUSPENSION_TRACK);
                output.accept(TracksBlocks.TRACK_DRIVE_WHEEL);
                output.accept(TracksBlocks.TRACK_GUIDE_WHEEL);
                output.accept(TracksItems.SUSPENSION_KEY);
            }).build());
    }
    public static void register(IEventBus bus) { TABS.register(bus); }
}
