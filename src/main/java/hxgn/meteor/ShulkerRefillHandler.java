package hxgn.meteor;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.NopPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * State machine that handles restocking AutoMender's supply of damaged elytras
 * by placing a shulker box, swapping elytras, and picking the shulker back up.
 *
 * States: IDLE → PREPARING → PLACING → OPENING → TRANSFERRING → CLOSING → BREAKING → COLLECTING
 *
 * SLOT MAPPING (ShulkerBoxScreenHandler, always 27 slots):
 *   Slots [0 .. 26]   : shulker contents
 *   Slots [27 .. 53]  : player main inventory
 *   Slots [54 .. 62]  : player hotbar
 */
public class ShulkerRefillHandler {

    public enum BreakTool { MAIN_HAND, BEST_PICKAXE }

    public enum State {
        IDLE,
        PREPARING,      // Ensure shulker in hotbar + find placement position
        PLACING,        // Custom face-controlled placement + server confirmation
        OPENING,        // Sneak-interact to open the shulker container
        TRANSFERRING,   // Send repaired → shulker, take damaged → player
        CLOSING,        // Close container screen + swap to break tool
        BREAKING,       // Break the shulker block
        COLLECTING      // Baritone walk to item, pick up, walk back
    }

    private enum TransferPhase { SENDING, TAKING }
    private enum CollectPhase  { WALKING_TO_ITEM, WALKING_BACK }

    // ── Timeouts ────────────────────────────────────────────────────────────────
    private static final long IDLE_RETRY_MS         = 500L;
    private static final long PLACEMENT_GRACE_MS    = 2000L;
    private static final long OPEN_RETRY_MS         = 1500L;
    private static final long OPEN_TIMEOUT_MS       = 6000L;
    private static final long TRANSFER_TIMEOUT_MS   = 8000L;
    private static final long CLOSE_TIMEOUT_MS      = 3000L;
    private static final long BREAK_TIMEOUT_MS      = 10000L;
    private static final long COLLECT_TIMEOUT_MS    = 15000L;
    private static final long POST_PICKUP_COOLDOWN_MS = 2000L;
    private static final int  MAX_OPEN_RETRIES      = 3;

    private static final int CHEST_SLOT_PSH = 6; // playerScreenHandler chest slot

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final ClickDispatcher transferDispatcher;
    private Consumer<String> log = s -> {};

    // ── Per-cycle state ─────────────────────────────────────────────────────────
    private State state           = State.IDLE;
    private long stateEnteredAt   = 0L;

    private BlockPos shulkerPos   = null;
    private BlockPos originalPos  = null;
    private Direction chosenFace  = null;

    private boolean sentOpen      = false;
    private int openRetries       = 0;
    private boolean placementSent = false;

    private TransferPhase transferPhase;
    private long transferPhaseEnteredAt = 0L;

    private CollectPhase collectPhase;
    private boolean pathingStarted = false;
    private boolean savedAllowBreak = true;
    private boolean savedAllowPlace = true;
    private boolean baritoneSettingsOverridden = false;

    private BlockPos lastEntityTargetPos    = null;
    private int swapTargetHotbarSlot        = -1; // hotbar slot we put the shulker into
    private int swapDisplacedInventorySlot  = -1; // inventory slot where displaced item ended up
    private boolean swappedForBreaking     = false;
    private int shulkerCountBeforePlace    = 0;
    private boolean shouldDisable          = false;
    private long idleRetryAfter            = 0L;
    private boolean closeSent              = false;

    private final Set<BlockPos> failedPositions = new HashSet<>();

    // ── Constructor ─────────────────────────────────────────────────────────────

