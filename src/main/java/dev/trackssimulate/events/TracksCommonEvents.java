// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.events;

import dev.trackssimulate.content.blocks.sable_track.SableTrackBlockEntity;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;

public class TracksCommonEvents {
   public static void physicsTick(SubLevelPhysicsSystem physicsSystem, double timeStep) {
      ServerLevel level = physicsSystem.getLevel();
      SableTrackBlockEntity.applyAllBatchedForces(level, timeStep);
   }
}
