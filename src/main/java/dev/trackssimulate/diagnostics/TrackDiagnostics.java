package dev.trackssimulate.diagnostics;

import dev.trackssimulate.Tracks;
import dev.trackssimulate.config.TracksServerConfig;
import dev.trackssimulate.physics.SuspensionContactSolver;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import org.joml.Vector3dc;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Physics-thread aggregation; detached windows are formatted and written off-thread. */
public final class TrackDiagnostics {
    private static final AsyncDiagnosticLog OUTPUT=new AsyncDiagnosticLog(
        java.nio.file.Path.of("logs","trackssimulate-physics.log"),32,8L*1024*1024,3,Tracks.LOGGER::info);
    private record Context(long capturedMillis,String dimension,int body,double mass,
            org.joml.Vector3d com,org.joml.Vector3d inertia,org.joml.Vector3d linear,org.joml.Vector3d angular,double wallMs) {}
    private static final WeakHashMap<ServerSubLevel,Window> WINDOWS=new WeakHashMap<>();
    public static void motion(java.util.UUID belt,int body,int nodes,int contacts,double speed,double distance,double normal,double tangent,double mass) {
        if(!TracksServerConfig.PHYSICS_LOG.get())return;
        long captured=System.currentTimeMillis();
        OUTPUT.submit(()->"[TS-MOTION] capturedAt="+java.time.Instant.ofEpochMilli(captured)+" belt="+belt+" body="+body
            +" mode=passive nodes="+nodes+" contacts="+contacts+" speed="+speed+" distance="+distance
            +" rollingMass="+mass+" supportImpulse="+normal+" absForwardImpulse="+tangent+"\n");
    }
    private static final class Window {
        long start=System.nanoTime(),calls,nanos,contacts,limits,queries;
        double minExtension=Double.POSITIVE_INFINITY,maxExtension=Double.NEGATIVE_INFINITY;
        double maxImpulse,maxRpm,maxForward,maxSide,minDt=Double.POSITIVE_INFINITY,maxDt,normalSum;
        long reachable,zeroSupport;
        long solverNanos,solverCalls;
        long surfaceNanos,surfaceRays,surfaceRequested,surfaceRejected,surfaceMerged,surfaceInside,surfaceExpired,surfaceRouted;
        int surfaceSuspensionWheels;
        int surfaceContacts;double surfaceNormal,surfaceTangent,surfacePenetration;
        int solverContacts,solverIterations;
        double solverResidual,maxPredictedDv,maxPredictedDw;
        final Set<Long> mounts=new HashSet<>(), roadWheels=new HashSet<>(), driveWheels=new HashSet<>();
        final Map<Long,WheelWindow> wheels=new LinkedHashMap<>();
        BlockPos lastMount=BlockPos.ZERO,lastContact=BlockPos.ZERO;
    }
    private static final class WheelWindow {
        String role;
        long hits,contacts,zeroSupport;
        double minExt=Double.POSITIVE_INFINITY,maxExt=Double.NEGATIVE_INFINITY;
        double minRaw=Double.POSITIVE_INFINITY,maxRaw=Double.NEGATIVE_INFINITY;
        double radius,width,travel,maxCompression,normalSum,maxSpring,minDamping=Double.POSITIVE_INFINITY,maxDamping=Double.NEGATIVE_INFINITY;
        double compressionLimit,downOffset,spread,margin,forwardMu,sideMu,stopSum,penetrationMax,forwardImpulseMax,sideImpulseMax;
        long stops,atCap;
    }
    public static synchronized void solver(ServerSubLevel host,int contacts,SuspensionContactSolver.Result result,long nanos) {
        if(!TracksServerConfig.PHYSICS_LOG.get())return;
        var w=WINDOWS.computeIfAbsent(host,h->new Window());
        w.solverNanos+=nanos;w.solverCalls++;w.solverContacts=Math.max(w.solverContacts,contacts);
        w.solverIterations=Math.max(w.solverIterations,result.iterations());
        w.solverResidual=Math.max(w.solverResidual,result.residual());
        w.maxPredictedDv=Math.max(w.maxPredictedDv,result.maxDeltaSpeed());
        w.maxPredictedDw=Math.max(w.maxPredictedDw,result.maxDeltaAngularSpeed());
    }
    public static synchronized void surface(ServerSubLevel host,dev.trackssimulate.physics.SurfaceContacts.Batch batch,double normal,double tangent) {
        if(!TracksServerConfig.PHYSICS_LOG.get()||batch==null)return;
        var w=WINDOWS.computeIfAbsent(host,h->new Window());
        w.surfaceInside+=batch.inside();w.surfaceExpired+=batch.expired();
        w.surfaceRouted+=batch.routed();w.surfaceSuspensionWheels=Math.max(w.surfaceSuspensionWheels,batch.supports().size());
        w.surfaceNanos+=batch.nanos();w.surfaceRays+=batch.rays();w.surfaceRequested+=batch.requested();
        w.surfaceRejected+=batch.rejected();w.surfaceMerged+=batch.merged();w.surfaceContacts=Math.max(w.surfaceContacts,batch.contacts().size());
        w.surfaceNormal+=normal;w.surfaceTangent+=tangent;
        for(var c:batch.contacts())w.surfacePenetration=Math.max(w.surfacePenetration,c.penetration());
    }
    public static synchronized void sample(ServerSubLevel host,BlockPos pos,String role,double radius,double width,double dt,double extension,double travel,
            Vector3dc impulse,double normal,double rpm,double forward,double side,BlockPos hit,boolean contact,
            double rawExtension,double compression,double springForce,double dampingForce,boolean limited,int queries,long nanos,
            double compressionLimit,double spread,double margin,double forwardMu,double sideMu,double stopImpulse,double penetration,
            double forwardImpulse,double sideImpulse,double downOffset) {
        if(!TracksServerConfig.PHYSICS_LOG.get())return;
        var w=WINDOWS.computeIfAbsent(host,h->new Window());
        w.calls++;w.nanos+=nanos;w.queries+=queries;if(hit!=null){w.contacts++;w.lastContact=hit.immutable();}
        if(limited)w.limits++;
        w.mounts.add(pos.asLong());w.lastMount=pos.immutable();
        (role.equals("DRIVE")?w.driveWheels:w.roadWheels).add(pos.asLong());
        if(contact) {w.reachable++;if(normal<=0)w.zeroSupport++;}
        var wheel=w.wheels.computeIfAbsent(pos.asLong(),p->new WheelWindow());wheel.role=role;wheel.radius=radius;wheel.width=width;wheel.travel=travel;
        wheel.minExt=Math.min(wheel.minExt,extension);wheel.maxExt=Math.max(wheel.maxExt,extension);
        if(Double.isFinite(rawExtension)){wheel.minRaw=Math.min(wheel.minRaw,rawExtension);wheel.maxRaw=Math.max(wheel.maxRaw,rawExtension);}
        if(hit!=null)wheel.hits++;if(contact)wheel.contacts++;if(contact&&normal<=0)wheel.zeroSupport++;
        wheel.maxCompression=Math.max(wheel.maxCompression,compression);wheel.normalSum+=normal;
        wheel.compressionLimit=compressionLimit;wheel.downOffset=downOffset;wheel.spread=spread;wheel.margin=margin;wheel.forwardMu=forwardMu;wheel.sideMu=sideMu;
        wheel.stopSum+=stopImpulse;wheel.penetrationMax=Math.max(wheel.penetrationMax,penetration);
        wheel.forwardImpulseMax=Math.max(wheel.forwardImpulseMax,Math.abs(forwardImpulse));wheel.sideImpulseMax=Math.max(wheel.sideImpulseMax,Math.abs(sideImpulse));
        if(stopImpulse>1e-8)wheel.stops++;if(limited&&normal>1e-8)wheel.atCap++;
        wheel.maxSpring=Math.max(wheel.maxSpring,springForce);wheel.minDamping=Math.min(wheel.minDamping,dampingForce);wheel.maxDamping=Math.max(wheel.maxDamping,dampingForce);
        w.minDt=Math.min(w.minDt,dt);w.maxDt=Math.max(w.maxDt,dt);
        w.minExtension=Math.min(w.minExtension,extension);w.maxExtension=Math.max(w.maxExtension,extension);
        w.maxImpulse=Math.max(w.maxImpulse,impulse.length());w.normalSum+=normal;
        w.maxRpm=Math.max(w.maxRpm,Math.abs(rpm));w.maxForward=Math.max(w.maxForward,Math.abs(forward));w.maxSide=Math.max(w.maxSide,Math.abs(side));
        long now=System.nanoTime();if(now-w.start<1_000_000_000L)return;
        var mass=host.getMassTracker();var handle=RigidBodyHandle.of(host);
        // Transfer ownership of the completed window. The worker never reads a
        // world, block entity, Sable handle or mutable engine-owned vector.
        var tensor=mass.getInertiaTensor();
        var context=new Context(System.currentTimeMillis(),host.getLevel().dimension().location().toString(),
            System.identityHashCode(host),mass.getMass(),new org.joml.Vector3d(mass.getCenterOfMass()),
            new org.joml.Vector3d(tensor.m00(),tensor.m11(),tensor.m22()),
            new org.joml.Vector3d(handle.getLinearVelocity()),new org.joml.Vector3d(handle.getAngularVelocity()),(now-w.start)/1e6);
        WINDOWS.put(host,new Window());
        OUTPUT.submit(()->format(context,w));
    }
    private static String format(Context c,Window w) {
        StringBuilder report=new StringBuilder(2048+w.wheels.size()*512);
        report.append("[TS-SAMPLE] capturedAt=").append(java.time.Instant.ofEpochMilli(c.capturedMillis)).append('\n');
        line(report,"[TS-PHYS] version=0.6.0-rebuild.27 dimension={} body={} wallMs={} mounts={} calls={} hits={} rays={} dt=[{},{}] mass={} com={} inertiaDiagonal={} extension=[{},{}] maxImpulse={} normalImpulseSum={} maxAbsInputRpm={} maxAbsLocalForward={} maxAbsLocalSide={} limited={} physicsMs={} linear={} angular={} lastMount={} lastHit={} roadWheels={} driveWheels={} reachable={} zeroSupport={}",
            c.dimension,c.body,c.wallMs,w.mounts.size(),w.calls,w.contacts,w.queries,w.minDt,w.maxDt,
            c.mass,c.com,c.inertia,w.minExtension,w.maxExtension,w.maxImpulse,w.normalSum,w.maxRpm,w.maxForward,w.maxSide,
            w.limits,w.nanos/1e6,c.linear,c.angular,w.lastMount,w.lastContact,w.roadWheels.size(),w.driveWheels.size(),w.reachable,w.zeroSupport);
        // Build readable strings only once per aggregate window, not every physics substep.
        StringBuilder rows=new StringBuilder();int count=0;
        for(var entry:w.wheels.entrySet()) {
            if(count++>=64){rows.append(" ...truncated");break;}
            BlockPos p=BlockPos.of(entry.getKey());var row=entry.getValue();
            rows.append(String.format(Locale.ROOT,
                " [%d,%d,%d %s radius=%.3f width=%.3f ext=%.3f..%.3f raw=%.3f..%.3f travel=%.3f compressionMax=%.3f hit=%d contact=%d zeroN=%d normalSum=%.3f springMax=%.2f damping=%.2f..%.2f]",
                p.getX(),p.getY(),p.getZ(),row.role,row.radius,row.width,row.minExt,row.maxExt,
                (Double.isFinite(row.minRaw)?row.minRaw:0),(Double.isFinite(row.maxRaw)?row.maxRaw:0),row.travel,row.maxCompression,row.hits,row.contacts,row.zeroSupport,
                row.normalSum,row.maxSpring,row.minDamping,row.maxDamping));
            if(row.role.equals("SUSPENSION"))rows.append(String.format(Locale.ROOT,
                " {relativeLimits=[%.3f,%.3f] downOffset=%.3f stroke=%.3f absoluteDrop=[%.3f,%.3f] spread=%.2f margin=%.2f frictionMultipliers=[%.2f,%.2f] stopSteps=%d stopImpulseSum=%.3f penetrationMax=%.4f springCapSteps=%d tangentImpulseMax=[%.3f,%.3f]}",
                row.travel-row.compressionLimit,row.travel,row.downOffset,row.compressionLimit,row.downOffset+row.travel-row.compressionLimit,row.downOffset+row.travel,
                row.spread,row.margin,row.forwardMu,row.sideMu,row.stops,row.stopSum,row.penetrationMax,row.atCap,row.forwardImpulseMax,row.sideImpulseMax));
        }
        line(report,"[TS-SUSP] dimension={} body={} wheels={}",c.dimension,c.body,rows);
        line(report,"[TS-SOLVER] dimension={} body={} calls={} contactsMax={} iterationsMax={} normalResidualMax={} predictedDvMax={} predictedDwMax={} solverMs={}",
            c.dimension,c.body,w.solverCalls,w.solverContacts,w.solverIterations,
            w.solverResidual,w.maxPredictedDv,w.maxPredictedDw,w.solverNanos/1e6);
        line(report,"[TS-SURFACE] dimension={} body={} rays={} requested={} contactsMax={} rejected={} merged={} insideRejected={} expiredFaces={} suspensionRouted={} suspensionWheelsMax={} penetrationMax={} normalImpulseSum={} absTangentImpulseSum={} collectionMs={}",
            c.dimension,c.body,w.surfaceRays,w.surfaceRequested,w.surfaceContacts,w.surfaceRejected,w.surfaceMerged,w.surfaceInside,w.surfaceExpired,
            w.surfaceRouted,w.surfaceSuspensionWheels,w.surfacePenetration,w.surfaceNormal,w.surfaceTangent,w.surfaceNanos/1e6);
        return report.toString();
    }
    private static void line(StringBuilder output,String pattern,Object... args) {
        output.append(org.slf4j.helpers.MessageFormatter.arrayFormat(pattern,args).getMessage()).append('\n');
    }
    public static synchronized void clear(){WINDOWS.clear();OUTPUT.close();}
    private TrackDiagnostics() {}
}
