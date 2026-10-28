// Recovered from the verified rebuild.24 artifact; see docs/RESTORE_REBUILD24_2026-10-26.md.
package dev.trackssimulate.diagnostics;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.trackssimulate.Tracks;
import dev.trackssimulate.config.TracksServerConfig;
import dev.trackssimulate.physics.SuspensionContactSolver;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.helpers.MessageFormatter;

public final class TrackDiagnostics {
   private static final AsyncDiagnosticLog OUTPUT = new AsyncDiagnosticLog(Path.of("logs", "trackssimulate-physics.log"), 32, 8388608L, 3, Tracks.LOGGER::info);
   private static final WeakHashMap<ServerSubLevel, TrackDiagnostics.Window> WINDOWS = new WeakHashMap<>();

   public static void motion(UUID belt, int body, int nodes, int contacts, double speed, double distance, double normal, double tangent, double mass) {
      if ((Boolean)TracksServerConfig.PHYSICS_LOG.get()) {
         long captured = System.currentTimeMillis();
         OUTPUT.submit(
            () -> "[TS-MOTION] capturedAt="
                  + Instant.ofEpochMilli(captured)
                  + " belt="
                  + belt
                  + " body="
                  + body
                  + " mode=passive nodes="
                  + nodes
                  + " contacts="
                  + contacts
                  + " speed="
                  + speed
                  + " distance="
                  + distance
                  + " rollingMass="
                  + mass
                  + " supportImpulse="
                  + normal
                  + " absForwardImpulse="
                  + tangent
                  + "\n"
         );
      }
   }

   public static synchronized void solver(ServerSubLevel host, int contacts, SuspensionContactSolver.Result result, long nanos) {
      if ((Boolean)TracksServerConfig.PHYSICS_LOG.get()) {
         TrackDiagnostics.Window w = WINDOWS.computeIfAbsent(host, h -> new TrackDiagnostics.Window());
         w.solverNanos += nanos;
         w.solverCalls++;
         w.solverContacts = Math.max(w.solverContacts, contacts);
         w.solverIterations = Math.max(w.solverIterations, result.iterations());
         w.solverResidual = Math.max(w.solverResidual, result.residual());
         w.maxPredictedDv = Math.max(w.maxPredictedDv, result.maxDeltaSpeed());
         w.maxPredictedDw = Math.max(w.maxPredictedDw, result.maxDeltaAngularSpeed());
      }
   }