    public ShulkerRefillHandler(Setting<Integer> transferDelay) {
        this.transferDispatcher = new ClickDispatcher(transferDelay);
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    public void setLogger(Consumer<String> logger) {
        this.log = logger != null ? logger : s -> {};
    }

    public boolean isActive()           { return state != State.IDLE; }
    public State   getState()           { return state; }
    public boolean shouldDisable()      { return shouldDisable; }
    public void    clearShouldDisable() { shouldDisable = false; }

    /** True when AutoMend should lock camera/movement. False during COLLECTING (Baritone needs control). */
    public boolean wantsMovementLock() {
        return state != State.IDLE && state != State.COLLECTING;
    }

    public void drainTransfer() {
        transferDispatcher.drain();
    }

    public void reset() {
        if (swappedForBreaking) { InvUtils.swapBack(); swappedForBreaking = false; }
        transferDispatcher.clear();
        if (canBaritone() && baritoneSettingsOverridden) stopBaritone();
        state = State.IDLE;
        shulkerPos = null;
        originalPos = null;
        chosenFace = null;
        stateEnteredAt = 0L;
        sentOpen = false;
        openRetries = 0;
        placementSent = false;
        shouldDisable = false;
        idleRetryAfter = 0L;
        closeSent = false;
        pathingStarted = false;
        lastEntityTargetPos = null;
        swapTargetHotbarSlot = -1;
        swapDisplacedInventorySlot = -1;
        failedPositions.clear();
    }

    // ── Main tick ───────────────────────────────────────────────────────────────

    public boolean tick(ClientPlayerEntity player,
                        ClickDispatcher mainDispatcher,
                        int threshold,
                        BreakTool breakTool) {
        ClientWorld world = mc.world;
        if (world == null) return false;

        return switch (state) {

            // ── IDLE ─────────────────────────────────────────────────────────────
            case IDLE -> {
                if (!mainDispatcher.isEmpty()) yield false;
                if (System.currentTimeMillis() < idleRetryAfter) yield false;

                int dmgCount = countDamagedElytras(player);
                if (dmgCount >= threshold) yield false;

                FindItemResult shulkerResult = findElytraShulker();
                if (!shulkerResult.found()) {
                    log.accept("IDLE: no elytra shulker found → shouldDisable=true");
                    shouldDisable = true;
                    yield false;
                }

                originalPos = player.getBlockPos();
                shulkerCountBeforePlace = countShulkers(player);
                log.accept(String.format("IDLE: dmg=%d < threshold=%d, starting refill cycle", dmgCount, threshold));
                enterState(State.PREPARING);
                yield true;
            }

            // ── PREPARING ────────────────────────────────────────────────────────
            case PREPARING -> {
                FindItemResult shulkerResult = findElytraShulker();
                if (!shulkerResult.found()) {
                    log.accept("PREPARING: shulker disappeared → IDLE");
                    enterState(State.IDLE);
                    yield false;
                }

                // Ensure shulker is in hotbar
                if (!shulkerResult.isHotbar()) {
                    int target = findFreeHotbarSlot(player);
                    if (target == -1) target = player.getInventory().selectedSlot;
                    swapTargetHotbarSlot = target;
                    swapDisplacedInventorySlot = shulkerResult.slot();
                    log.accept(String.format("PREPARING: quickSwap slot%d → hotbar[%d]", shulkerResult.slot(), target));
                    InvUtils.quickSwap().fromId(target).to(shulkerResult.slot());
                    yield true; // wait a tick for swap to complete
                }

                // Find placement position (also resolves the face to click)
                BlockPos pos = getPlacementPos(player, world);
                if (pos == null) {
                    log.accept("PREPARING: no valid placement pos, retrying");
                    idleRetryAfter = System.currentTimeMillis() + IDLE_RETRY_MS;
                    enterState(State.IDLE);
                    yield false;
                }

                // chosenFace was set by getPlacementPos — guaranteed non-null here
                shulkerPos = pos;
                placementSent = false;
                log.accept(String.format("PREPARING: pos=%s face=%s shulker@slot%d → PLACING",
                    pos, chosenFace, shulkerResult.slot()));
                enterState(State.PLACING);
                yield true;
            }

            // ── PLACING ──────────────────────────────────────────────────────────
            case PLACING -> {
                long elapsed = elapsed();

                // Send placement once
                if (!placementSent) {
                    FindItemResult shulkerResult = findElytraShulker();
                    if (!shulkerResult.found() || !shulkerResult.isHotbar()) {
                        log.accept("PLACING: shulker not in hotbar → IDLE");
                        enterState(State.IDLE);
                        yield false;
                    }
                    placeShulker(shulkerResult, shulkerPos, chosenFace);
                    placementSent = true;
                    log.accept("PLACING: placement sent");
                    yield true;
                }

                // Wait for server confirmation
                if (world.getBlockState(shulkerPos).isAir()) {
                    if (elapsed < PLACEMENT_GRACE_MS) yield true;
                    log.accept(String.format("PLACING: block still air after grace → blacklist %s, IDLE", shulkerPos));
                    failedPositions.add(shulkerPos);
                    idleRetryAfter = System.currentTimeMillis() + IDLE_RETRY_MS;
                    enterState(State.IDLE);
                    yield false;
                }

                // Block confirmed — verify facing and lid clearance
                BlockState confirmed = world.getBlockState(shulkerPos);
                Direction facing = confirmed.contains(Properties.FACING)
                    ? confirmed.get(Properties.FACING) : Direction.UP;
                BlockPos lidPos = shulkerPos.offset(facing);

                if (!world.getBlockState(lidPos).isAir()) {
                    log.accept(String.format("PLACING: facing=%s, lid blocked at %s → BREAKING (blacklist %s)",
                        facing, lidPos, shulkerPos));
                    failedPositions.add(shulkerPos);
                    enterState(State.BREAKING);
                    yield true;
                }

                log.accept(String.format("PLACING: confirmed facing=%s, lid clear → OPENING", facing));
                sentOpen = false;
                openRetries = 0;
                enterState(State.OPENING);
                yield true;
            }

            // ── OPENING ──────────────────────────────────────────────────────────
            case OPENING -> {
                long elapsed = elapsed();

                if (!sentOpen) {
                    boolean wasSneaking = player.isSneaking();
                    player.setSneaking(true);
                    Vec3d hitPos = Vec3d.ofCenter(shulkerPos).add(0, 0.5, 0);
                    var result = mc.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                        new BlockHitResult(hitPos, Direction.UP, shulkerPos, false));
                    player.swingHand(Hand.MAIN_HAND);
                    player.setSneaking(wasSneaking);
                    sentOpen = true;
                    log.accept(String.format("OPENING: interactBlock (result=%s, retry=%d/%d)",
                        result, openRetries, MAX_OPEN_RETRIES));
                }

                if (mc.currentScreen instanceof ShulkerBoxScreen) {
                    log.accept("OPENING → TRANSFERRING");
                    transferPhase = TransferPhase.SENDING;
                    transferPhaseEnteredAt = System.currentTimeMillis();
                    enterState(State.TRANSFERRING);
                    yield true;
                }

                // Retry logic
                if (openRetries < MAX_OPEN_RETRIES
                        && elapsed > (long) OPEN_RETRY_MS * (openRetries + 1)) {
                    sentOpen = false;
                    openRetries++;
                    log.accept(String.format("OPENING: no screen after %dms, retry %d/%d",
                        elapsed, openRetries, MAX_OPEN_RETRIES));
                    yield true;
                }

                if (elapsed > OPEN_TIMEOUT_MS) {
                    log.accept(String.format("OPENING: timed out → BREAKING (blacklist %s)", shulkerPos));
                    failedPositions.add(shulkerPos);
                    enterState(State.BREAKING);
                }
                yield true;
            }

            // ── TRANSFERRING ─────────────────────────────────────────────────────
            case TRANSFERRING -> {
                if (!(mc.currentScreen instanceof ShulkerBoxScreen)) {
                    log.accept("TRANSFERRING: screen lost → CLOSING");
                    transferDispatcher.clear();
                    enterState(State.CLOSING);
                    yield true;
                }
                if (!(player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) {
                    yield true; // handler not yet assigned
                }

                int containerSlots = 27;
                long phaseElapsed = System.currentTimeMillis() - transferPhaseEnteredAt;

                if (phaseElapsed > TRANSFER_TIMEOUT_MS) {
                    log.accept(String.format("TRANSFERRING: %s timed out → CLOSING", transferPhase));
                    transferDispatcher.clear();
                    enterState(State.CLOSING);
                    yield true;
                }

                // Wait for dispatcher to drain
                if (!transferDispatcher.isEmpty()) {
                    yield true;
                }

                switch (transferPhase) {
                    case SENDING -> {
                        int queued = queueRepairedToShulker(player, handler, containerSlots);
                        if (queued > 0) {
                            log.accept(String.format("TRANSFERRING/SENDING: queued %d clicks", queued));
                            yield true;
                        }
                        // Nothing more to send → switch to TAKING
                        log.accept("TRANSFERRING: SENDING done → TAKING");
                        transferPhase = TransferPhase.TAKING;
                        transferPhaseEnteredAt = System.currentTimeMillis();
                    }
                    case TAKING -> {
                        int queued = queueDamagedFromShulker(handler, containerSlots);
                        if (queued > 0) {
                            log.accept(String.format("TRANSFERRING/TAKING: queued %d clicks", queued));
                            yield true;
                        }
                        // Nothing more to take → done
                        log.accept("TRANSFERRING: TAKING done → CLOSING");
                        enterState(State.CLOSING);
                    }
                }
                yield true;
            }

            // ── CLOSING ──────────────────────────────────────────────────────────
            case CLOSING -> {
                if (!closeSent) {
                    player.closeHandledScreen();
                    closeSent = true;
                    log.accept("CLOSING: sent closeHandledScreen");
                    yield true;
                }

                if (mc.currentScreen instanceof HandledScreen) {
                    if (elapsed() > CLOSE_TIMEOUT_MS) {
                        log.accept("CLOSING: screen won't close → forcing BREAKING");
                        enterState(State.BREAKING);
                    }
                    yield true;
                }

                // Screen gone — swap to break tool
                if (breakTool == BreakTool.BEST_PICKAXE) swapToBestPickaxe(player);
                log.accept("CLOSING: screen gone → BREAKING");
                enterState(State.BREAKING);
                yield true;
            }

            // ── BREAKING ─────────────────────────────────────────────────────────
            case BREAKING -> {
                if (world.getBlockState(shulkerPos).isAir()) {
                    log.accept("BREAKING: block gone → COLLECTING");
                    if (swappedForBreaking) { InvUtils.swapBack(); swappedForBreaking = false; }
                    collectPhase = CollectPhase.WALKING_TO_ITEM;
                    pathingStarted = false;
                    enterState(State.COLLECTING);
                    yield true;
                }
                if (elapsed() > BREAK_TIMEOUT_MS) {
                    log.accept("BREAKING: timed out → accepting loss, IDLE");
                    if (swappedForBreaking) { InvUtils.swapBack(); swappedForBreaking = false; }
                    enterState(State.IDLE);
                    yield false;
                }
                BlockUtils.breakBlock(shulkerPos, true);
                yield true;
            }

            // ── COLLECTING ───────────────────────────────────────────────────────
            case COLLECTING -> {
                int currentShulkers = countShulkers(player);
                boolean canPath = canBaritone();

                switch (collectPhase) {
                    case WALKING_TO_ITEM -> {
                        // Check if picked up
                        if (currentShulkers >= shulkerCountBeforePlace) {
                            log.accept(String.format("COLLECTING: shulker picked up (%d >= %d)",
                                currentShulkers, shulkerCountBeforePlace));
                            if (canPath) stopBaritone();
                            collectPhase = CollectPhase.WALKING_BACK;
                            pathingStarted = false;
                            lastEntityTargetPos = null;
                            stateEnteredAt = System.currentTimeMillis(); // reset timeout for walk-back
                            yield true;
                        }

                        if (canPath) {
                            // Find the actual dropped shulker item entity
                            ItemEntity entity = findShulkerEntity(player);
                            if (entity != null) {
                                BlockPos entityPos = entity.getBlockPos();
                                // Only re-path if entity moved >1 block from last target
                                if (lastEntityTargetPos == null
                                        || !entityPos.isWithinDistance(lastEntityTargetPos, 1.5)) {
                                    baritoneGoalNear(entityPos, 0);
                                    lastEntityTargetPos = entityPos;
                                    pathingStarted = true;
                                    log.accept(String.format("COLLECTING: pathing to entity at %s", entityPos));
                                }
                            } else if (elapsed() > 2000 && !pathingStarted) {
                                // Entity not found after 2s grace → fall back to block pos
                                baritoneGoalNear(shulkerPos, 0);
                                pathingStarted = true;
                                log.accept("COLLECTING: entity not found, fallback to shulkerPos");
                            }
                        } else if (!pathingStarted) {
                            log.accept(String.format(
                                "COLLECTING: no pathfinder (pathManager=%s) — waiting for manual pickup",
                                PathManagers.get().getName()));
                            pathingStarted = true;
                        }

                        if (elapsed() > COLLECT_TIMEOUT_MS) {
                            log.accept("COLLECTING: pickup timed out → shouldDisable");
                            if (canPath) stopBaritone();
                            lastEntityTargetPos = null;
                            shouldDisable = true;
                            enterState(State.IDLE);
                            yield false;
                        }
                    }
                    case WALKING_BACK -> {
                        if (!pathingStarted) {
                            if (canPath && originalPos != null) {
                                baritoneGoalNear(originalPos, 1);
                                log.accept(String.format("COLLECTING: return to %s", originalPos));
                            }
                            pathingStarted = true;
                            yield true; // give Baritone a tick to start pathfinding
                        }

                        boolean arrived = !canPath
                            || originalPos == null
                            || player.getBlockPos().isWithinDistance(originalPos, 1.0)
                            || !PathManagers.get().isPathing();

                        if (arrived || elapsed() > COLLECT_TIMEOUT_MS) {
                            if (canPath) stopBaritone();
                            if (swapTargetHotbarSlot != -1) {
                                InvUtils.quickSwap().fromId(swapTargetHotbarSlot).to(swapDisplacedInventorySlot);
                                log.accept(String.format("COLLECTING: restored hotbar[%d] ← slot%d",
                                    swapTargetHotbarSlot, swapDisplacedInventorySlot));
                                swapTargetHotbarSlot = -1;
                                swapDisplacedInventorySlot = -1;
                            }
                            log.accept("COLLECTING: arrived back → IDLE");
                            idleRetryAfter = System.currentTimeMillis() + POST_PICKUP_COOLDOWN_MS;
                            pathingStarted = false;
                            enterState(State.IDLE);
                            yield false;
                        }
                    }
                }
                yield true;
            }
        };
    }

    // ── Transfer Algorithms ─────────────────────────────────────────────────────

    /**
     * Queue clicks to send FULLY REPAIRED elytras from player → shulker.
     * Strategy A: shift-click into empty shulker slots.
     * Strategy B: 3-click cursor-swap with damaged elytras in shulker when full.
     */
    private int queueRepairedToShulker(ClientPlayerEntity player,
                                       ShulkerBoxScreenHandler handler,
                                       int containerSlots) {
        List<Integer> repairedPlayerGI = new ArrayList<>();
        for (int gi = containerSlots; gi < handler.slots.size(); gi++) {
            ItemStack stack = handler.getSlot(gi).getStack();
            if (stack.getItem() == Items.ELYTRA && stack.getDamage() == 0)
                repairedPlayerGI.add(gi);
        }
        if (repairedPlayerGI.isEmpty()) return 0;

        List<Integer> emptyShulkerGI = new ArrayList<>();
        List<Integer> damagedShulkerGI = new ArrayList<>();
        for (int i = 0; i < containerSlots; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (s.isEmpty()) emptyShulkerGI.add(i);
            else if (s.getItem() == Items.ELYTRA && s.getDamage() > 0) damagedShulkerGI.add(i);
        }

        int emptyIdx = 0, damagedIdx = 0, queued = 0;
        for (int playerGI : repairedPlayerGI) {
            if (emptyIdx < emptyShulkerGI.size()) {
                transferDispatcher.enqueueClick(playerGI, true);
                emptyIdx++;
                queued++;
            } else if (damagedIdx < damagedShulkerGI.size()) {
                transferDispatcher.enqueueSwap(playerGI, damagedShulkerGI.get(damagedIdx));
                damagedIdx++;
                queued += 3;
            } else {
                break;
            }
        }
        return queued;
    }

    /**
     * Queue shift-clicks to pull DAMAGED elytras from shulker → player.
     * Reserves 1 free player slot for the shulker item after breaking.
     */
    private int queueDamagedFromShulker(ShulkerBoxScreenHandler handler, int containerSlots) {
        int freePlayerSlots = 0;
        for (int gi = containerSlots; gi < handler.slots.size(); gi++) {
            if (handler.getSlot(gi).getStack().isEmpty()) freePlayerSlots++;
        }
        freePlayerSlots = Math.max(0, freePlayerSlots - 1); // reserve 1 for shulker pickup

        int queued = 0;
        for (int i = 0; i < containerSlots && freePlayerSlots > 0; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (s.getItem() == Items.ELYTRA && s.getDamage() > 0) {
                transferDispatcher.enqueueClick(i, true);
                freePlayerSlots--;
                queued++;
            }
        }
        return queued;
    }

    // ── Counting Utilities ──────────────────────────────────────────────────────

    private int countDamagedElytras(ClientPlayerEntity player) {
        int count = 0;
        for (Slot s : player.playerScreenHandler.slots) {
            if (s.id == CHEST_SLOT_PSH) continue;
            if (s.getStack().getItem() == Items.ELYTRA && s.getStack().getDamage() > 0) count++;
        }
        return count;
    }

    private int countShulkers(ClientPlayerEntity player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isIn(ItemTags.SHULKER_BOXES)) count++;
        }
        return count;
    }

    // ── Entity Lookup ────────────────────────────────────────────────────────────

    /** Finds the closest dropped shulker box ItemEntity within 5 blocks of shulkerPos. */
    private ItemEntity findShulkerEntity(ClientPlayerEntity player) {
        if (mc.world == null || shulkerPos == null) return null;
        Box searchArea = new Box(shulkerPos).expand(5);
        ItemEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getOtherEntities(player, searchArea)) {
            if (entity instanceof ItemEntity ie && ie.getStack().isIn(ItemTags.SHULKER_BOXES)) {
                double dist = ie.squaredDistanceTo(Vec3d.ofCenter(shulkerPos));
                if (dist < closestDist) {
                    closest = ie;
                    closestDist = dist;
                }
            }
        }
        return closest;
    }

    // ── Lookup Utilities ────────────────────────────────────────────────────────

    private FindItemResult findElytraShulker() {
        return InvUtils.find(stack -> {
            if (!stack.isIn(ItemTags.SHULKER_BOXES)) return false;
            ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
            if (c == null) return false;
            return c.streamNonEmpty().anyMatch(s -> s.getItem() == Items.ELYTRA && s.getDamage() > 0);
        });
    }

    private int findFreeHotbarSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    // ── Placement ───────────────────────────────────────────────────────────────

    /**
     * Picks the best face to click for shulker placement at {@code pos}.
     * Prefers DOWN (→ FACING=UP, lid opens upward) over horizontal, then UP last.
     * Returns null if no valid face exists.
     *
     * "face" = Direction FROM pos TO the solid neighbor we click.
     * Clicking that neighbor's surface produces FACING = face.getOpposite().
     */
    private Direction choosePlacementFace(BlockPos pos, ClientWorld world) {
        Direction[] priority = { Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                                 Direction.EAST, Direction.WEST, Direction.UP };
        for (Direction face : priority) {
            BlockPos neighbor = pos.offset(face);
            BlockState neighborState = world.getBlockState(neighbor);
            if (neighborState.isAir() || BlockUtils.isClickable(neighborState.getBlock())) continue;
            if (!neighborState.getFluidState().isEmpty()) continue;
            // Skip non-solid blocks (carpets, pressure plates, etc.) — can't reliably place against them
            if (neighborState.getCollisionShape(world, neighbor).isEmpty()) continue;

            Direction facing = face.getOpposite();
            if (!world.getBlockState(pos.offset(facing)).isAir()) continue;

            return face;
        }
        return null;
    }

    /**
     * Places shulker with explicit face control, bypassing BlockUtils.getPlaceSide().
     * Uses Rotations.rotate() for anti-cheat compliance.
     */
    private void placeShulker(FindItemResult item, BlockPos pos, Direction face) {
        BlockPos neighbor = pos.offset(face);
        Direction clickedFace = face.getOpposite();
        Vec3d hitPos = Vec3d.ofCenter(neighbor).add(
            clickedFace.getOffsetX() * 0.5,
            clickedFace.getOffsetY() * 0.5,
            clickedFace.getOffsetZ() * 0.5
        );
        BlockHitResult bhr = new BlockHitResult(hitPos, clickedFace, neighbor, false);

        // Select the shulker slot NOW — not deferred in the rotation callback.
        // The callback fires on a future tick; if the swap is inside it, the
        // player may still be holding the wrong item when the packet is sent.
        InvUtils.swap(item.slot(), true);

        Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), 50, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        });
    }

    /**
     * Searches nearby positions for a valid shulker placement spot, sorted nearest-first.
     * Sets {@link #chosenFace} as a side effect when a valid position is found.
     */
    private BlockPos getPlacementPos(ClientPlayerEntity player, ClientWorld world) {
        BlockPos origin = player.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= 2; dy++) {
                    candidates.add(origin.add(dx, dy, dz));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p ->
            Vec3d.ofCenter(p).squaredDistanceTo(player.getPos())));

        for (BlockPos pos : candidates) {
            if (failedPositions.contains(pos)) continue;
            if (!PlayerUtils.isWithinReach(pos)) continue;

            Direction face = getValidPlacementFace(pos, world);
            if (face == null) continue;

            if (!hasLineOfSight(player, pos, world)) continue;
            chosenFace = face;
            return pos;
        }
        return null;
    }

    /**
     * Returns the placement face if the position is valid, or null.
     * A position is valid for shulker placement if:
     *  1. Air block (shulker body)
     *  2. Has a valid face to click with lid clearance
     *  3. Not over a drop (solid ground within 5 blocks below)
     *  4. Horizontal 2-block clearance for player access
     */
    private Direction getValidPlacementFace(BlockPos pos, ClientWorld world) {
        if (!world.getBlockState(pos).isAir()) return null;

        Direction face = choosePlacementFace(pos, world);
        if (face == null) return null;

        if (!hasGroundBelow(pos, world)) return null;

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos adj = pos.offset(dir);
            if (world.getBlockState(adj).getCollisionShape(world, adj).isEmpty()
                    && world.getBlockState(adj.up()).getCollisionShape(world, adj.up()).isEmpty()) {
                return face;
            }
        }
        return null;
    }

    private boolean hasGroundBelow(BlockPos pos, ClientWorld world) {
        for (int dy = 1; dy <= 5; dy++) {
            BlockPos below = pos.down(dy);
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) return true;
        }
        return false;
    }

    private boolean hasLineOfSight(ClientPlayerEntity player, BlockPos pos, ClientWorld world) {
        Vec3d eye = player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        if (eye.squaredDistanceTo(target) > 4.5 * 4.5) return false;
        RaycastContext ctx = new RaycastContext(eye, target,
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
        return world.raycast(ctx).getType() == HitResult.Type.MISS;
    }

    // ── Tool Swap ───────────────────────────────────────────────────────────────

    private void swapToBestPickaxe(ClientPlayerEntity player) {
        if (player.getMainHandStack().isIn(ItemTags.PICKAXES)) return;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isIn(ItemTags.PICKAXES)) {
                InvUtils.swap(i, true);
                swappedForBreaking = true;
                return;
            }
        }
    }

    /** True if a real path manager (Baritone or Voyager) is available, not the no-op fallback. */
    private boolean canBaritone() {
        return !(PathManagers.get() instanceof NopPathManager);
    }

    /**
     * Path to within {@code range} blocks of {@code pos} using Baritone's GoalNear.
     * Disables block breaking/placing so Baritone doesn't destroy the environment.
     * Falls back to PathManagers.moveTo() if direct Baritone API isn't available.
     */
    private void baritoneGoalNear(BlockPos pos, int range) {
        if (!BaritoneUtils.IS_AVAILABLE) {
            PathManagers.get().moveTo(pos);
            return;
        }
        var settings = BaritoneAPI.getSettings();
        if (!baritoneSettingsOverridden) {
            savedAllowBreak = settings.allowBreak.value;
            savedAllowPlace = settings.allowPlace.value;
            settings.allowBreak.value = false;
            settings.allowPlace.value = false;
            baritoneSettingsOverridden = true;
        }

        BaritoneAPI.getProvider().getPrimaryBaritone()
            .getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
    }

    /** Stop pathing and restore Baritone settings. */
    private void stopBaritone() {
        PathManagers.get().stop();
        if (!BaritoneUtils.IS_AVAILABLE || !baritoneSettingsOverridden) return;
        var settings = BaritoneAPI.getSettings();
        settings.allowBreak.value = savedAllowBreak;
        settings.allowPlace.value = savedAllowPlace;
        baritoneSettingsOverridden = false;
    }

    // ── State Helpers ───────────────────────────────────────────────────────────

    private void enterState(State next) {
        log.accept(String.format("[ShulkerRefill] %s → %s", state, next));
        state = next;
        stateEnteredAt = System.currentTimeMillis();
        if (next == State.CLOSING) closeSent = false;
    }

    private long elapsed() {
        return System.currentTimeMillis() - stateEnteredAt;
    }
}
