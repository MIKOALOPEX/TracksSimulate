// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.blocks.sable_track;

public enum SableTrackRole {
   GUIDE(0.4, 0.0, 0.0, 0.0, 0, true),
   MOUNT(0.0, 0.0, 0.0, 0.0, 0, false),
   SUSPENSION(0.75, 0.75, 0.0, 1.05, 3, true),
   DRIVE(0.8, 0.55, 1.9, 1.25, 2, true);

   private final double radius;
   private final double suspensionTravel;
   private final double driveMultiplier;
   private final double sideGripMultiplier;
   private final int contactSamples;
   private final boolean appliesPhysics;

   private SableTrackRole(
      final double radius,
      final double suspensionTravel,
      final double driveMultiplier,
      final double sideGripMultiplier,
      final int contactSamples,
      final boolean appliesPhysics
   ) {
      this.radius = radius;
      this.suspensionTravel = suspensionTravel;
      this.driveMultiplier = driveMultiplier;
      this.sideGripMultiplier = sideGripMultiplier;
      this.contactSamples = contactSamples;
      this.appliesPhysics = appliesPhysics;
   }

   public double radius() {
      return this.radius;
   }

   public double suspensionTravel() {
      return this.suspensionTravel;
   }

   public double driveMultiplier() {
      return this.driveMultiplier;
   }

   public double sideGripMultiplier() {
      return this.sideGripMultiplier;
   }

   public int contactSamples() {
      return this.contactSamples;
   }

   public boolean appliesPhysics() {
      return this.appliesPhysics;
   }
}
