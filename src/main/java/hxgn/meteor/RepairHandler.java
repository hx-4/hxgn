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

    public void handleArmor(ClientPlayerEntity player, List<Slot> mendingPieces, boolean announce) {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            final int armorSlotId = ARMOR_SLOT_IDS[i];
            final EquipmentSlot slot = ARMOR_SLOTS[i];

            mendingPieces.stream()
                    .filter(s -> player.getPreferredEquipmentSlot(s.getStack()) == slot)
                    .findFirst()
                    .ifPresent(candidate -> {
                        ItemStack worn = player.playerScreenHandler.getSlot(armorSlotId).getStack();
                        ItemStack toWear = candidate.getStack();

                        if (toWear.getDamage() > 0 && worn.getDamage() == 0) {
                            if (announce && !worn.isEmpty() && worn.isDamageable()) {
                                player.sendMessage(
                                        Text.literal("[AutoMender] " + worn.getName().getString() + " fully repaired!"), true);
                            }
                            swapWithArmorSlot(candidate, armorSlotId);
                        }
                    });
        }
    }

    public void handleOffhand(ClientPlayerEntity player, List<Slot> mendingPieces, List<Slot> mendingTools,
                              boolean offhandEnabled, RegistryEntry<Enchantment> mendingHolder,
                              boolean prioritizeTools, boolean announce) {
        if (!offhandEnabled) return;

        final Slot candidate;
        boolean toolFirst = prioritizeTools && !mendingTools.isEmpty() && mendingTools.get(0).getStack().getDamage() > 0;
        if (toolFirst) {
            candidate = mendingTools.get(0);
        } else if (mendingPieces.size() >= 2 && mendingPieces.get(1).getStack().getDamage() > 0) {
            candidate = mendingPieces.get(1);
        } else if (!mendingTools.isEmpty() && mendingTools.get(0).getStack().getDamage() > 0) {
            candidate = mendingTools.get(0);
        } else {
            return;
        }

        ItemStack currentOffhand = player.playerScreenHandler.getSlot(OFFHAND_SLOT_ID).getStack();

        boolean offhandHasMendingItem = !currentOffhand.isEmpty()
                && currentOffhand.isDamageable()
                && EnchantmentHelper.getLevel(mendingHolder, currentOffhand) > 0;

        if (offhandHasMendingItem) {
            if (currentOffhand.getDamage() == 0) {
                if (announce) {
                    player.sendMessage(
                            Text.literal("[AutoMender] " + currentOffhand.getName().getString() + " fully repaired!"), true);
                }
                if (hasFreeInventorySlot(player)) {
                    dispatcher.enqueueClick(OFFHAND_SLOT_ID, true);
                }
            } else if (candidate.id == OFFHAND_SLOT_ID) {
                return;
            } else if (toolFirst) {
                if (hasFreeInventorySlot(player)) {
                    dispatcher.enqueueClick(OFFHAND_SLOT_ID, true);
                }
            } else {
                return;
            }
        }

        dispatcher.enqueueSwap(candidate.id, OFFHAND_SLOT_ID);
    }

    private void swapWithArmorSlot(Slot source, int armorSlotId) {
        dispatcher.enqueueSwap(source.id, armorSlotId);
    }

    private boolean hasFreeInventorySlot(ClientPlayerEntity player) {
        return player.getInventory().getEmptySlot() != -1;
    }
}