   public static synchronized void sample(
      ServerSubLevel host,
      BlockPos pos,
      boolean drive,
      double radius,
      double width,
      double dt,
      double extension,
      double travel,
      Vector3dc impulse,
      double normal,
      double rpm,
      double forward,
      double side,
      BlockPos hit,
      boolean contact,
      double rawExtension,
      double compression,
      double springForce,
      double dampingForce,
      boolean limited,
      int queries,
      long nanos,
      double compressionLimit,
      double spread,
      double margin,
      double forwardMu,
      double sideMu,
      double stopImpulse,
      double penetration,
      double forwardImpulse,
      double sideImpulse,
      double downOffset
   ) {
      if ((Boolean)TracksServerConfig.PHYSICS_LOG.get()) {
         TrackDiagnostics.Window w = WINDOWS.computeIfAbsent(host, h -> new TrackDiagnostics.Window());
         w.calls++;
         w.nanos += nanos;
         w.queries += (long)queries;
         if (hit != null) {
            w.contacts++;
            w.lastContact = hit.immutable();
         }

         if (limited) {
            w.limits++;
         }

         w.mounts.add(pos.asLong());
         w.lastMount = pos.immutable();
         (drive ? w.driveWheels : w.roadWheels).add(pos.asLong());
         if (contact) {
            w.reachable++;
            if (normal <= 0.0) {
               w.zeroSupport++;
            }
         }

         TrackDiagnostics.WheelWindow wheel = w.wheels.computeIfAbsent(pos.asLong(), p -> new TrackDiagnostics.WheelWindow());
         wheel.drive = drive;
         wheel.radius = radius;
         wheel.width = width;
         wheel.travel = travel;
         wheel.minExt = Math.min(wheel.minExt, extension);
         wheel.maxExt = Math.max(wheel.maxExt, extension);
         if (Double.isFinite(rawExtension)) {
            wheel.minRaw = Math.min(wheel.minRaw, rawExtension);
            wheel.maxRaw = Math.max(wheel.maxRaw, rawExtension);
         }

         if (hit != null) {
            wheel.hits++;
         }

         if (contact) {
            wheel.contacts++;
         }

         if (contact && normal <= 0.0) {
            wheel.zeroSupport++;
         }

         wheel.maxCompression = Math.max(wheel.maxCompression, compression);
         wheel.normalSum += normal;
         wheel.compressionLimit = compressionLimit;
         wheel.downOffset = downOffset;
         wheel.spread = spread;
         wheel.margin = margin;
         wheel.forwardMu = forwardMu;
         wheel.sideMu = sideMu;
         wheel.stopSum += stopImpulse;
         wheel.penetrationMax = Math.max(wheel.penetrationMax, penetration);
         wheel.forwardImpulseMax = Math.max(wheel.forwardImpulseMax, Math.abs(forwardImpulse));
         wheel.sideImpulseMax = Math.max(wheel.sideImpulseMax, Math.abs(sideImpulse));
         if (stopImpulse > 1.0E-8) {
            wheel.stops++;
         }

         if (limited && normal > 1.0E-8) {
            wheel.atCap++;
         }

         wheel.maxSpring = Math.max(wheel.maxSpring, springForce);
         wheel.minDamping = Math.min(wheel.minDamping, dampingForce);
         wheel.maxDamping = Math.max(wheel.maxDamping, dampingForce);
         w.minDt = Math.min(w.minDt, dt);
         w.maxDt = Math.max(w.maxDt, dt);
         w.minExtension = Math.min(w.minExtension, extension);
         w.maxExtension = Math.max(w.maxExtension, extension);
         w.maxImpulse = Math.max(w.maxImpulse, impulse.length());
         w.normalSum += normal;
         w.maxRpm = Math.max(w.maxRpm, Math.abs(rpm));
         w.maxForward = Math.max(w.maxForward, Math.abs(forward));
         w.maxSide = Math.max(w.maxSide, Math.abs(side));
         long now = System.nanoTime();
         if (now - w.start >= 1000000000L) {
            MassData mass = host.getMassTracker();
            RigidBodyHandle handle = RigidBodyHandle.of(host);
            Matrix3dc tensor = mass.getInertiaTensor();
            TrackDiagnostics.Context context = new TrackDiagnostics.Context(
               System.currentTimeMillis(),
               host.getLevel().dimension().location().toString(),
               System.identityHashCode(host),
               mass.getMass(),
               new Vector3d(mass.getCenterOfMass()),
               new Vector3d(tensor.m00(), tensor.m11(), tensor.m22()),
               new Vector3d(handle.getLinearVelocity()),
               new Vector3d(handle.getAngularVelocity()),
               (double)(now - w.start) / 1000000.0
            );
            WINDOWS.put(host, new TrackDiagnostics.Window());
            OUTPUT.submit(() -> format(context, w));
         }
      }
   }

