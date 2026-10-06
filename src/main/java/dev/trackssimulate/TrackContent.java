package dev.trackssimulate;

import dev.trackssimulate.wheel.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class TrackContent {
    public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(TracksSimulate.MOD_ID);
    public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(TracksSimulate.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,TracksSimulate.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,TracksSimulate.MOD_ID);
    public static final DeferredBlock<WheelBlock> DRIVE=wheel("drive_wheel",WheelKind.DRIVE);
    public static final DeferredBlock<WheelBlock> ROAD=wheel("road_wheel",WheelKind.ROAD);
    public static final DeferredBlock<WheelBlock> RETURN=wheel("return_wheel",WheelKind.RETURN);
    public static final DeferredItem<Item> DEBUG_STICK=ITEMS.register("track_debug_stick",()->new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<WheelItem> DRIVE_ITEM=ITEMS.register("drive_wheel",()->new WheelItem(DRIVE.get(),new Item.Properties()));
    public static final DeferredItem<WheelItem> ROAD_ITEM=ITEMS.register("road_wheel",()->new WheelItem(ROAD.get(),new Item.Properties()));
    public static final DeferredItem<WheelItem> RETURN_ITEM=ITEMS.register("return_wheel",()->new WheelItem(RETURN.get(),new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<WheelBlockEntity>> WHEEL_ENTITY=ENTITIES.register("wheel",()->BlockEntityType.Builder.of(WheelBlockEntity::new,DRIVE.get(),ROAD.get(),RETURN.get()).build(null));
    private static DeferredBlock<WheelBlock> wheel(String name,WheelKind kind) {
        return BLOCKS.register(name,()->new WheelBlock(kind,BlockBehaviour.Properties.of().strength(2).noOcclusion()));
    }
    static {
        TABS.register("wheels",()->CreativeModeTab.builder().title(Component.translatable("itemGroup.trackssimulate"))
            .icon(()->new ItemStack(DRIVE_ITEM.get())).displayItems((p,out)->{
                out.accept(DRIVE_ITEM.get());out.accept(ROAD_ITEM.get());out.accept(RETURN_ITEM.get());out.accept(DEBUG_STICK.get());
            }).build());
    }
    public static void register(IEventBus bus) { BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);TABS.register(bus); }
    private TrackContent() {}
}
