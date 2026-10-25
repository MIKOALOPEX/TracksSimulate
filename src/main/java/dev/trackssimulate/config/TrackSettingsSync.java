package dev.trackssimulate.config;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.assembly.TrackAssemblies;
import dev.trackssimulate.content.blocks.sable_track.*;
import dev.ryanhcode.sable.Sable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Main-thread transaction: resolve bounded targets, validate everything, then publish once. */
public final class TrackSettingsSync {
    private static final Map<ServerPlayer,Long> LAST_SCAN=new WeakHashMap<>();
    private TrackSettingsSync() {}
    public static String apply(ServerPlayer player, BlockPos pos, UUID expectedBelt, OffsetScope scope,
            Map<TrackSetting,Double> values) {
        long started=System.nanoTime();
        var level=player.serverLevel();
        if(values.isEmpty()||values.size()>TrackSetting.values().length)throw new IllegalArgumentException("配置项数量无效");
        for(var e:values.entrySet())if(!e.getKey().accepts(e.getValue()))throw new IllegalArgumentException("参数超出范围："+e.getKey().key);
        if(!player.mayBuild()||!level.hasChunkAt(pos)||!level.mayInteract(player,pos))throw new IllegalArgumentException("当前轮未加载或不可编辑");
        if(!(level.getBlockEntity(pos) instanceof SableTrackBlockEntity source)||!source.effectivePart().isWheel())throw new IllegalArgumentException("当前轮已被拆除");
        var body=Sable.HELPER.getContaining(source);
        var point=pos.getCenter();if(body!=null)point=body.logicalPose().transformPosition(point);
        if(player.distanceToSqr(point)>64)throw new IllegalArgumentException("距离轮子过远，请靠近后操作");
        if(!Objects.equals(expectedBelt,source.beltId()))throw new IllegalArgumentException("履带连接状态已变化，请重新打开 GUI");
        if(scope==OffsetScope.ASSEMBLY&&expectedBelt==null)throw new IllegalArgumentException("未连接的轮子不能使用整组同步");
        var candidates=new ArrayList<SableTrackBlockEntity>();int scanned=0;
        if(expectedBelt!=null) {
            var assembly=TrackAssemblies.get(source);
            if(assembly==null||assembly.offsets().size()>TrackSyncPolicy.MAX_TARGETS)throw new IllegalArgumentException("履带环标记无效，请重新连接");
            // Read the bounded topology directly: never trust a same-tick cached member after replacement.
            var origin=assembly.origin(source);
            for(int i=0;i<assembly.offsets().size();i++) {
                var p=origin.offset(assembly.offsets().get(i));scanned++;
                if(!level.hasChunkAt(p)||!(level.getBlockEntity(p) instanceof SableTrackBlockEntity w)
                    ||!expectedBelt.equals(w.beltId())||w.beltIndex()!=i||!w.effectivePart().isWheel()
                    ||Sable.HELPER.getContaining(w)!=body)
                    throw new IllegalArgumentException("履带环未完整加载或子结构已变化，本次未修改");
                candidates.add(w);
            }
        } else if(scope==OffsetScope.WHEEL) candidates.add(source);
        else {
            long tick=level.getGameTime();Long previous=LAST_SCAN.get(player);
            if(previous!=null&&tick>=previous&&tick-previous<10)throw new IllegalArgumentException("附近同类查找间隔至少 0.5 秒，请稍后重试");
            LAST_SCAN.put(player,tick);
            int range=TrackSyncPolicy.RANGE;
            for(int dx=-range;dx<=range;dx++)for(int dy=-range;dy<=range;dy++)for(int dz=-range;dz<=range;dz++) {
                if(dx*dx+dy*dy+dz*dz>range*range)continue;
                var p=pos.offset(dx,dy,dz);scanned++;
                if(!level.hasChunkAt(p)||!(level.getBlockEntity(p) instanceof SableTrackBlockEntity w)
                    ||!w.effectivePart().isWheel()||w.beltId()!=null||w.effectiveRole()!=source.effectiveRole())continue;
                if(Sable.HELPER.getContaining(w)==body)candidates.add(w);
                if(candidates.size()>TrackSyncPolicy.MAX_TARGETS)throw new IllegalArgumentException("8 格内同类轮超过 64 个，本次未修改；请先连接履带或使用当前轮");
            }
        }
        var targets=candidates.stream().filter(w->TrackSyncPolicy.includes(scope,expectedBelt,w.beltId(),
            w==source,w.effectiveRole()==source.effectiveRole(),true,w.getBlockPos().distSqr(pos))).toList();
        if(targets.isEmpty())throw new IllegalArgumentException("未找到可同步的轮子");
        var changes=new LinkedHashMap<SableTrackBlockEntity,Map<TrackSetting,Double>>();
        var deltas=new HashMap<SableTrackBlockEntity,Vec3>();var radii=new HashMap<SableTrackBlockEntity,Double>();
        int skipped=0, supported=0;
        for(var w:targets) {
            if(!level.mayInteract(player,w.getBlockPos()))throw new IllegalArgumentException("目标轮不可编辑，本次全部未修改");
            var fields=new EnumMap<TrackSetting,Double>(TrackSetting.class);
            for(var e:values.entrySet()) {
                if(!e.getKey().supports(w.effectiveRole()==SableTrackRole.SUSPENSION,w.effectiveRole()==SableTrackRole.DRIVE)){skipped++;continue;}
                supported++;
                if(Math.abs(w.getSettingValue(e.getKey().key)-e.getValue())>1e-9)fields.put(e.getKey(),e.getValue());
            }
            if(fields.isEmpty())continue;
            w.validateTravelSettings(fields);
            changes.put(w,fields);
            if(fields.keySet().stream().anyMatch(TrackSetting::isOffset))deltas.put(w,w.offsetDelta(
                fields.getOrDefault(TrackSetting.LATERAL,w.getSettingValue("lateral")),
                fields.getOrDefault(TrackSetting.LONGITUDINAL,w.getSettingValue("longitudinal")),
                fields.getOrDefault(TrackSetting.HEIGHT,w.getSettingValue("height"))));
            if(fields.containsKey(TrackSetting.SCALE))radii.put(w,TrackSetting.scaledRadius(w.effectivePart().radius(),fields.get(TrackSetting.SCALE)));
        }
        if(supported==0)throw new IllegalArgumentException("所选轮种不使用这些配置项");
        var geometry=deltas.isEmpty()&&radii.isEmpty()?null:TrackAssemblies.prepareGeometry(source,deltas,radii,true);
        if(changes.values().stream().anyMatch(fields->fields.containsKey(TrackSetting.THROUGH_AXLE)))
            dev.trackssimulate.assembly.TrackDriveNetwork.change(candidates,()->changes.forEach(SableTrackBlockEntity::applyLocalSettings),"axle_settings");
        else changes.forEach(SableTrackBlockEntity::applyLocalSettings);
        if(geometry!=null)geometry.commit("settings_push_"+scope);
        Tracks.LOGGER.info("[TS-CONFIG] operation=push player={} source={} assembly={} scope={} requested={} targets={} changed={} skippedFields={} scanned={} replan={} radii={} elapsedMs={}",
            player.getGameProfile().getName(),pos,expectedBelt,scope,values,targets.size(),changes.size(),skipped,scanned,geometry!=null,
            targets.stream().map(SableTrackBlockEntity::wheelRadius).toList(),(System.nanoTime()-started)/1e6);
        return "已应用：范围内 "+targets.size()+" 个轮，修改 "+changes.size()+" 个；跳过 "+skipped+" 项不适用字段"
            +(expectedBelt==null&&scope==OffsetScope.ROLE?"（8 格内同子结构、未连接、已加载的同类轮）":"");
    }
}