   private static String format(TrackDiagnostics.Context c, TrackDiagnostics.Window w) {
      StringBuilder report = new StringBuilder(2048 + w.wheels.size() * 512);
      report.append("[TS-SAMPLE] capturedAt=").append(Instant.ofEpochMilli(c.capturedMillis)).append('\n');
      line(
         report,
         "[TS-PHYS] version=0.6.0-rebuild.24 dimension={} body={} wallMs={} mounts={} calls={} hits={} rays={} dt=[{},{}] mass={} com={} inertiaDiagonal={} extension=[{},{}] maxImpulse={} normalImpulseSum={} maxAbsInputRpm={} maxAbsLocalForward={} maxAbsLocalSide={} limited={} physicsMs={} linear={} angular={} lastMount={} lastHit={} roadWheels={} driveWheels={} reachable={} zeroSupport={}",
         c.dimension,
         c.body,
         c.wallMs,
         w.mounts.size(),
         w.calls,
         w.contacts,
         w.queries,
         w.minDt,
         w.maxDt,
         c.mass,
         c.com,
         c.inertia,
         w.minExtension,
         w.maxExtension,
         w.maxImpulse,
         w.normalSum,
         w.maxRpm,
         w.maxForward,
         w.maxSide,
         w.limits,
         (double)w.nanos / 1000000.0,
         c.linear,
         c.angular,
         w.lastMount,
         w.lastContact,
         w.roadWheels.size(),
         w.driveWheels.size(),
         w.reachable,
         w.zeroSupport
      );
      StringBuilder rows = new StringBuilder();
      int count = 0;

      for (Entry<Long, TrackDiagnostics.WheelWindow> entry : w.wheels.entrySet()) {
         if (count++ >= 64) {
            rows.append(" ...truncated");
            break;
         }

         BlockPos p = BlockPos.of(entry.getKey());
         TrackDiagnostics.WheelWindow row = entry.getValue();
         rows.append(
            String.format(
               Locale.ROOT,
               " [%d,%d,%d %s radius=%.3f width=%.3f ext=%.3f..%.3f raw=%.3f..%.3f travel=%.3f compressionMax=%.3f hit=%d contact=%d zeroN=%d normalSum=%.3f springMax=%.2f damping=%.2f..%.2f]",
               p.getX(),
               p.getY(),
               p.getZ(),
               row.drive ? "DRIVE_FIXED" : "ROAD",
               row.radius,
               row.width,
               row.minExt,
               row.maxExt,
               row.drive ? 0.0 : row.minRaw,
               row.drive ? 0.0 : row.maxRaw,
               row.travel,
               row.maxCompression,
               row.hits,
               row.contacts,
               row.zeroSupport,
               row.normalSum,
               row.maxSpring,
               row.minDamping,
               row.maxDamping
            )
         );
         if (!row.drive) {
            rows.append(
               String.format(
                  Locale.ROOT,
                  " {relativeLimits=[%.3f,%.3f] downOffset=%.3f stroke=%.3f absoluteDrop=[%.3f,%.3f] spread=%.2f margin=%.2f frictionMultipliers=[%.2f,%.2f] stopSteps=%d stopImpulseSum=%.3f penetrationMax=%.4f springCapSteps=%d tangentImpulseMax=[%.3f,%.3f]}",
                  row.travel - row.compressionLimit,
                  row.travel,
                  row.downOffset,
                  row.compressionLimit,
                  row.downOffset + row.travel - row.compressionLimit,
                  row.downOffset + row.travel,
                  row.spread,
                  row.margin,
                  row.forwardMu,
                  row.sideMu,
                  row.stops,
                  row.stopSum,
                  row.penetrationMax,
                  row.atCap,
                  row.forwardImpulseMax,
                  row.sideImpulseMax
               )
            );
         }
      }

      line(report, "[TS-SUSP] dimension={} body={} wheels={}", c.dimension, c.body, rows);
      line(
         report,
         "[TS-SOLVER] dimension={} body={} calls={} contactsMax={} iterationsMax={} normalResidualMax={} predictedDvMax={} predictedDwMax={} solverMs={}",
         c.dimension,
         c.body,
         w.solverCalls,
         w.solverContacts,
         w.solverIterations,
         w.solverResidual,
         w.maxPredictedDv,
         w.maxPredictedDw,
         (double)w.solverNanos / 1000000.0
      );
      return report.toString();
   }

   private static void line(StringBuilder output, String pattern, Object... args) {
      output.append(MessageFormatter.arrayFormat(pattern, args).getMessage()).append('\n');
   }

   public static synchronized void clear() {
      WINDOWS.clear();
      OUTPUT.close();
   }

   private TrackDiagnostics() {
   }

   private static record Context(
      long capturedMillis, String dimension, int body, double mass, Vector3d com, Vector3d inertia, Vector3d linear, Vector3d angular, double wallMs
   ) {
   }

   private static final class WheelWindow {
      boolean drive;
      long hits;
      long contacts;
      long zeroSupport;
      double minExt = Double.POSITIVE_INFINITY;
      double maxExt = Double.NEGATIVE_INFINITY;
      double minRaw = Double.POSITIVE_INFINITY;
      double maxRaw = Double.NEGATIVE_INFINITY;
      double radius;
      double width;
      double travel;
      double maxCompression;
      double normalSum;
      double maxSpring;
      double minDamping = Double.POSITIVE_INFINITY;
      double maxDamping = Double.NEGATIVE_INFINITY;
      double compressionLimit;
      double downOffset;
      double spread;
      double margin;
      double forwardMu;
      double sideMu;
      double stopSum;
      double penetrationMax;
      double forwardImpulseMax;
      double sideImpulseMax;
      long stops;
      long atCap;
   }

   private static final class Window {
      long start = System.nanoTime();
      long calls;
      long nanos;
      long contacts;
      long limits;
      long queries;
      double minExtension = Double.POSITIVE_INFINITY;
      double maxExtension = Double.NEGATIVE_INFINITY;
      double maxImpulse;
      double maxRpm;
      double maxForward;
      double maxSide;
      double minDt = Double.POSITIVE_INFINITY;
      double maxDt;
      double normalSum;
      long reachable;
      long zeroSupport;
      long solverNanos;
      long solverCalls;
      int solverContacts;
      int solverIterations;
      double solverResidual;
      double maxPredictedDv;
      double maxPredictedDw;
      final Set<Long> mounts = new HashSet<>();
      final Set<Long> roadWheels = new HashSet<>();
      final Set<Long> driveWheels = new HashSet<>();
      final Map<Long, TrackDiagnostics.WheelWindow> wheels = new LinkedHashMap<>();
      BlockPos lastMount = BlockPos.ZERO;
      BlockPos lastContact = BlockPos.ZERO;
   }
}
