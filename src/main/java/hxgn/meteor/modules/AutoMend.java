package hxgn.meteor.modules;

import hxgn.meteor.ClickDispatcher;
import hxgn.meteor.HxgnAddon;
import hxgn.meteor.MendingScanner;
import hxgn.meteor.RepairHandler;
import hxgn.meteor.ShulkerRefillHandler;
import java.util.function.Consumer;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.util.math.Vec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import java.util.Optional;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.Slot;

import java.util.List;
import java.util.function.Predicate;

public class AutoMend extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> offhandToo = sgGeneral.add(new BoolSetting.Builder()
            .name("use-offhand")
            .description("Puts the second-most damaged mending piece in offhand")
            .defaultValue(false)
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

    private final SettingGroup sgShulker = settings.createGroup("Shulker Refill");

    private final Setting<Integer> refillThreshold = sgShulker.add(new IntSetting.Builder()
            .name("refill-threshold")
            .description("Trigger restock when damaged mending items in inventory drop below this count")
            .defaultValue(2)
            .min(1)
            .max(27)
            .visible(() -> BARITONE_PRESENT)
            .build());

    private final Setting<ShulkerRefillHandler.BreakTool> breakTool = sgShulker.add(
            new EnumSetting.Builder<ShulkerRefillHandler.BreakTool>()
                    .name("break-tool")
                    .description("Tool to use when breaking the shulker to pick it back up")
                    .defaultValue(ShulkerRefillHandler.BreakTool.BEST_PICKAXE)
                    .visible(() -> BARITONE_PRESENT)
                    .build());

    private final Setting<Boolean> autoDisable = sgShulker.add(new BoolSetting.Builder()
            .name("auto-disable")
            .description("Disable the module automatically when no more mending shulkers are found")
            .defaultValue(true)
            .visible(() -> BARITONE_PRESENT)
            .build());

    private final ClickDispatcher dispatcher = new ClickDispatcher(clickDelay);

    private final RepairHandler repairHandler = new RepairHandler(dispatcher);

    // ShulkerRefillHandler owns its own transferDispatcher (separate from the armor-swap dispatcher).
    // transferDispatcher drains only when a container IS open; main dispatcher drains only when none is open.
    // Null when Baritone is not installed.
    private ShulkerRefillHandler refillHandler = null;

    //dependency on refillHandler
    private final Setting<Boolean> shulkerRefill = sgShulker.add(new BoolSetting.Builder()
            .name("shulker-refill")
            .description("Place a shulker of damaged mending items to restock inventory when supply runs low")
            .defaultValue(false)
            .visible(() -> BARITONE_PRESENT)
            .onChanged(enabled -> {
                if (!isActive()) return;
                if (!enabled && refillHandler != null) {
                    refillHandler.reset();
                    resumeRusherAura();
                    prevRefillActive = false;
                }
            })
            .build());

    private int lastInventoryHash = 0;
    private long manualCooldownUntil = 0L;
    private long swapGraceUntil = 0L;

    // Camera/movement lock during refill
    private float lockedYaw, lockedPitch;
    private boolean cameraLocked = false;

    // Baritone soft-dependency check
    private static final boolean BARITONE_PRESENT;
    static {
        boolean present;
        try { Class.forName("baritone.api.BaritoneAPI"); present = true; }
        catch (ClassNotFoundException e) { present = false; }
        BARITONE_PRESENT = present;
    }

    // Rusher Aura soft-dependency (reflection — no compile-time dep required)
    private static final boolean RUSHER_PRESENT;
    static {
        boolean present;
        try { Class.forName("org.rusherhack.client.api.RusherHackAPI"); present = true; }
        catch (ClassNotFoundException e) { present = false; }
        RUSHER_PRESENT = present;
    }
    private boolean rusherAuraWasEnabled = false;
    private boolean prevRefillActive = false;

    private static final long MANUAL_COOLDOWN_MS = 2000L;
    private static final long SWAP_GRACE_MS = 500L;
    private static final Consumer<String> NOOP = s -> {};

    private volatile int damagedCount = 0;

    public AutoMend() {
        super(HxgnAddon.CATEGORY, "clever-mend", "Wear the most-damaged mending piece so XP repairs it");
        refillHandler = BARITONE_PRESENT ? new ShulkerRefillHandler(clickDelay) : null;
    }

    @Override
    public String getInfoString() {
        if (shulkerRefill.get() && refillHandler != null && refillHandler.isActive())
            return damagedCount + " | " + refillHandler.getState();
        return String.valueOf(damagedCount);
    }

    @Override
    public void onActivate() {
        lastInventoryHash = 0;
        manualCooldownUntil = 0L;
        swapGraceUntil = 0L;
        dispatcher.clear();
        if (refillHandler != null) refillHandler.reset();

    }

    @Override
    public void onDeactivate() {
        dispatcher.clear();
        if (refillHandler != null) refillHandler.reset();
        cameraLocked = false;
        resumeRusherAura();
        prevRefillActive = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Extend grace window while armor-swap clicks are still draining.
        if (!dispatcher.isEmpty()) swapGraceUntil = System.currentTimeMillis() + SWAP_GRACE_MS;

        // Main dispatcher: drains only when no container is open (armor/offhand swaps).
        if (!isExternalContainerOpen()) dispatcher.drain();

        if (mc.world == null) return;

        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        // ── Shulker Refill ──────────────────────────────────────────────────────────
        if (shulkerRefill.get() && refillHandler != null) {
            // transferDispatcher drains only when a container IS open (inverted from main).
            if (isExternalContainerOpen()) refillHandler.drainTransfer();

            refillHandler.setLogger(debug.get() ? this::info : NOOP);
            RegistryEntry<Enchantment> mending = MendingScanner.resolveMending(mc.world);
            Predicate<ItemStack> itemFilter    = stack -> isDamagedMending(mending, stack);
            Predicate<ItemStack> shulkerMatcher = stack -> {
                if (!stack.isIn(ItemTags.SHULKER_BOXES)) return false;
                ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
                return c != null && c.streamNonEmpty().anyMatch(itemFilter);
            };
            boolean refillActive = refillHandler.tick(
                player,
                dispatcher,
                () -> countDamagedMendingItems(player, mending) < refillThreshold.get(),
                shulkerMatcher,
                itemFilter,
                breakTool.get());

            if (refillActive && !prevRefillActive) pauseRusherAura();
            else if (!refillActive && prevRefillActive) resumeRusherAura();
            prevRefillActive = refillActive;

            if (refillHandler.shouldDisable()) {
                refillHandler.clearShouldDisable();
                if (autoDisable.get()) {
                    info("[CleverMend] No more damaged mending items in any shulker, disabling.");
                    toggle();
                    return;
                }
            }

            if (refillActive) {
                if (refillHandler.wantsMovementLock()) {
                    if (!cameraLocked) {
                        lockedYaw   = player.getYaw();
                        lockedPitch = player.getPitch();
                        cameraLocked = true;
                    }
                    player.setYaw(lockedYaw);
                    player.setPitch(lockedPitch);
                    Vec3d vel = player.getVelocity();
                    player.setVelocity(0, vel.y, 0);
                } else {
                    cameraLocked = false;
                }
                return; // Pause normal swapper while a refill cycle is in progress.
            }
            cameraLocked = false;
        }
        // ───────────────────────────────────────────────────────────────────────────

        if (isExternalContainerOpen()) return;
        if (mc.currentScreen instanceof InventoryScreen && !inInventory.get()) return;
        if (!dispatcher.isEmpty()) return;

        int hash = inventoryHash(player);
        if (hash != lastInventoryHash) {
            if (System.currentTimeMillis() < swapGraceUntil) {
                if (debug.get()) info("Hash changed by own swap");
            } else {
                manualCooldownUntil = System.currentTimeMillis() + MANUAL_COOLDOWN_MS;
                if (debug.get()) info("Manual inventory change, cooldown %dms", MANUAL_COOLDOWN_MS);
            }
            lastInventoryHash = hash;
        }

        if (System.currentTimeMillis() < manualCooldownUntil) {
            return;
        }

        runSwapper(player);

        // If runSwapper just queued something, open the grace window.
        if (!dispatcher.isEmpty()) swapGraceUntil = System.currentTimeMillis() + SWAP_GRACE_MS;
    }

    private static boolean isDamagedMending(RegistryEntry<Enchantment> mending, ItemStack stack) {
        return stack.isDamageable() && stack.getDamage() > 0
               && EnchantmentHelper.getLevel(mending, stack) > 0;
    }

    private static int countDamagedMendingItems(ClientPlayerEntity player,
                                                RegistryEntry<Enchantment> mending) {
        int count = 0;
        for (Slot s : player.playerScreenHandler.slots) {
            if (s.id < 5 || s.id > 45) continue; // skip crafting slots; include armor/hotbar/offhand
            if (isDamagedMending(mending, s.getStack())) count++;
        }
        return count;
    }

    private void runSwapper(ClientPlayerEntity player) {
        if (mc.world == null) return;
        RegistryEntry<Enchantment> mending = MendingScanner.resolveMending(mc.world);
        List<Slot> pieces = MendingScanner.scan(player, mending);
        List<Slot> tools = MendingScanner.scanTools(player, mending);

        damagedCount = (int) pieces.stream().filter(s -> s.getStack().getDamage() > 0).count()
                     + (int) tools.stream().filter(s -> s.getStack().getDamage() > 0).count();
        Consumer<String> dbg = debug.get() ? this::info : NOOP;

        repairHandler.handleArmor(player, pieces, mending, announce.get(), dbg);
        if (!dispatcher.isEmpty()) {
            if (debug.get()) info("Armor swap queued");
            return; // Don't also queue an offhand swap this tick — wait for armor to settle
        }
        repairHandler.handleOffhand(player, pieces, tools, offhandToo.get(), mending, prioritizeTools.get(), announce.get(), dbg);
        if (debug.get() && !dispatcher.isEmpty()) info("Offhand swap queued");
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (!isActive() || mc.player == null || !shulkerRefill.get() || refillHandler == null) return;
        if (!refillHandler.isActive() || !refillHandler.wantsMovementLock()) return;
        Vec3d vel = mc.player.getVelocity();
        mc.player.setVelocity(0, vel.y, 0);
    }

    private void pauseRusherAura() {
        if (!RUSHER_PRESENT) return;
        try {
            Class<?> api      = Class.forName("org.rusherhack.client.api.RusherHackAPI");
            Class<?> togClass = Class.forName("org.rusherhack.client.api.feature.module.ToggleableModule");
            Object mgr = api.getMethod("getModuleManager").invoke(null);
            @SuppressWarnings("unchecked")
            Optional<Object> opt = (Optional<Object>) mgr.getClass()
                    .getMethod("getFeature", String.class).invoke(mgr, "Aura");
            if (opt.isEmpty()) return;
            Object mod = opt.get();
            if (!togClass.isInstance(mod)) return;
            if ((boolean) togClass.getMethod("isToggled").invoke(mod)) {
                if (debug.get()) info("Rusher Aura is on, pausing for refill");
                togClass.getMethod("setToggled", boolean.class).invoke(mod, false);
                rusherAuraWasEnabled = true;
            }
        } catch (Exception ignored) {}
    }

    private void resumeRusherAura() {
        if (!RUSHER_PRESENT || !rusherAuraWasEnabled) return;
        try {
            Class<?> api      = Class.forName("org.rusherhack.client.api.RusherHackAPI");
            Class<?> togClass = Class.forName("org.rusherhack.client.api.feature.module.ToggleableModule");
            Object mgr = api.getMethod("getModuleManager").invoke(null);
            @SuppressWarnings("unchecked")
            Optional<Object> opt = (Optional<Object>) mgr.getClass()
                    .getMethod("getFeature", String.class).invoke(mgr, "Aura");
            if (opt.isPresent() && togClass.isInstance(opt.get())) {
                if (debug.get()) info("Resuming Rusher Aura");
                togClass.getMethod("setToggled", boolean.class).invoke(opt.get(), true);
            }
        } catch (Exception ignored) {}
        rusherAuraWasEnabled = false;
    }

    private boolean isExternalContainerOpen() {
        return mc.currentScreen instanceof HandledScreen && !(mc.currentScreen instanceof InventoryScreen);
    }

    private int inventoryHash(ClientPlayerEntity player) {
        int hash = 0;
        for (Slot s : player.playerScreenHandler.slots) {
            if (!s.getStack().isEmpty()) {
                hash = hash * 31 + s.getStack().getItem().hashCode();
                // Count intentionally excluded: consuming items (XP bottles, food) changes
                // count but not position, so it shouldn't trigger the manual-change cooldown
            }
        }
        return hash;
    }
}
