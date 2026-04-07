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
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;


/*

todo:
FIX WHEN SWAPPING To good elytra, dont cancel flight!? (either redeploy or figure out what you have to do)
 */

public class AutoElytraReplace extends Module {

    // Container slot ID for chest armor in the player's inventory screen handler
    private static final int CHEST_SLOT_ID = 6;
    private static final int ELYTRA_DURABILITY = 432;

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
            if (slot.getStack().getItem() == Items.ELYTRA && slot.getStack().getDamage() < (ELYTRA_DURABILITY - threshold.get())) count++;
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

        Slot replacement = findBestElytra(player);
        if (replacement != null) {
            enqueueReplace(replacement.id);
            return;
        }

        replacement = findChestplate(player);
        if (replacement != null) {
            enqueueReplace(replacement.id);
            return;
        }

        if (InvUtils.findEmpty().found()) {
            dispatcher.enqueueClick(CHEST_SLOT_ID, true);
        } else {
            player.sendMessage(
                    Text.literal("[AutoElytraReplace] Elytra critical! No replacement and inventory full!"), true);
        }
    }

    // 3-click cursor swap: pick up replacement, swap onto chest slot, place old elytra back.
    // The chest slot is never empty during the swap, so gliding is never interrupted.
    private void enqueueReplace(int replacementSlotId) {
        dispatcher.enqueueSwap(replacementSlotId, CHEST_SLOT_ID);
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
