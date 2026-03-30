package hxgn.meteor.modules;

import hxgn.meteor.HxgnAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class FutureTotem extends Module {

    private static final int OFFHAND_SLOT_ID = 45;
    private static final int OFFHAND_BUTTON = 40; // SlotActionType.SWAP button for offhand (vanilla "press F")
    private static final long REFILL_COOLDOWN_MS = 500L;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
            .name("announce")
            .description("Show action bar message when a totem activates")
            .defaultValue(true)
            .build());

    private long refillCooldownUntil = 0L;

    public FutureTotem() {
        super(HxgnAddon.CATEGORY, "future-totem", "Keeps a totem in offhand at all times");
    }

    @Override
    public void onActivate() {
        refillCooldownUntil = 0L;
        tryRefill();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (System.currentTimeMillis() < refillCooldownUntil) return;

        ItemStack offhand = mc.player.playerScreenHandler.getSlot(OFFHAND_SLOT_ID).getStack();
        if (!offhand.isEmpty() && offhand.getItem() == Items.TOTEM_OF_UNDYING) return;

        if (tryRefill()) refillCooldownUntil = System.currentTimeMillis() + REFILL_COOLDOWN_MS;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getEntity(mc.world) != mc.player) return;
        if (packet.getStatus() != EntityStatuses.USE_TOTEM_OF_UNDYING) return;

        if (announce.get()) {
            mc.player.sendMessage(Text.literal("[FutureTotem] Totem activated!"), true);
        }

        // Totem is about to be consumed — bypass offhand check and refill immediately
        if (tryRefill()) refillCooldownUntil = System.currentTimeMillis() + REFILL_COOLDOWN_MS;
    }

    // Returns true if a click was sent
    private boolean tryRefill() {
        if (mc.player == null) return false;
        for (Slot slot : mc.player.playerScreenHandler.slots) {
            if (slot.id == OFFHAND_SLOT_ID) continue;
            if (slot.getStack().isEmpty() || slot.getStack().getItem() != Items.TOTEM_OF_UNDYING) continue;

            mc.interactionManager.clickSlot(
                mc.player.playerScreenHandler.syncId,
                slot.id, OFFHAND_BUTTON, SlotActionType.SWAP, mc.player
            );
            return true;
        }
        return false;
    }
}
