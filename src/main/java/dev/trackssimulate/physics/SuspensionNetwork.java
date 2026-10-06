package dev.trackssimulate.physics;

import dev.ryanhcode.sable.Sable;
import dev.trackssimulate.wheel.*;
import dev.trackssimulate.track.TrackPath;
import dev.trackssimulate.track.TrackLayoutValidation;
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

public final class SuspensionNetwork {
    public static Reply clientReply;
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("4");
        registrar.playToClient(Reply.TYPE,Reply.CODEC,(p,c)->clientReply=p);
        registrar.playToServer(Apply.TYPE,Apply.CODEC,(p,context)->{
            if(!(context.player() instanceof ServerPlayer player)) return;
            try { reply(player,p,true,apply(player,p)); }
            catch(IllegalArgumentException ex) { reply(player,p,false,ex.getMessage()); }
        });
    }
    private static String apply(ServerPlayer player,Apply p) {
        var level=player.level();
        if(!level.dimension().location().equals(p.dimension)||!player.isAlive()||player.isSpectator()||!player.mayBuild()
            ||!level.hasChunkAt(p.pos)||!level.mayInteract(player,p.pos)) throw new IllegalArgumentException("当前无法编辑该轮，请检查距离和权限");
        boolean wrench=java.util.stream.Stream.of(player.getMainHandItem(),player.getOffhandItem())
            .anyMatch(s->s.is(dev.trackssimulate.TrackContent.DEBUG_STICK.get()));
        if(!wrench||!(level.getBlockEntity(p.pos) instanceof WheelBlockEntity wheel))
            throw new IllegalArgumentException("请保持手持履带调试棒，目标必须是本模组轮子");
        Vec3 centre=Vec3.atCenterOf(p.pos);var host=Sable.HELPER.getContaining(wheel);
        if(host!=null) centre=host.logicalPose().transformPosition(centre);
        if(player.getEyePosition().distanceToSqr(centre)>64) throw new IllegalArgumentException("离安装轴太远，参数未保存");
        double[] v=p.values;
        var settings=new SuspensionSettings(v[0],v[1],v[2],v[3],v[4],v[5]);
        var geometry=new WheelGeometry(v[6],v[7],v[8],v[9],v[10]);
        List<WheelBlockEntity> loop=new ArrayList<>();
        if(wheel.loopId()!=null) {
            for(BlockPos pos:wheel.nodes()) {
                if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof WheelBlockEntity member)
                    ||!wheel.loopId().equals(member.loopId())||member.axis()!=wheel.axis()||Sable.HELPER.getContaining(member)!=host)
                    throw new IllegalArgumentException("需要整条履带完整加载且属于同一车体，参数未保存");
                loop.add(member);
            }
        } else if(p.wholeLoop) throw new IllegalArgumentException("当前轮尚未连接履带，无法同步整组");
        Set<WheelBlockEntity> targets=new LinkedHashSet<>();targets.add(wheel);
        if(p.wholeLoop && wheel.kind()==WheelKind.ROAD) for(var member:loop) if(member.kind()==WheelKind.ROAD) targets.add(member);
        Map<WheelBlockEntity,WheelGeometry> shapes=new LinkedHashMap<>();
        if(loop.isEmpty()) loop.add(wheel);
        for(var member:loop) shapes.put(member,member.geometry().applyGroupDimensions(geometry,targets.contains(member),member==wheel||p.radiusAll));
        for(var target:shapes.keySet()) if(!level.mayInteract(player,target.getBlockPos()))
            throw new IllegalArgumentException("同环存在不可编辑的轮子，未修改任何轮子");
        if(host instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body) for(var target:targets) {
            var offset=shapes.get(target).offset(target.axis().ordinal());var pos=target.getBlockPos();
            if(!body.getPlot().contains(new org.joml.Vector3d(pos.getX()+.5+offset.x(),pos.getY()+.5+offset.y(),pos.getZ()+.5+offset.z())))
                throw new IllegalArgumentException("偏移后的悬挂基准超出车体坐标分区，未修改任何轮子");
        }
        boolean crossing=false;
        if(wheel.loopId()!=null) {
            WheelBlockEntity root=wheel.root();
            if(root==null||root.path()==null) throw new IllegalArgumentException("履带路径当前不可用，请先修复连接或拆带后调整");
            List<TrackLayoutValidation.Wheel> planned=new ArrayList<>();
            for(var member:loop) {
                WheelGeometry g=shapes.get(member);
                double axial=WheelBlockEntity.axial(member.getBlockPos(),member.axis())+.5+g.axial();
                var point=WheelBlockEntity.planar(member.getBlockPos().subtract(root.getBlockPos()),member.axis());
                double extension=targets.contains(member)?member.configuredExtension(settings,p.enabled):member.renderExtension(1);
                boolean road=member.kind()==WheelKind.ROAD&&member.axis()!=net.minecraft.core.Direction.Axis.Y;
                planned.add(new TrackLayoutValidation.Wheel(axial,new TrackPath.Wheel(new TrackPath.Point(point.x()+g.forward(),point.y()+g.vertical()-(member.axis()==net.minecraft.core.Direction.Axis.Y?0:extension)),member.kind().radius*g.radiusScale()+.025,0,road?3:0,road,member.kind()==WheelKind.DRIVE)));
            }
            // Validate the complete proposed edit before changing any member. Preserve committed wrap topology.
            crossing=TrackLayoutValidation.validate(planned,root.path().wheelDirections()).crossing();
        }
        for(var entry:shapes.entrySet()) {
            var target=entry.getKey();boolean local=targets.contains(target);
            target.configureWheel(local?settings:target.suspensionSettings(),local?p.enabled:target.getBlockState().getValue(WheelBlock.SUSPENSION),
                entry.getValue(),local?p.visible:target.wheelVisible(),local?p.strut:target.strutVisible());
        }
        if(wheel.kind()==WheelKind.DRIVE&&(wheel.getBlockState().getValue(WheelBlock.FULL_AXLE)!=p.fullAxle||wheel.getBlockState().getValue(WheelBlock.POSITIVE)!=p.positive)) com.simibubi.create.content.kinetics.base.KineticBlockEntity.switchToBlockState(level,p.pos,wheel.getBlockState().setValue(WheelBlock.FULL_AXLE,p.fullAxle).setValue(WheelBlock.POSITIVE,p.positive));
        if(crossing) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("注意：修改后的履带路径存在交叉，已保留原包覆方向。").withStyle(net.minecraft.ChatFormatting.RED));
        return "已保存；轮宽同步 "+shapes.size()+" 轮，轮径"+(p.radiusAll?"同步整组":"仅当前轮")+(crossing?"；履带有交叉":"");
    }
    private static void reply(ServerPlayer player,Apply p,boolean success,String message) {
        PacketDistributor.sendToPlayer(player,new Reply(p.request,success,message));
    }
    public record Apply(UUID request,BlockPos pos,ResourceLocation dimension,boolean enabled,boolean visible,boolean strut,boolean wholeLoop,boolean radiusAll,boolean fullAxle,boolean positive,double[] values) implements CustomPacketPayload {
        public Apply { if(values.length!=11) throw new IllegalArgumentException("Wheel parameter count");values=values.clone(); }
        public static final Type<Apply> TYPE=new Type<>(ResourceLocation.parse("trackssimulate:suspension"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Apply> CODEC=new StreamCodec<>() {
            public Apply decode(RegistryFriendlyByteBuf b) {
                UUID id=b.readUUID();BlockPos pos=b.readBlockPos();ResourceLocation dim=b.readResourceLocation();
                boolean enabled=b.readBoolean(),visible=b.readBoolean(),strut=b.readBoolean(),whole=b.readBoolean(),radiusAll=b.readBoolean(),fullAxle=b.readBoolean(),positive=b.readBoolean();
                double[] values=new double[11];for(int i=0;i<11;i++) values[i]=b.readDouble();
                return new Apply(id,pos,dim,enabled,visible,strut,whole,radiusAll,fullAxle,positive,values);
            }
            public void encode(RegistryFriendlyByteBuf b,Apply p) {
                b.writeUUID(p.request);b.writeBlockPos(p.pos);b.writeResourceLocation(p.dimension);b.writeBoolean(p.enabled);
                b.writeBoolean(p.visible);b.writeBoolean(p.strut);b.writeBoolean(p.wholeLoop);b.writeBoolean(p.radiusAll);b.writeBoolean(p.fullAxle);b.writeBoolean(p.positive);for(double value:p.values) b.writeDouble(value);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Reply(UUID request,boolean success,String message) implements CustomPacketPayload {
        public static final Type<Reply> TYPE=new Type<>(ResourceLocation.parse("trackssimulate:wheel_settings_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=new StreamCodec<>() {
            public Reply decode(RegistryFriendlyByteBuf b) { return new Reply(b.readUUID(),b.readBoolean(),b.readUtf(256)); }
            public void encode(RegistryFriendlyByteBuf b,Reply p) { b.writeUUID(p.request);b.writeBoolean(p.success);b.writeUtf(p.message,256); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private SuspensionNetwork() {}
}
