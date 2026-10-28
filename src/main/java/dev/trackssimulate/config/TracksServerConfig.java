// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;

public class TracksServerConfig {
   public static final ModConfigSpec SPEC;
   public static final BooleanValue PHYSICS_LOG;
   public static final ModConfigSpec.DoubleValue PASSIVE_ROLLING_MASS;

   static {
      Builder builder = new Builder();
      builder.push("server");
      PHYSICS_LOG = builder.comment("Aggregate physics diagnostics once per vehicle per wall-clock second; bounded background writer saves logs/trackssimulate-physics.log (8 MiB x 4 files).")
         .define("physicsLog", true);
      PASSIVE_ROLLING_MASS=builder.comment("Equivalent rolling mass in kg per selected wheel for loops WITHOUT drive wheels. Lumped rotational inertia only; adds no chassis weight. Not individual track plate masses.")
         .defineInRange("passiveRollingMassPerWheel",1.0,0.01,1000.0);
      builder.pop();
      SPEC = builder.build();
   }
}
