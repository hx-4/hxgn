package hxgn.meteor;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.Slot;

import java.util.Comparator;
import java.util.List;

public final class MendingScanner {

    private MendingScanner() {}

    public static RegistryEntry<Enchantment> resolveMending(ClientWorld world) {
        return world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.MENDING.getValue()).orElseThrow();
    }

    public static List<Slot> scan(ClientPlayerEntity player, ClientWorld world) {
        final var mending = resolveMending(world);

        return player.playerScreenHandler.slots.stream()
                .filter(s -> !s.getStack().isEmpty())
                .filter(s -> {
                    EquipmentSlot es = player.getPreferredEquipmentSlot(s.getStack());
                    return es == EquipmentSlot.HEAD || es == EquipmentSlot.CHEST
                            || es == EquipmentSlot.LEGS || es == EquipmentSlot.FEET;
                })
                .filter(s -> EnchantmentHelper.getLevel(mending, s.getStack()) > 0)
                .sorted(Comparator.comparingInt((Slot s) -> s.getStack().getDamage()).reversed())
                .toList();
    }

    public static List<Slot> scanTools(ClientPlayerEntity player, ClientWorld world) {
        final var mending = resolveMending(world);
        // hotbar slots in the playerScreenHandler are at container IDs 36-44 (index + 36)
        final int selectedContainerSlotId = 36 + player.getInventory().selectedSlot;

        return player.playerScreenHandler.slots.stream()
                .filter(s -> !s.getStack().isEmpty())
                .filter(s -> s.id != selectedContainerSlotId)
                .filter(s -> s.getStack().isDamageable())
                .filter(s -> {
                    EquipmentSlot es = player.getPreferredEquipmentSlot(s.getStack());
                    return es != EquipmentSlot.HEAD && es != EquipmentSlot.CHEST
                            && es != EquipmentSlot.LEGS && es != EquipmentSlot.FEET;
                })
                .filter(s -> EnchantmentHelper.getLevel(mending, s.getStack()) > 0)
                .sorted(Comparator.comparingInt((Slot s) -> s.getStack().getDamage()).reversed())
                .toList();
    }
}
