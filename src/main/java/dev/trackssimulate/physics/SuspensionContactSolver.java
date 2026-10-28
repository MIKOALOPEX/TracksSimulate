// Coupled suspension impulses, travel stops, friction and passive belt motion.
package dev.trackssimulate.physics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

public final class SuspensionContactSolver {
   public static final int MAX_ITERATIONS = 64;

   public static SuspensionContactSolver.Result solve(List<SuspensionContactSolver.Contact> contacts) {
      if (contacts.isEmpty()) {
         return new SuspensionContactSolver.Result(0, 0.0, 0.0, 0.0);
      } else {
         double[] delta = new double[6];
         LinkedHashMap<SuspensionContactSolver.FreeBelt, Double> freeBelts = new LinkedHashMap<>();

         for (SuspensionContactSolver.Contact c : contacts) {
            c.normal.impulse = c.forward.impulse = c.side.impulse = 0.0;
            if (c.stop != null) {
               c.stop.impulse = 0.0;
            }

            if (c.forward.belt != null && c.forward.belt.brake > 0.0) {
               freeBelts.put(c.forward.belt, Double.valueOf(0.0));
            }
         }

         for (SuspensionContactSolver.FreeBelt belt : freeBelts.keySet()) {
            belt.brakeImpulse = 0.0;
         }

         for (SuspensionContactSolver.Contact c : contacts) {
            double seed = c.normal.softness > 0.0 ? c.normal.target / c.normal.softness : 0.0;
            c.normal.setImpulse(Math.max(0.0, Math.min(c.maxNormal, seed)), delta);
         }

         int iterations;
         for (iterations = 0; iterations < 64; iterations++) {
            double change = 0.0;

            for (int step = 0; step < contacts.size(); step++) {
               SuspensionContactSolver.Contact c = contacts.get((iterations & 1) == 0 ? step : contacts.size() - 1 - step);
               change = Math.max(change, c.normal.solve(0.0, c.maxNormal, delta));
               if (c.stop != null) {
                  change = Math.max(change, c.stop.solve(0.0, Double.POSITIVE_INFINITY, delta));
               }

               double forwardLimit = c.forwardFriction * c.supportImpulse();
               double sideLimit = c.sideFriction * c.supportImpulse();
               change = Math.max(change, c.forward.solve(-forwardLimit, forwardLimit, delta));
               change = Math.max(change, c.side.solve(-sideLimit, sideLimit, delta));
               double length = Math.hypot(forwardLimit > 0.0 ? c.forward.impulse / forwardLimit : 0.0, sideLimit > 0.0 ? c.side.impulse / sideLimit : 0.0);
               if (length > 1.0) {
                  double scale = 1.0 / length;
                  change = Math.max(change, c.forward.setImpulse(c.forward.impulse * scale, delta));
                  change = Math.max(change, c.side.setImpulse(c.side.impulse * scale, delta));
               }
            }

            if (!freeBelts.isEmpty()) {
               freeBelts.replaceAll((beltx, budget) -> 0.0);

               for (SuspensionContactSolver.Contact cx : contacts) {
                  if (cx.forward.belt != null && cx.forward.belt.brake > 0.0) {
                     freeBelts.merge(cx.forward.belt, Double.valueOf(cx.forwardFriction * cx.supportImpulse() * cx.forward.belt.brake), Double::sum);
                  }
               }

               for (Entry<SuspensionContactSolver.FreeBelt, Double> entry : freeBelts.entrySet()) {
                  SuspensionContactSolver.FreeBelt belt = entry.getKey();
                  double limit = entry.getValue();
                  double next = Math.max(-limit, Math.min(limit, belt.brakeImpulse - belt.speed / belt.inverseMass));
                  double diff = next - belt.brakeImpulse;
                  belt.speed = belt.speed + diff * belt.inverseMass;
                  belt.brakeImpulse = next;
                  change = Math.max(change, Math.abs(diff));
               }
            }

            if (iterations >= 7 && change < 1.0E-6) {
               iterations++;
               break;
            }
         }

         double residual = 0.0;

         for (SuspensionContactSolver.Contact cxx : contacts) {
            residual = Math.max(residual, cxx.normal.residual(0.0, cxx.maxNormal, delta));
            if (cxx.stop != null) {
               residual = Math.max(residual, cxx.stop.residual(0.0, Double.POSITIVE_INFINITY, delta));
            }
         }

         return new SuspensionContactSolver.Result(
            iterations,
            residual,
            Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]),
            Math.sqrt(delta[3] * delta[3] + delta[4] * delta[4] + delta[5] * delta[5])
         );
      }
   }

   private SuspensionContactSolver() {
   }

   public static record Contact(
      SuspensionContactSolver.Row normal,
      SuspensionContactSolver.Row stop,
      SuspensionContactSolver.Row forward,
      SuspensionContactSolver.Row side,
      double maxNormal,
      double forwardFriction,
      double sideFriction
   ) {
      public Contact(
         SuspensionContactSolver.Row normal,
         SuspensionContactSolver.Row stop,
         SuspensionContactSolver.Row forward,
         SuspensionContactSolver.Row side,
         double maxNormal,
         double forwardFriction,
         double sideFriction
      ) {
         if (maxNormal >= 0.0
            && Double.isFinite(maxNormal)
            && forwardFriction >= 0.0
            && Double.isFinite(forwardFriction)
            && sideFriction >= 0.0
            && Double.isFinite(sideFriction)) {
            this.normal = normal;
            this.stop = stop;
            this.forward = forward;
            this.side = side;
            this.maxNormal = maxNormal;
            this.forwardFriction = forwardFriction;
            this.sideFriction = sideFriction;
         } else {
            throw new IllegalArgumentException("Invalid contact limits");
         }
      }

      public Contact(
         SuspensionContactSolver.Row normal, SuspensionContactSolver.Row forward, SuspensionContactSolver.Row side, double maxNormal, double friction
      ) {
         this(normal, null, forward, side, maxNormal, friction, friction);
      }

      public double supportImpulse() {
         return this.normal.impulse + (this.stop == null ? 0.0 : this.stop.impulse);
      }
   }

   public static final class FreeBelt {
      public final double inverseMass;
      public double speed;
      private double brake;
      private double brakeImpulse;

      public FreeBelt(double mass, double speed) {
         if (mass > 0.0 && Double.isFinite(mass) && Double.isFinite(speed)) {
            this.inverseMass = 1.0 / mass;
            this.speed = speed;
         } else {
            throw new IllegalArgumentException("Invalid rolling state");
         }
      }

      public void brake(double fraction) {
         this.brake = Math.max(0.0, Math.min(1.0, fraction));
      }

      public double brake() {
         return this.brake;
      }
   }

   public static record Result(int iterations, double residual, double maxDeltaSpeed, double maxDeltaAngularSpeed) {
   }

   public static final class Row {
      public final double[] j = new double[6];
      public final double[] response = new double[6];
      public final double velocity;
      public final double target;
      public final double softness;
      public final double diagonal;
      private final double massDiagonal;
      public double impulse;
      private SuspensionContactSolver.FreeBelt belt;
      private double beltJacobian;
      private boolean lockedRolling;

      public void lockRolling() {
         this.lockedRolling = true;
      }

      public void couple(SuspensionContactSolver.FreeBelt belt, double winding) {
         if (winding != 1.0 && winding != -1.0) {
            throw new IllegalArgumentException("Invalid winding");
         } else {
            this.belt = belt;
            this.beltJacobian = -winding;
         }
      }

      private double effectiveSoftness() {
         return this.lockedRolling ? 0.0 : this.softness;
      }

      private double effectiveDiagonal() {
         return (this.lockedRolling ? this.massDiagonal : this.diagonal) + (this.belt == null ? 0.0 : this.belt.inverseMass);
      }

      public Row(
         double nx,
         double ny,
         double nz,
         double rx,
         double ry,
         double rz,
         double inverseMass,
         double[] inverseInertia,
         double velocity,
         double target,
         double softness
      ) {
         this.j[0] = nx;
         this.j[1] = ny;
         this.j[2] = nz;
         this.j[3] = ry * nz - rz * ny;
         this.j[4] = rz * nx - rx * nz;
         this.j[5] = rx * ny - ry * nx;

         for (int i = 0; i < 3; i++) {
            this.response[i] = this.j[i] * inverseMass;
            this.response[i + 3] = inverseInertia[i * 3] * this.j[3] + inverseInertia[i * 3 + 1] * this.j[4] + inverseInertia[i * 3 + 2] * this.j[5];
         }

         this.velocity = velocity;
         this.target = target;
         this.softness = softness;
         double effective = 0.0;

         for (int i = 0; i < 6; i++) {
            effective += this.j[i] * this.response[i];
         }

         this.massDiagonal = effective;
         this.diagonal = effective + softness;
         if (!(this.diagonal > 0.0) || !Double.isFinite(this.diagonal) || softness < 0.0 || !Double.isFinite(velocity) || !Double.isFinite(target)) {
            throw new IllegalArgumentException("Invalid suspension contact row");
         }
      }

      private double predicted(double[] delta) {
         double value = this.velocity;

         for (int i = 0; i < 6; i++) {
            value += this.j[i] * delta[i];
         }

         if (this.belt != null) {
            value += this.beltJacobian * this.belt.speed;
         }

         return value;
      }

      private double setImpulse(double value, double[] delta) {
         double change = value - this.impulse;
         this.impulse = value;

         for (int i = 0; i < 6; i++) {
            delta[i] += this.response[i] * change;
         }

         if (this.belt != null) {
            this.belt.speed = this.belt.speed + this.belt.inverseMass * this.beltJacobian * change;
         }

         return Math.abs(change);
      }

      private double solve(double min, double max, double[] delta) {
         double value = this.impulse + (this.target - this.predicted(delta) - this.effectiveSoftness() * this.impulse) / this.effectiveDiagonal();
         return this.setImpulse(Math.max(min, Math.min(max, value)), delta);
      }

      private double residual(double min, double max, double[] delta) {
         double value = this.impulse + (this.target - this.predicted(delta) - this.effectiveSoftness() * this.impulse) / this.effectiveDiagonal();
         return Math.abs(this.impulse - Math.max(min, Math.min(max, value)));
      }
   }
}
