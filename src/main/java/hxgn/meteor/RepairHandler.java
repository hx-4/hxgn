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

    public void handleArmor(ClientPlayerEntity player, List<Slot> mendingPieces, boolean announce, Consumer<String> dbg) {
        List<Slot> movable = mendingPieces.stream().filter(s -> isMovableSlot(s.id)).toList();
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            final int armorSlotId = ARMOR_SLOT_IDS[i];
            final EquipmentSlot slot = ARMOR_SLOTS[i];

            movable.stream()
                    .filter(s -> player.getPreferredEquipmentSlot(s.getStack()) == slot)
                    .findFirst()
                    .ifPresent(candidate -> {
                        ItemStack worn = player.playerScreenHandler.getSlot(armorSlotId).getStack();
                        ItemStack toWear = candidate.getStack();
                        // Only equip the inventory piece when the currently worn one is fully repaired
                        if (toWear.getDamage() > 0 && worn.getDamage() == 0) {
                            dbg.accept(String.format("armor %s: worn=%s(dmg=%d) → swapping in %s(dmg=%d,s=%d)",
                                    slot.getName(), worn.getName().getString(), worn.getDamage(),
                                    toWear.getName().getString(), toWear.getDamage(), candidate.id));
                            if (announce && !worn.isEmpty() && worn.isDamageable()) {
                                player.sendMessage(
                                        Text.literal("[AutoMender] " + worn.getName().getString() + " fully repaired!"), true);
                            }
                            dispatcher.enqueueSwap(candidate.id, armorSlotId);
                        }
                    });
        }
    }

    public void handleOffhand(ClientPlayerEntity player, List<Slot> mendingPieces, List<Slot> mendingTools,
                              boolean offhandEnabled, RegistryEntry<Enchantment> mendingHolder,
                              boolean prioritizeTools, boolean announce, Consumer<String> dbg) {
        if (!offhandEnabled) return;

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
            return;
        }

        dbg.accept(String.format("offhand: candidate=%s(dmg=%d,s=%d)",
                candidate.getStack().getName().getString(), candidate.getStack().getDamage(), candidate.id));

        ItemStack currentOffhand = player.playerScreenHandler.getSlot(OFFHAND_SLOT_ID).getStack();
        boolean offhandHasMendingItem = !currentOffhand.isEmpty()
                && currentOffhand.isDamageable()
                && EnchantmentHelper.getLevel(mendingHolder, currentOffhand) > 0;

        if (offhandHasMendingItem) {
            dbg.accept(String.format("offhand: current=%s(dmg=%d)", currentOffhand.getName().getString(), currentOffhand.getDamage()));
            if (currentOffhand.getDamage() == 0) {
                if (announce) {
                    player.sendMessage(
                            Text.literal("[AutoMender] " + currentOffhand.getName().getString() + " fully repaired!"), true);
                }
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
            dbg.accept("offhand: candidate already in offhand");
            return;
        }

        dbg.accept("offhand: swapping in " + candidate.getStack().getName().getString());
        dispatcher.enqueueSwap(candidate.id, OFFHAND_SLOT_ID);
    }

}
