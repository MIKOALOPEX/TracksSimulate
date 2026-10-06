package dev.trackssimulate.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.trackssimulate.physics.contact.*;
import dev.trackssimulate.wheel.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Samples solver inputs AND outputs; does not claim to observe the later native constraint solve. */
final class PhysicsDiagnostics {
    private static final DiagnosticLog LOG=new DiagnosticLog(Path.of("logs","trackssimulate-physics.log"),5*1024*1024,3);
    private static boolean ioWarning;
    static final class State {
        long lastTick=Long.MIN_VALUE,lastSpike=Long.MIN_VALUE;
        int steps;double seconds,normalImpulse,peakDepth,peakDeltaV,peakDeltaW,peakSpeed,peakSpin,peakExternalV,peakExternalW;
        V3 previousPredictedV,previousPredictedW;
        V3 previousLocalCom;double previousMass=Double.NaN,peakMassChange,peakComShift,peakSpringSpeed;
        String previous="no previous sample";
    }
    static void sample(State state,ServerSubLevel host,double dt,SableContactBody body,Map<WheelBlockEntity,ContactSolver.Group> groups,Map<WheelBlockEntity,BeltSuspension> springs) {
        try {record(state,host,dt,body,groups,springs);}catch(Exception ex){warn(ex);}
    }
    private static void record(State state,ServerSubLevel host,double dt,SableContactBody body,Map<WheelBlockEntity,ContactSolver.Group> groups,Map<WheelBlockEntity,BeltSuspension> springs) {
        if(body==null)return;
        long tick=host.getLevel().getGameTime();double depth=0,normal=0;
        double mass=1/body.inverseMass;
        V3 localCom=SableContactBody.of(host.logicalPose().rotationPoint());
        double massChange=Double.isNaN(state.previousMass)?0:mass-state.previousMass;
        double comShift=state.previousLocalCom==null?0:localCom.sub(state.previousLocalCom).length();
        state.previousMass=mass;state.previousLocalCom=localCom;
        state.peakMassChange=Math.max(state.peakMassChange,Math.abs(massChange));state.peakComShift=Math.max(state.peakComShift,comShift);
        for(var spring:springs.values())state.peakSpringSpeed=Math.max(state.peakSpringSpeed,Math.abs(spring.speed()));
        for(var g:groups.values())for(var c:g.contacts()){depth=Math.max(depth,c.penetration);normal+=c.normalImpulse;}
        double dv=body.linear.sub(body.initialLinear).length(),dw=body.angular.sub(body.initialAngular).length();
        double externalV=state.previousPredictedV==null?0:body.initialLinear.sub(state.previousPredictedV).length();
        double externalW=state.previousPredictedW==null?0:body.initialAngular.sub(state.previousPredictedW).length();
        state.previousPredictedV=body.linear;state.previousPredictedW=body.angular;
        state.peakExternalV=Math.max(state.peakExternalV,externalV);state.peakExternalW=Math.max(state.peakExternalW,externalW);
        state.steps++;state.seconds+=dt;state.normalImpulse+=normal;state.peakDepth=Math.max(state.peakDepth,depth);
        state.peakDeltaV=Math.max(state.peakDeltaV,dv);state.peakDeltaW=Math.max(state.peakDeltaW,dw);
        state.peakSpeed=Math.max(state.peakSpeed,body.initialLinear.length());state.peakSpin=Math.max(state.peakSpin,body.initialAngular.length());
        boolean spike=dv>1||dw>1||externalV>1||externalW>1||depth>.15;
        boolean changed=Math.abs(massChange)>1e-7||comShift>1e-6;
        boolean report=changed||state.lastTick==Long.MIN_VALUE||tick-state.lastTick>=5||spike&&(state.lastSpike==Long.MIN_VALUE||tick-state.lastSpike>=2);
        if(!report)return;
        if(spike)state.lastSpike=tick;
        StringBuilder text=new StringBuilder(header(host,tick,dt,changed?"mass_change":spike?"spike":"sample"));
        text.append(fmt(" mass=%.3f window_s=%.5f steps=%d mean_contact_force=%.3f peak_penetration=%.6f peak_contact_dv=%.6f peak_contact_dw=%.6f peak_speed=%.6f peak_spin=%.6f",1/body.inverseMass,state.seconds,state.steps,state.normalImpulse/state.seconds,state.peakDepth,state.peakDeltaV,state.peakDeltaW,state.peakSpeed,state.peakSpin));
        text.append(fmt(" peak_external_dv=%.6f peak_external_dw=%.6f",state.peakExternalV,state.peakExternalW));
        text.append(fmt(" mass_delta=%.6f peak_mass_delta=%.6f local_com_shift=%.6f peak_com_shift=%.6f peak_spring_speed=%.6f",massChange,state.peakMassChange,comShift,state.peakComShift,state.peakSpringSpeed));
        text.append(" local_com=").append(vec(localCom));
        var inertia=body.inverseInertia;
        text.append(fmt(" inverse_inertia_local=[%.9g,%.9g,%.9g,%.9g,%.9g,%.9g]",inertia.m00(),inertia.m11(),inertia.m22(),inertia.m01(),inertia.m02(),inertia.m12()));
        text.append(" com=").append(vec(body.center)).append(" orientation_xyzw=").append(fmt("[%.6f,%.6f,%.6f,%.6f]",body.rotation.x,body.rotation.y,body.rotation.z,body.rotation.w));
        text.append(" native_v_before=").append(vec(body.initialLinear)).append(" native_w_before=").append(vec(body.initialAngular));
        text.append(" contact_v_after=").append(vec(body.linear)).append(" contact_w_after=").append(vec(body.angular));
        text.append(" contact_J=").append(vec(body.impulse)).append(" contact_torque_J=").append(vec(body.torque));
        text.append(" active_loops=").append(groups.size());
        for(var entry:groups.entrySet()) {
            var w=entry.getKey();var g=entry.getValue();var settings=w.beltSettings();
            double force=0,slipLong=0,slipSide=0,maxJ=0,maxOtherMass=0,sideUse=0;int loaded=0,ground=0,wall=0,dynamicContacts=0;ContactSolver.Contact strongest=null;
            for(var c:g.contacts())if(c.normalImpulse>1e-7) {
                loaded++;force+=c.normalImpulse/dt;slipLong=Math.max(slipLong,Math.abs(c.longitudinalSlip(g.belt())));slipSide=Math.max(slipSide,Math.abs(c.lateralSlip()));
                if(Math.abs(c.normal.y())>.7)ground++;else wall++;
                double sideLimit=g.lateralFriction()*c.gripScale*c.normalImpulse;
                if(sideLimit>1e-9)sideUse=Math.max(sideUse,Math.abs(c.sideImpulse)/sideLimit);
                if(c.other!=null){dynamicContacts++;if(c.source!=null)maxOtherMass=Math.max(maxOtherMass,c.source.mass());}
                if(c.normalImpulse>maxJ){maxJ=c.normalImpulse;strongest=c;}
            }
            text.append(" belt={root=").append(pos(w)).append(" loop=").append(w.loopId()).append(" axis=").append(w.axis());
            text.append(fmt(" speed=%.6f target=%.6f driven=%s conflict=%s brake=%.3f drive_force_limit=%.3f mu_long=%.3f mu_side=%.3f width=%.4f thickness=%.4f contacts=%d loaded=%d vertical_contacts=%d wall_contacts=%d normal_force=%.3f max_long_slip=%.6f max_side_slip=%.6f",g.belt().speed,w.driveTarget(),w.driven(),w.conflict(),settings.brake(),settings.driveForce(),settings.friction(),settings.lateralFriction(),w.beltWidth(),settings.thickness(),g.contacts().size(),loaded,ground,wall,force,slipLong,slipSide));
            text.append(fmt(" dynamic_loaded_contacts=%d max_contact_body_mass=%.3f",dynamicContacts,maxOtherMass));
            text.append(fmt(" lateral_slip_speed=%.4f drive_multiplier=%.4f",settings.lateralSlipSpeed(),settings.driveMultiplier()));
            text.append(fmt(" limited_target=%.6f motor_J=%.6f drag_J=%.6f speed_before_contacts=%.6f max_side_utilization=%.6f",g.belt().targetSpeed,g.belt().motorImpulse,g.belt().dragImpulse,g.belt().speedBeforeContacts,sideUse));
            text.append(fmt(" belt_mass=%.6f effective_drive_force=%.6f max_acceleration=%.4f inertia_ratio=%.4f contact_offset=%.4f side_offset=%.4f prediction_distance=%.4f side_grip=%.4f softness=%.4f recovery_speed=%.4f belt_kinetic_energy=%.6f",
                g.belt().mass,g.belt().driveForceLimit,settings.maxAcceleration(),settings.inertiaRatio(),settings.contactOffset(),settings.sideOffset(),settings.predictionDistance(),settings.sideGrip(),settings.contactSoftness(),settings.recoverySpeed(),.5*g.belt().mass*g.belt().speed*g.belt().speed));
            if(strongest!=null) {
                text.append(" strongest={point=").append(vec(strongest.point)).append(" normal=").append(vec(strongest.normal)).append(fmt(" depth=%.6f Jn=%.6f Jlong=%.6f Jside=%.6f",strongest.penetration,strongest.normalImpulse,strongest.longImpulse,strongest.sideImpulse));
                var source=strongest.source;
                text.append(fmt(" grip_scale=%.4f belt_projection=%.4f",strongest.gripScale,strongest.beltFactor));
                if(source!=null)text.append(" obstacle=").append(strongest.other==null?"terrain":"sublevel").append(" voxel=").append(source.x()).append(':').append(source.y()).append(':').append(source.z())
                    .append(" obstacle_body=").append(Integer.toHexString(source.bodyId())).append(fmt(" obstacle_mass=%.3f exposed_mask=%d",source.mass(),source.exposedFaces()));
                text.append('}');
            }
            text.append('}');
        }
        for(var entry:springs.entrySet()) {
            var w=entry.getKey();var s=w.suspensionSettings();var mode=entry.getValue();
            text.append(" spring={pos=").append(pos(w)).append(fmt(" extension=%.6f velocity_up=%.6f min=%.4f max=%.4f rest=%.4f stiffness=%.3f damping=%.3f modal_mass=%.3f at_stop=%s}",mode.extension(),mode.speed(),s.minimum(),s.maximum(),s.rest(),s.stiffness(),s.damping(),s.mass(),mode.atStop()));
        }
        state.previous=text.toString();write(state.previous);
        state.lastTick=tick;state.steps=0;state.seconds=state.normalImpulse=state.peakDepth=state.peakDeltaV=state.peakDeltaW=state.peakSpeed=state.peakSpin=state.peakExternalV=state.peakExternalW=0;
        state.peakMassChange=state.peakComShift=state.peakSpringSpeed=0;
    }
    static void failed(State state,ServerSubLevel host,double dt,RuntimeException failure) {
        try {write(header(host,host.getLevel().getGameTime(),dt,"fault")+" reason="+failure.toString().replace('\n',' ')+" last_sample="+state.previous);}catch(Exception ex){warn(ex);}
    }
    private static String header(ServerSubLevel host,long tick,double dt,String event) {return Instant.now()+" version=0.5.11 contact_model=soft_shear_v1 event="+event+" dimension="+host.getLevel().dimension().location()+" vehicle="+Integer.toHexString(System.identityHashCode(host))+" tick="+tick+fmt(" dt=%.6f",dt);}
    private static String pos(WheelBlockEntity w) {var p=w.getBlockPos();return p.getX()+":"+p.getY()+":"+p.getZ();}
    private static String vec(V3 v) {return fmt("[%.6f,%.6f,%.6f]",v.x(),v.y(),v.z());}
    private static String fmt(String format,Object... values) {return String.format(Locale.ROOT,format,values);}
    private static void write(String line) {
        try {LOG.append(line);}catch(Exception ex){warn(ex);}
    }
    private static void warn(Exception ex) {if(!ioWarning){ioWarning=true;Sable.LOGGER.warn("TracksSimulate physics diagnostics unavailable; simulation continues",ex);}}
    private PhysicsDiagnostics() {}
}
