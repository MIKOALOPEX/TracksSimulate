// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;

public class TracksServerConfig {
   public static final ModConfigSpec SPEC;
   public static final BooleanValue PHYSICS_LOG;
   public static final ModConfigSpec.DoubleValue PASSIVE_ROLLING_MASS;
   public static final ModConfigSpec.IntValue SURFACE_PROBES,SURFACE_CONTACTS;

   static {
      Builder builder = new Builder();
      builder.push("server");
      PHYSICS_LOG = builder.comment("Aggregate physics diagnostics once per vehicle per wall-clock second; bounded background writer saves logs/trackssimulate-physics.log (8 MiB x 4 files).")
         .define("physicsLog", true);
      PASSIVE_ROLLING_MASS=builder.comment("Equivalent rolling mass in kg per selected wheel for loops WITHOUT drive wheels. Lumped rotational inertia only; adds no chassis weight. Not individual track plate masses.")
         .defineInRange("passiveRollingMassPerWheel",1.0,0.01,1000.0);
      SURFACE_PROBES=builder.comment("Maximum scheduled full-surface probes per loop per game tick. Expired finite hit faces may each be recast once per physics substep, bounded by this same probe count. All orientations are eligible; lower values reduce spatial coverage on long/wide loops. Budget reductions are logged.")
         .defineInRange("surfaceProbesPerLoop",512,64,4096);
      SURFACE_CONTACTS=builder.comment("Maximum additional wheel/belt contact patches per vehicle per physics step after road-support exclusion and overlap merging.")
         .defineInRange("surfaceContactsPerBody",64,8,256);
      builder.pop();
      SPEC = builder.build();
   }
}
