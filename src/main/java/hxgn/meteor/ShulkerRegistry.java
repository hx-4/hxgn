package hxgn.meteor;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Scans player inventory for all shulker boxes and assigns each a stable, deduplicated
 * display name so callers can refer to specific shulkers by name.
 *
 * Dedup rule (slot order, 0..size-1):
 *   first occurrence of a base name → resolvedName = baseName
 *   nth duplicate (n ≥ 2)          → resolvedName = baseName + " " + n
 *   e.g. three unnamed "Shulker Box" entries → "Shulker Box", "Shulker Box 2", "Shulker Box 3"
 */
public final class ShulkerRegistry {

    public record Entry(String resolvedName, int slot, ItemStack stack) {}

    /**
     * Returns one {@link Entry} per shulker box in the player's inventory,
     * ordered by slot (0 first), with names deduplicated as described above.
     */
    public static List<Entry> listAll(ClientPlayerEntity player) {
        List<Entry> result = new ArrayList<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isIn(ItemTags.SHULKER_BOXES)) continue;
            String baseName = stack.getName().getString();
            int count = occurrences.merge(baseName, 1, Integer::sum);
            String resolvedName = count == 1 ? baseName : baseName + " " + count;
            result.add(new Entry(resolvedName, i, stack));
        }
        return result;
    }

    /**
     * Finds the first shulker whose resolved name equals {@code resolvedName} and whose
     * contents include at least one item matching {@code contentFilter}.
     * Returns empty if no matching shulker is found.
     */
    public static Optional<Entry> find(ClientPlayerEntity player, String resolvedName,
                                       Predicate<ItemStack> contentFilter) {
        for (Entry e : listAll(player)) {
            if (!e.resolvedName().equals(resolvedName)) continue;
            ContainerComponent c = e.stack().get(DataComponentTypes.CONTAINER);
            if (c == null) continue;
            if (c.streamNonEmpty().anyMatch(contentFilter)) return Optional.of(e);
        }
        return Optional.empty();
    }

    private ShulkerRegistry() {}
}
