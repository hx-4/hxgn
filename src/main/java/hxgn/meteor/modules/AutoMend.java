package hxgn.meteor.modules;

import hxgn.meteor.ClickDispatcher;
import hxgn.meteor.HxgnAddon;
import hxgn.meteor.MendingScanner;
import hxgn.meteor.RepairHandler;
import java.util.function.Consumer;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoArmor;
import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.Slot;

import java.util.List;

public class AutoMend extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private boolean autoTotemWasOn = false;
    private boolean futureTotemWasOn = false;
    private boolean autoArmorWasOn = false;

    private final Setting<Boolean> offhandToo = sgGeneral.add(new BoolSetting.Builder()
            .name("use-offhand")
            .description("Puts the second-most damaged mending piece in offhand (temporarily disables AutoTotem and FutureTotem)")
            .defaultValue(false)
            .onChanged(enabled -> {
                if (!isActive()) return;
                AutoTotem autoTotem = Modules.get().get(AutoTotem.class);
                FutureTotem futureTotem = Modules.get().get(FutureTotem.class);
                if (enabled) {
                    if (autoTotem != null && autoTotem.isActive()) { autoTotem.toggle(); autoTotemWasOn = true; }
                    if (futureTotem != null && futureTotem.isActive()) { futureTotem.toggle(); futureTotemWasOn = true; }
                } else {
                    if (autoTotemWasOn) { if (!autoTotem.isActive()) autoTotem.toggle(); autoTotemWasOn = false; }
                    if (futureTotemWasOn) { if (!futureTotem.isActive()) futureTotem.toggle(); futureTotemWasOn = false; }
                }
            })
            .build());

    private final Setting<Boolean> prioritizeTools = sgGeneral.add(new BoolSetting.Builder()
            .name("prioritize-tools")
            .description("When use-offhand is on, prefer the most-damaged tool over the second-most damaged armor piece")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> inInventory = sgGeneral.add(new BoolSetting.Builder()
            .name("in-inventory")
            .description("Continue mending swaps while your inventory is open")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
            .name("announce")
            .description("Show an action bar message when a mending piece finishes repairing")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug")
            .description("Print flow events to chat for debugging")
            .defaultValue(false)
            .build());

    private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
            .name("click-delay")
            .description("Milliseconds between inventory clicks")
            .defaultValue(50)
            .sliderRange(0,500)
            .build());

    private final ClickDispatcher dispatcher = new ClickDispatcher(clickDelay);
    private final RepairHandler repairHandler = new RepairHandler(dispatcher);

    private int lastInventoryHash = 0;
    private long manualCooldownUntil = 0L;
    private long swapGraceUntil = 0L;

    private static final long MANUAL_COOLDOWN_MS = 2000L;
    private static final long SWAP_GRACE_MS = 500L;
    private static final Consumer<String> NOOP = s -> {};

    public AutoMend() {
        super(HxgnAddon.CATEGORY, "auto-mender", "Wear the most-damaged mending piece so XP repairs it");
    }

    @Override
    public void onActivate() {
        lastInventoryHash = 0;
        manualCooldownUntil = 0L;
        swapGraceUntil = 0L;
        dispatcher.clear();

        AutoArmor autoArmor = Modules.get().get(AutoArmor.class);
        if (autoArmor != null && autoArmor.isActive()) { autoArmor.toggle(); autoArmorWasOn = true; }

        if (offhandToo.get()) {
            AutoTotem autoTotem = Modules.get().get(AutoTotem.class);
            if (autoTotem != null && autoTotem.isActive()) { autoTotem.toggle(); autoTotemWasOn = true; }
            FutureTotem futureTotem = Modules.get().get(FutureTotem.class);
            if (futureTotem != null && futureTotem.isActive()) { futureTotem.toggle(); futureTotemWasOn = true; }
        }
    }

    @Override
    public void onDeactivate() {
        dispatcher.clear();
        restoreModule(Modules.get().get(AutoTotem.class), autoTotemWasOn);
        autoTotemWasOn = false;
        restoreModule(Modules.get().get(FutureTotem.class), futureTotemWasOn);
        futureTotemWasOn = false;
        restoreModule(Modules.get().get(AutoArmor.class), autoArmorWasOn);
        autoArmorWasOn = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Extend grace window while our clicks are still draining (server confirmations still incoming)
        if (!dispatcher.isEmpty()) swapGraceUntil = System.currentTimeMillis() + SWAP_GRACE_MS;

        boolean externalOpen = isExternalContainerOpen();
        if (!externalOpen) dispatcher.drain();

        if (mc.world == null) return;
        if (externalOpen) return;
        if (mc.currentScreen instanceof InventoryScreen && !inInventory.get()) return;
        if (!dispatcher.isEmpty()) return;

        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        int hash = inventoryHash(player);
        if (hash != lastInventoryHash) {
            lastInventoryHash = hash;
            if (System.currentTimeMillis() < swapGraceUntil) {
                if (debug.get()) info("Hash changed by own swap");
            } else {
                manualCooldownUntil = System.currentTimeMillis() + MANUAL_COOLDOWN_MS;
                if (debug.get()) info("Manual inventory change, cooldown %dms", MANUAL_COOLDOWN_MS);
            }
            return; // Always wait one tick for inventory to settle before deciding next swap
        }

        if (System.currentTimeMillis() < manualCooldownUntil) {
            if (debug.get()) info("Cooldown: %.1fs left", (manualCooldownUntil - System.currentTimeMillis()) / 1000.0);
            return;
        }

        runSwapper(player);

        // If runSwapper just queued something, open the grace window
        if (!dispatcher.isEmpty()) swapGraceUntil = System.currentTimeMillis() + SWAP_GRACE_MS;
    }

    private void runSwapper(ClientPlayerEntity player) {
        if (mc.world == null) return;
        RegistryEntry<Enchantment> mending = MendingScanner.resolveMending(mc.world);
        List<Slot> pieces = MendingScanner.scan(player, mending);
        List<Slot> tools = MendingScanner.scanTools(player, mending);

        Consumer<String> dbg = debug.get() ? this::info : NOOP;

        if (debug.get()) {
            String pieceNames = pieces.isEmpty() ? "none" : pieces.stream()
                    .map(s -> s.getStack().getName().getString() + "(dmg=" + s.getStack().getDamage() + ",s=" + s.id + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("");
            info("Pieces[%d]: %s | Tools[%d]", pieces.size(), pieceNames, tools.size());
        }

        repairHandler.handleArmor(player, pieces, announce.get(), dbg);
        if (!dispatcher.isEmpty()) {
            if (debug.get()) info("Armor swap queued");
            return; // Don't also queue an offhand swap this tick — wait for armor to settle
        }
        repairHandler.handleOffhand(player, pieces, tools, offhandToo.get(), mending, prioritizeTools.get(), announce.get(), dbg);
        if (debug.get() && !dispatcher.isEmpty()) info("Offhand swap queued");
    }

    private boolean isExternalContainerOpen() {
        return mc.currentScreen instanceof HandledScreen && !(mc.currentScreen instanceof InventoryScreen);
    }

    private void restoreModule(Module module, boolean wasOn) {
        if (wasOn && module != null && !module.isActive()) module.toggle();
    }

    private int inventoryHash(ClientPlayerEntity player) {
        int hash = 0;
        for (Slot s : player.playerScreenHandler.slots) {
            if (!s.getStack().isEmpty()) {
                hash = hash * 31 + s.getStack().getItem().hashCode();
                hash = hash * 31 + s.getStack().getCount();
            }
        }
        return hash;
    }
}
