package dev.trackssimulate.physics;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.sublevel.*;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.*;
import java.util.function.Predicate;

/** Independent adapter for the installed Sable Rapier provider's constraint lifecycle. */
public final class ConnectedBodies {
    private static final Map<Object,ConstraintLinks<PhysicsPipelineBody>> GRAPHS=new IdentityHashMap<>();
    private record Cached(long tick,ConstraintLinks.Reach<PhysicsPipelineBody> reach){}
    private static final Map<ServerSubLevel,Cached> CACHE=new WeakHashMap<>();
    private static final Map<Object,Long> MAINTAINED=new IdentityHashMap<>();
    public static void added(Object pipeline,PhysicsPipelineBody a,PhysicsPipelineBody b,PhysicsConstraintHandle handle){
        if(a==null||b==null||handle==null)return;
        GRAPHS.computeIfAbsent(pipeline,k->new ConstraintLinks<>()).add(a,b,()->!a.isRemoved()&&!b.isRemoved()&&handle.isValid());CACHE.clear();
    }
    public static void disposed(Object pipeline){GRAPHS.remove(pipeline);MAINTAINED.remove(pipeline);CACHE.clear();}
    public static void clear(){GRAPHS.clear();CACHE.clear();MAINTAINED.clear();}
    public static void maintain(net.minecraft.server.level.ServerLevel level){
        var system=SubLevelPhysicsSystem.get(level);if(system==null)return;
        var pipeline=system.getPipeline();long tick=level.getGameTime();var last=MAINTAINED.get(pipeline);
        if(last!=null&&last==tick)return;MAINTAINED.put(pipeline,tick);
        var graph=GRAPHS.get(pipeline);if(graph!=null)graph.prune(64);
        CACHE.entrySet().removeIf(e->e.getKey().isRemoved()||e.getKey().getLevel()==level&&e.getValue().tick<tick-40);
    }
    public static Predicate<SubLevel> ignoring(ServerSubLevel host,boolean allowConnected){
        if(allowConnected)return other->other==host;
        long tick=host.getLevel().getGameTime();var cached=CACHE.get(host);
        if(cached==null||cached.tick!=tick){
            var graph=GRAPHS.get(SubLevelPhysicsSystem.require(host.getLevel()).getPipeline());
            var reach=graph==null?new ConstraintLinks.Reach<PhysicsPipelineBody>(Set.of(host),true):graph.from(host,256,2048);
            cached=new Cached(tick,reach);CACHE.put(host,cached);
            if(!reach.complete()&&tick%100==0)dev.trackssimulate.Tracks.LOGGER.warn("[TS-CONTACT-FILTER] body={} topology_budget_exceeded nodes={} suppressSublevelContacts=true",host.getUniqueId(),reach.nodes().size());
        }
        var reach=cached.reach;
        // An incomplete graph cannot safely classify another body as unrelated.
        return other->other==host||!reach.complete()||reach.nodes().contains(other);
    }
    private ConnectedBodies(){}
}
