// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.mixin_interface;

public interface WheelMountOffsetAccess {
   double tracks$adjustLateralOffset(int var1);

   double tracks$adjustLongitudinalOffset(int var1);

   double tracks$adjustHeightOffset(int var1);

   double tracks$adjustTuning(String var1, int var2);

   void tracks$resetTuning();

   double tracks$getTuning(String var1);

   double tracks$getLerpedLateralOffset(float var1);

   double tracks$getLerpedLongitudinalOffset(float var1);

   double tracks$getLerpedHeightOffset(float var1);

   double tracks$getLerpedYaw(float var1);

   boolean tracks$isVisualSuspensionHidden();

   void tracks$toggleVisualSuspensionHidden();

   int tracks$getClientSteeringSignalLeft();

   int tracks$getClientSteeringSignalRight();
}
