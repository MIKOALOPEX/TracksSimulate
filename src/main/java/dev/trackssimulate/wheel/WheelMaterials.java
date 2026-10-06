package dev.trackssimulate.wheel;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.RoseQuartzLampBlock;
import net.minecraft.core.Direction;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Copycat-style appearance and material inventory; wheel dynamics remain configured separately. */
public final class WheelMaterials {
    public static boolean apply(WheelBlockEntity wheel,Player player,ItemStack stack,Direction face) {
        if(!(stack.getItem() instanceof BlockItem)||stack.getItem() instanceof WheelItem||player.isShiftKeyDown())return false;
        var level=wheel.getLevel();
        // Use the installed Create version's whitelist, blacklist and shape acceptance rules directly.
        BlockState accepted=AllBlocks.COPYCAT_PANEL.get().getAcceptedBlockState(level,wheel.getBlockPos(),stack,face);
        if(accepted==null)return false;
        if(level.isClientSide)return true;
        if(!player.isAlive()||player.isSpectator()||!player.mayBuild()||!level.mayInteract(player,wheel.getBlockPos()))return true;
        if(wheel.hasMaterial()) {
            if(wheel.material().is(accepted.getBlock())) {
                BlockState cycled=cycle(wheel.material());
                if(cycled!=wheel.material()) {
                    wheel.setMaterial(cycled,wheel.materialItem());
                    level.playSound(null,wheel.getBlockPos(),SoundEvents.ITEM_FRAME_ADD_ITEM,SoundSource.BLOCKS,.75f,.95f);
                }
            } else player.displayClientMessage(net.minecraft.network.chat.Component.literal("先用扳手右键取回现有材质，再填入新方块"),true);
            return true;
        }
        wheel.setMaterial(accepted,stack);
        if(!player.isCreative())stack.shrink(1);
        level.playSound(null,wheel.getBlockPos(),accepted.getSoundType().getPlaceSound(),SoundSource.BLOCKS,1,.75f);
        return true;
    }
    public static boolean remove(WheelBlockEntity wheel,Player player) {
        if(!wheel.hasMaterial())return false;
        var level=wheel.getLevel();if(level.isClientSide)return true;
        if(!player.isAlive()||player.isSpectator()||!player.mayBuild()||!level.mayInteract(player,wheel.getBlockPos()))return true;
        ItemStack paid=wheel.materialItem();BlockState old=wheel.material();
        wheel.setMaterial(AllBlocks.COPYCAT_BASE.getDefaultState(),ItemStack.EMPTY);
        if(!player.isCreative())player.getInventory().placeItemBackInInventory(paid);
        level.levelEvent(2001,wheel.getBlockPos(),Block.getId(old));
        return true;
    }
    private static BlockState cycle(BlockState state) {
        if(state.hasProperty(TrapDoorBlock.HALF)&&state.getOptionalValue(TrapDoorBlock.OPEN).orElse(false))return state.cycle(TrapDoorBlock.HALF);
        if(state.hasProperty(BlockStateProperties.FACING))return state.cycle(BlockStateProperties.FACING);
        if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))return state.setValue(BlockStateProperties.HORIZONTAL_FACING,state.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise());
        if(state.hasProperty(BlockStateProperties.AXIS))return state.cycle(BlockStateProperties.AXIS);
        if(state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS))return state.cycle(BlockStateProperties.HORIZONTAL_AXIS);
        if(state.hasProperty(BlockStateProperties.LIT))return state.cycle(BlockStateProperties.LIT);
        if(state.hasProperty(RoseQuartzLampBlock.POWERING))return state.cycle(RoseQuartzLampBlock.POWERING);
        return state;
    }
    private WheelMaterials() {}
}
