package dev.trackssimulate.physics;

import dev.ryanhcode.sable.Sable;
import dev.trackssimulate.wheel.WheelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

public final class BeltNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("6").playToServer(Apply.TYPE,Apply.CODEC,(p,c)->{
            if(!(c.player() instanceof ServerPlayer player)) return;
            try { apply(player,p);PacketDistributor.sendToPlayer(player,new SuspensionNetwork.Reply(p.request,true,"已保存整条履带的物理参数")); }
            catch(IllegalArgumentException ex) { PacketDistributor.sendToPlayer(player,new SuspensionNetwork.Reply(p.request,false,ex.getMessage())); }
        });
    }
    private static void apply(ServerPlayer player,Apply p) {
        var level=player.level();
        if(!level.dimension().location().equals(p.dimension)||!player.isAlive()||player.isSpectator()||!player.mayBuild()||!level.hasChunkAt(p.pos)
            ||!java.util.stream.Stream.of(player.getMainHandItem(),player.getOffhandItem()).anyMatch(s->s.is(dev.trackssimulate.TrackContent.DEBUG_STICK.get()))
            ||!(level.getBlockEntity(p.pos) instanceof WheelBlockEntity wheel)||wheel.root()==null)
            throw new IllegalArgumentException("请保持手持履带调试棒，目标必须属于已连接履带");
        var host=Sable.HELPER.getContaining(wheel);Vec3 centre=Vec3.atCenterOf(p.pos);
        if(host!=null) centre=host.logicalPose().transformPosition(centre);
        if(player.getEyePosition().distanceToSqr(centre)>64) throw new IllegalArgumentException("离安装轴太远，参数未保存");
        double[] v=p.values;var settings=BeltSettings.from(v);
        List<WheelBlockEntity> members=new ArrayList<>();
        for(BlockPos pos:wheel.nodes()) {
            if(!level.hasChunkAt(pos)||!level.mayInteract(player,pos)||!(level.getBlockEntity(pos) instanceof WheelBlockEntity member)
                ||!Objects.equals(wheel.loopId(),member.loopId())||Sable.HELPER.getContaining(member)!=host)
                throw new IllegalArgumentException("需要整环完整加载、可编辑并属于同一车体，未修改任何参数");
            members.add(member);
        }
        for(var member:members) member.configureBelt(settings);
    }
    public record Apply(UUID request,BlockPos pos,ResourceLocation dimension,double[] values) implements CustomPacketPayload {
        public Apply { if(values.length!=BeltSettings.KEYS.length) throw new IllegalArgumentException("Belt parameter count");values=values.clone(); }
        public static final Type<Apply> TYPE=new Type<>(ResourceLocation.parse("trackssimulate:belt_physics"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Apply> CODEC=new StreamCodec<>() {
            public Apply decode(RegistryFriendlyByteBuf b) { UUID id=b.readUUID();BlockPos pos=b.readBlockPos();var dim=b.readResourceLocation();double[] v=new double[BeltSettings.KEYS.length];for(int i=0;i<v.length;i++) v[i]=b.readDouble();return new Apply(id,pos,dim,v); }
            public void encode(RegistryFriendlyByteBuf b,Apply p) { b.writeUUID(p.request);b.writeBlockPos(p.pos);b.writeResourceLocation(p.dimension);for(double v:p.values)b.writeDouble(v); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private BeltNetwork() {}
}
