package dev.trackssimulate.track;

import com.simibubi.create.AllItems;
import dev.trackssimulate.wheel.WheelBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Server-side single refund for a loop, including members reloaded after the break. */
public final class BeltLifecycle extends SavedData {
    private final Set<UUID> broken=new HashSet<>();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BeltLifecycle.class);
    private static BeltLifecycle data(ServerLevel level) {
        // Shared across dimensions: a moved assembly keeps its loop UUID.
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BeltLifecycle::new,BeltLifecycle::load),"trackssimulate_broken_belts");
    }
    private static BeltLifecycle load(CompoundTag tag,HolderLookup.Provider registries) {
        BeltLifecycle data=new BeltLifecycle();
        ListTag list=tag.getList("Broken",Tag.TAG_INT_ARRAY);
        for(Tag id:list) data.broken.add(NbtUtils.loadUUID(id));
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        ListTag list=new ListTag();for(UUID id:broken) list.add(NbtUtils.createUUID(id));
        tag.put("Broken",list);return tag;
    }
    public static boolean isBroken(ServerLevel level,UUID id) { return data(level).broken.contains(id); }
    public static void breakLoop(WheelBlockEntity target,Player recipient,String reason) {
        if(!(target.getLevel() instanceof ServerLevel level)||target.loopId()==null) return;
        UUID id=target.loopId();var nodes=List.copyOf(target.nodes());
        BeltLifecycle ledger=data(level);boolean first=ledger.broken.add(id);
        if(first) ledger.setDirty();
        // Claim before unlinking: block updates/removal of another member cannot refund twice.
        for(var pos:nodes) if(level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof WheelBlockEntity member&&id.equals(member.loopId())) member.unlink();
        if(id.equals(target.loopId())) target.unlink();
        if(!first) return;
        ItemStack belt=AllItems.BELT_CONNECTOR.asStack();
        if(recipient!=null) { if(!recipient.addItem(belt)) recipient.drop(belt,false); }
        else Block.popResource(level,target.getBlockPos(),belt);
        LOG.info("belt_removed loop={} reason={} dimension={} pos={} members={} refund={}",id,reason,
            level.dimension().location(),target.getBlockPos(),nodes.size(),recipient==null?"world":"inventory");
    }
}
