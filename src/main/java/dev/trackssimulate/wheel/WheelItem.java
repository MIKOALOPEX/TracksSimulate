package dev.trackssimulate.wheel;

import net.minecraft.world.item.BlockItem;

/** Normal block placement; optional shaft conversion is handled by TrackInteractions. */
public final class WheelItem extends BlockItem {
    public WheelItem(WheelBlock block,Properties properties) { super(block,properties); }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,net.minecraft.world.item.Item.TooltipContext context,
        java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag) {
        super.appendHoverText(stack,context,lines,flag);
        lines.add(net.minecraft.network.chat.Component.literal("手持方块右键填充材质；同类方块再次点击调整朝向").withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(net.minecraft.network.chat.Component.literal("调试棒右键打开设置；扳手右键取回材质，潜行拆轮").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
