// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate;

import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.item.TooltipModifier;
import com.simibubi.create.foundation.item.ItemDescription.Modifier;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.trackssimulate.events.TracksCommonEvents;
import dev.trackssimulate.index.TracksBlockEntityTypes;
import dev.trackssimulate.index.TracksBlocks;
import dev.trackssimulate.index.TracksItems;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import com.simibubi.create.foundation.data.CreateRegistrate;
import dev.simulated_team.simulated.util.SimColors;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Tracks {
   public static final String MOD_ID = "trackssimulate";
   public static final Logger LOGGER = LoggerFactory.getLogger("trackssimulate");
   // Items belong to our own creative tab. SimulatedRegistrate would also enroll
   // them in Simulated's resource-defined sections, which this mod does not provide.
   private static final NonNullSupplier<CreateRegistrate> REGISTRATE = NonNullSupplier.lazy(
      () -> CreateRegistrate.create(MOD_ID).defaultCreativeTab((ResourceKey)null)
   );

   public static void init() {
      setTooltips();
      TracksBlocks.init();
      TracksBlockEntityTypes.init();
      TracksItems.init();
      SableEventPlatform.INSTANCE.onPhysicsTick(TracksCommonEvents::physicsTick);
   }

   private static void setTooltips() {
      getRegistrate().setTooltipModifierFactory(item -> {
         Rarity rarity = item.getDefaultInstance().getRarity();
         Palette color = Palette.STANDARD_CREATE;
         if (rarity == Rarity.EPIC) {
            color = new Palette(TooltipHelper.styleFromColor(SimColors.EPIC_OURPLE), TooltipHelper.styleFromColor(rarity.color()));
         }

         return new Modifier(item, color).andThen(TooltipModifier.mapNull(KineticStats.create(item)));
      });
   }

   public static CreateRegistrate getRegistrate() {
      return REGISTRATE.get();
   }

   public static ResourceLocation path(String path) {
      return ResourceLocation.tryBuild("trackssimulate", path);
   }
}
