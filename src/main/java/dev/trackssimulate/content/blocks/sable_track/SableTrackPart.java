// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.content.blocks.sable_track;

import dev.trackssimulate.index.TracksBlocks;
import net.minecraft.world.item.ItemStack;

public enum SableTrackPart {
   GUIDE(SableTrackRole.GUIDE, 0.4, 0, 0, 0, 0, 1),
   NONE(SableTrackRole.MOUNT, 0.0, 0.0, 0.0, 0.0, 0, 1.0F),
   SMALL_SUSPENSION(SableTrackRole.SUSPENSION, 0.55, 0.6, 1.05, 1.0, 2, 0.75F),
   SUSPENSION(SableTrackRole.SUSPENSION, 0.75, 0.75, 1.35, 1.05, 3, 1.0F),
   LARGE_SUSPENSION(SableTrackRole.SUSPENSION, 0.95, 0.95, 1.65, 1.15, 4, 1.25F),
   SMALL_DRIVE(SableTrackRole.DRIVE, 0.6, 0.0, 1.5, 1.15, 2, 0.8F),
   DRIVE(SableTrackRole.DRIVE, 0.8, 0.0, 1.9, 1.25, 2, 1.0F),
   LARGE_DRIVE(SableTrackRole.DRIVE, 1.0, 0.0, 2.35, 1.35, 3, 1.25F);

   private final SableTrackRole role;
   private final double radius;
   private final double suspensionTravel;
   private final double driveMultiplier;
   private final double sideGripMultiplier;
   private final int contactSamples;
   private final float visualScale;

   private SableTrackPart(
      final SableTrackRole role,
      final double radius,
      final double suspensionTravel,
      final double driveMultiplier,
      final double sideGripMultiplier,
      final int contactSamples,
      final float visualScale
   ) {
      this.role = role;
      this.radius = radius;
      this.suspensionTravel = suspensionTravel;
      this.driveMultiplier = driveMultiplier;
      this.sideGripMultiplier = sideGripMultiplier;
      this.contactSamples = contactSamples;
      this.visualScale = visualScale;
   }

   public static SableTrackPart fromStack(ItemStack stack) {
      if (stack.is(TracksBlocks.TRACK_GUIDE_WHEEL.asItem())) return GUIDE;
      if (stack.is(TracksBlocks.SMALL_SUSPENSION_TRACK.asItem())) {
         return SMALL_SUSPENSION;
      } else if (stack.is(TracksBlocks.SUSPENSION_TRACK.asItem())) {
         return SUSPENSION;
      } else if (stack.is(TracksBlocks.LARGE_SUSPENSION_TRACK.asItem())) {
         return LARGE_SUSPENSION;
      } else if (stack.is(TracksBlocks.SMALL_TRACK_DRIVE_WHEEL.asItem())) {
         return SMALL_DRIVE;
      } else if (stack.is(TracksBlocks.TRACK_DRIVE_WHEEL.asItem())) {
         return DRIVE;
      } else {
         return stack.is(TracksBlocks.LARGE_TRACK_DRIVE_WHEEL.asItem()) ? LARGE_DRIVE : NONE;
      }
   }

   public SableTrackRole role() {
      return this.role;
   }

   public boolean isWheel() { return role != SableTrackRole.MOUNT; }

   public boolean appliesPhysics() {
      return this.role.appliesPhysics();
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

   public float visualScale() {
      return this.visualScale;
   }
}
