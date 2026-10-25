// Derived from qwxon Create Tracks 1.0.1 (MIT); see THIRD_PARTY_NOTICES.md.
package dev.trackssimulate.index;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.ItemEntry;
import dev.trackssimulate.Tracks;
import dev.trackssimulate.content.items.SuspensionKeyItem;

public class TracksItems {
   private static final CreateRegistrate REGISTRATE = Tracks.getRegistrate();
   public static final ItemEntry<SuspensionKeyItem> SUSPENSION_KEY = REGISTRATE.item("suspension_key", SuspensionKeyItem::new).register();

   public static void init() {
   }
}
