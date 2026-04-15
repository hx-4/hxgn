package hxgn.meteor;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;

public class RepairHandler {

    // Container slot IDs in the player's inventory screen handler
    private static final int[] ARMOR_SLOT_IDS = {5, 6, 7, 8}; // HEAD, CHEST, LEGS, FEET
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final int OFFHAND_SLOT_ID = 45;

    private final ClickDispatcher dispatcher;

    public RepairHandler(ClickDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** Only slots 9–44 (inventory + hotbar) are valid swap sources.
     *  Armor slots (5–8) and offhand (45) are never moved from here — they are destinations only. */
    private static boolean isMovableSlot(int id) {
        return id >= 9 && id <= 44;
    }

    private static void announceRepaired(ClientPlayerEntity player, ItemStack item) {
        player.sendMessage(Text.literal("[CleverMend] " + item.getName().getString() + " fully repaired!"), true);
    }

    public void handleArmor(ClientPlayerEntity player, List<Slot> mendingPieces,
                            RegistryEntry<Enchantment> mending, boolean announce, Consumer<String> dbg) {
        List<Slot> movable = mendingPieces.stream().filter(s -> isMovableSlot(s.id)).toList();
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            final int armorSlotId = ARMOR_SLOT_IDS[i];
            final EquipmentSlot slot = ARMOR_SLOTS[i];

            java.util.Optional<Slot> candidateOpt = movable.stream()
                    .filter(s -> player.getPreferredEquipmentSlot(s.getStack()) == slot)
                    .findFirst();

            ItemStack worn = player.playerScreenHandler.getSlot(armorSlotId).getStack();
            if (candidateOpt.isPresent()) {
                Slot candidate = candidateOpt.get();
                ItemStack toWear = candidate.getStack();
                // Only equip the inventory piece when the currently worn one is fully repaired
                if (toWear.getDamage() > 0 && worn.getDamage() == 0) {
                    dbg.accept(String.format("armor %s: worn=%s(dmg=%d) → swapping in %s(dmg=%d,s=%d)",
                            slot.getName(), worn.getName().getString(), worn.getDamage(),
                            toWear.getName().getString(), toWear.getDamage(), candidate.id));
                    if (announce && !worn.isEmpty() && worn.isDamageable()) {
                        announceRepaired(player, worn);
                    }

                    dispatcher.enqueueSwap(candidate.id, armorSlotId);
                }
            } else if (!worn.isEmpty() && worn.getDamage() == 0
                    && EnchantmentHelper.getLevel(mending, worn) > 0) {
                // Repaired mending piece with no damaged replacement — unequip back to inventory,
                // but only if there's another copy of the same item in the inventory (even undamaged).
                // If it's the last one, keep it equipped.
                boolean hasDuplicate = player.playerScreenHandler.slots.stream()
                        .anyMatch(s -> isMovableSlot(s.id) && s.getStack().getItem() == worn.getItem());
                if (!hasDuplicate) continue;

                dbg.accept(String.format("armor %s: %s repaired, no replacement, unequipping",
                        slot.getName(), worn.getName().getString()));
                if (announce) announceRepaired(player, worn);
                dispatcher.enqueueClick(armorSlotId, true);
            }
        }
    }

    public void handleOffhand(ClientPlayerEntity player, List<Slot> mendingPieces, List<Slot> mendingTools,
                              boolean offhandEnabled, RegistryEntry<Enchantment> mendingHolder,
                              boolean prioritizeTools, boolean announce, Consumer<String> dbg) {
        if (!offhandEnabled) return;

        ItemStack currentOffhand = player.playerScreenHandler.getSlot(OFFHAND_SLOT_ID).getStack();

        // Only inventory/hotbar items are valid offhand candidates.
        // Items in armor slots (5–8) are already being repaired there; don't pull them out.
        List<Slot> invPieces = mendingPieces.stream().filter(s -> isMovableSlot(s.id)).toList();
        List<Slot> invTools  = mendingTools.stream().filter(s -> isMovableSlot(s.id)).toList();

        final Slot candidate;
        boolean toolFirst = prioritizeTools && !invTools.isEmpty() && invTools.get(0).getStack().getDamage() > 0;
        if (toolFirst) {
            candidate = invTools.get(0);
        } else if (!invPieces.isEmpty() && invPieces.get(0).getStack().getDamage() > 0) {
            candidate = invPieces.get(0);
        } else if (!invTools.isEmpty() && invTools.get(0).getStack().getDamage() > 0) {
            candidate = invTools.get(0);
        } else {
            // No damaged candidates. If offhand holds a now-repaired mending item, unequip it.
            if (!currentOffhand.isEmpty() && currentOffhand.isDamageable()
                    && currentOffhand.getDamage() == 0
                    && EnchantmentHelper.getLevel(mendingHolder, currentOffhand) > 0) {
                dbg.accept("offhand: repaired, no more damaged candidates, unequipping");
                if (announce) announceRepaired(player, currentOffhand);
                dispatcher.enqueueClick(OFFHAND_SLOT_ID, true);
            }
            return;
        }

        boolean offhandHasMendingItem = !currentOffhand.isEmpty()
                && currentOffhand.isDamageable()
                && EnchantmentHelper.getLevel(mendingHolder, currentOffhand) > 0;

        if (offhandHasMendingItem) {
            if (currentOffhand.getDamage() == 0) {
                if (announce) announceRepaired(player, currentOffhand);
                // Direct swap works even when inventory is full (no free slot needed)
                dbg.accept("offhand: done repairing, swapping with candidate");
                dispatcher.enqueueSwap(candidate.id, OFFHAND_SLOT_ID);
            } else if (toolFirst && currentOffhand.getDamage() <= candidate.getStack().getDamage()) {
                // A more-damaged tool has appeared; upgrade the offhand
                dbg.accept("offhand: upgrading to more-damaged tool");
                dispatcher.enqueueSwap(candidate.id, OFFHAND_SLOT_ID);
            }
            return;
        }

        if (ItemStack.areItemsAndComponentsEqual(currentOffhand, candidate.getStack())) {
            return;
        }

        dbg.accept("offhand: swapping in " + candidate.getStack().getName().getString());
        dispatcher.enqueueSwap(candidate.id, OFFHAND_SLOT_ID);
    }

}
