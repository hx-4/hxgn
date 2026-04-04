package hxgn.meteor.modules;

import hxgn.meteor.ClickDispatcher;
import hxgn.meteor.HxgnAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.Objects;

/*

todo:
FIX WHEN SWAPPING To good elytra, dont cancel flight!? (either redeploy or figure out what you have to do)
 */

public class AutoElytraReplace extends Module {

    // Container slot ID for chest armor in the player's inventory screen handler
    private static final int CHEST_SLOT_ID = 6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
            .name("threshold")
            .description("Replace elytra when remaining durability drops to or below this value")
            .defaultValue(20)
            .min(1)
            .max(432)
            .build());

    private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
            .name("click-delay")
            .description("Milliseconds between inventory clicks")
            .defaultValue(10)
            .min(0)
            .max(50)
            .build());

    private final ClickDispatcher dispatcher = new ClickDispatcher(clickDelay);

    public AutoElytraReplace() {
        super(HxgnAddon.CATEGORY, "auto-elytra-replace", "Replaces your elytra mid-flight when durability hits a threshold");
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        int count = 0;
        for (Slot slot : mc.player.playerScreenHandler.slots) {
            if (slot.getStack().getItem() == Items.ELYTRA) count++;
        }
        return String.valueOf(count);
    }

    @Override
    public void onDeactivate() {
        dispatcher.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.currentScreen instanceof HandledScreen) return;
        dispatcher.drain();

        if (!dispatcher.isEmpty()) return;
        if (mc.world == null || mc.player == null) return;

        ClientPlayerEntity player = mc.player;
        if (!player.isGliding()) return;

        ItemStack worn = player.playerScreenHandler.getSlot(CHEST_SLOT_ID).getStack();
        if (worn.isEmpty() || worn.getItem() != Items.ELYTRA) return;
        if (worn.getMaxDamage() - worn.getDamage() > threshold.get()) return;

        boolean hasFreeSlot = InvUtils.findEmpty().found();

        Slot replacement = findBestElytra(player);
        if (replacement != null) {
            enqueueReplace(replacement.id, hasFreeSlot);
            redeployElytra(player);
            return;
        }

        replacement = findChestplate(player);
        if (replacement != null) {
            enqueueReplace(replacement.id, hasFreeSlot);
            return;
        }

        if (hasFreeSlot) {
            dispatcher.enqueueClick(CHEST_SLOT_ID, true);
            return;
        }

        player.sendMessage(
                Text.literal("[AutoElytraReplace] Elytra critical! No replacement and inventory full!"), true);
    }

    // Uses two atomic shift-clicks (QUICK_MOVE) when a free slot is available,
    // avoiding mid-sequence server syncs from things like firework use.
    // Falls back to 3-click cursor swap when inventory is full.
    private void enqueueReplace(int replacementSlotId, boolean hasFreeSlot) {
        if (hasFreeSlot) {
            dispatcher.enqueueClick(CHEST_SLOT_ID, true);
            dispatcher.enqueueClick(replacementSlotId, true);
        } else {
            dispatcher.enqueueSwap(replacementSlotId, CHEST_SLOT_ID);
        }
    }

    private void redeployElytra(ClientPlayerEntity player) {
        if(player.isGliding()) return;
        assert mc.player != null;
        Objects.requireNonNull(mc.getNetworkHandler()).sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private Slot findBestElytra(ClientPlayerEntity player) {
        Slot best = null;
        int bestRemaining = -1;
        for (Slot slot : player.playerScreenHandler.slots) {
            if (slot.id == CHEST_SLOT_ID) continue;
            ItemStack item = slot.getStack();
            if (item.isEmpty() || item.getItem() != Items.ELYTRA) continue;
            int remaining = item.getMaxDamage() - item.getDamage();
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                best = slot;
            }
        }
        return best;
    }

    private Slot findChestplate(ClientPlayerEntity player) {
        for (Slot slot : player.playerScreenHandler.slots) {
            ItemStack item = slot.getStack();
            if (item.isEmpty()) continue;
            if (item.getItem() == Items.ELYTRA) continue;
            if (player.getPreferredEquipmentSlot(item) == EquipmentSlot.CHEST) return slot;
        }
        return null;
    }
}
