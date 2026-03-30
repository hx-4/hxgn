package hxgn.meteor;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Consumer;

public class ShulkerRefillHandler {

    public enum PlacementDir { BELOW, FRONT, BACK }
    public enum BreakTool { MAIN_HAND, BEST_PICKAXE }

    private enum State { IDLE, OPENING, OPENED, TAKING, CLOSING, WAITING_CLOSED, BREAKING, PICKUP_WAIT }

    private static final long PLACEMENT_GRACE_MS = 600;
    private static final long OPEN_TIMEOUT_MS = 3000;
    private static final long PICKUP_TIMEOUT_MS = 5000;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final ClickDispatcher dispatcher;
    private Consumer<String> log = s -> {};

    private State state = State.IDLE;
    private BlockPos shulkerPos = null;
    private Direction shulkerFace = Direction.UP;
    private long openTimeoutStart = 0;
    private long pickupTimeoutStart = 0;
    private boolean sentOpen = false;
    private boolean swappedForBreaking = false;
    private int shulkerCountBeforePlace = 0;

    public ShulkerRefillHandler(ClickDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void setLogger(Consumer<String> logger) {
        this.log = logger != null ? logger : s -> {};
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public void reset() {
        if (swappedForBreaking) {
            InvUtils.swapBack();
            swappedForBreaking = false;
        }
        state = State.IDLE;
        shulkerPos = null;
    }

    public boolean tick(ClientPlayerEntity player, int threshold, PlacementDir placementDir, BreakTool breakTool) {
        ClientWorld world = mc.world;
        if (world == null) return false;

        return switch (state) {
            case IDLE -> {
                int dmgCount = countDamagedElytras(player);
                if (dmgCount >= threshold) {
                    // log("IDLE: %d damaged elytras >= threshold %d, not triggering", dmgCount, threshold);
                    yield false;
                }
                log.accept(String.format("IDLE: %d damaged elytras < threshold %d, searching for shulker", dmgCount, threshold));

                FindItemResult shulkerResult = findElytraShulker();
                if (!shulkerResult.found()) {
                    log.accept("IDLE: no elytra shulker found in inventory");
                    yield false;
                }
                log.accept(String.format("IDLE: found shulker at inv slot %d", shulkerResult.slot()));

                BlockPos pos = getPlacementPos(player, placementDir, world);
                if (pos == null) {
                    log.accept("IDLE: no valid placement pos (block occupied or no floor)");
                    yield false;
                }
                log.accept(String.format("IDLE: placement pos = %s", pos));

                shulkerCountBeforePlace = countShulkers(player);
                log.accept(String.format("IDLE: shulkerCountBeforePlace = %d", shulkerCountBeforePlace));

                boolean placed = BlockUtils.place(pos, shulkerResult, true, 50, true, false, true);
                log.accept(String.format("IDLE: BlockUtils.place returned %b", placed));

                if (placed) {
                    shulkerPos = pos;
                    shulkerFace = switch (placementDir) {
                        case BELOW -> Direction.UP;
                        case FRONT -> player.getHorizontalFacing().getOpposite();
                        case BACK  -> player.getHorizontalFacing();
                    };
                    state = State.OPENING;
                    openTimeoutStart = System.currentTimeMillis();
                    sentOpen = false;
                    log.accept(String.format("IDLE -> OPENING (face=%s)", shulkerFace));
                }
                yield placed;
            }

            case OPENING -> {
                long elapsed = System.currentTimeMillis() - openTimeoutStart;
                // Wait for integrated server to confirm placement before checking for air or opening
                if (elapsed < PLACEMENT_GRACE_MS) {
                    yield true;
                }
                if (world.getBlockState(shulkerPos).isAir()) {
                    log.accept("OPENING: block is air after grace (server rejected placement), back to IDLE");
                    state = State.IDLE;
                    yield false;
                }
                if (!sentOpen) {
                    log.accept(String.format("OPENING: block confirmed (%s), sending interactBlock with face=%s",
                        world.getBlockState(shulkerPos).getBlock().getName().getString(), shulkerFace));
                    Vec3d hitPos = Vec3d.ofCenter(shulkerPos).add(Vec3d.of(shulkerFace.getVector()).multiply(0.5));
                    var result = mc.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                        new BlockHitResult(hitPos, shulkerFace, shulkerPos, false));
                    player.swingHand(Hand.MAIN_HAND);
                    sentOpen = true;
                    log.accept(String.format("OPENING: interactBlock result = %s", result));
                }
                if (elapsed > OPEN_TIMEOUT_MS) {
                    log.accept("OPENING: timed out waiting for container screen, going to BREAKING");
                    state = State.BREAKING;
                    yield true;
                }
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    log.accept("OPENING -> OPENED: GenericContainerScreen detected");
                    state = State.OPENED;
                }
                yield true;
            }

            case OPENED -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen)) {
                    log.accept("OPENED: screen is no longer GenericContainerScreen (" +
                        (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()) +
                        "), going to BREAKING");
                    state = State.BREAKING;
                    yield true;
                }
                if (player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
                    int containerSlots = handler.getRows() * 9;
                    int nonEmpty = 0;
                    for (int i = 0; i < containerSlots; i++) {
                        if (!handler.getSlot(i).getStack().isEmpty()) nonEmpty++;
                    }
                    log.accept(String.format("OPENED: syncId=%d rows=%d containerSlots=%d nonEmptySlots=%d",
                        handler.syncId, handler.getRows(), containerSlots, nonEmpty));

                    boolean hasContent = nonEmpty > 0;
                    boolean timedOut = System.currentTimeMillis() - openTimeoutStart > OPEN_TIMEOUT_MS;
                    if (hasContent || timedOut) {
                        if (timedOut && !hasContent) log.accept("OPENED: timed out waiting for slot data, proceeding anyway");
                        int queued = queueElytrasFromShulker(player, handler, containerSlots);
                        log.accept(String.format("OPENED: queued %d elytra shift-clicks, going to TAKING", queued));
                        state = State.TAKING;
                    }
                } else {
                    log.accept("OPENED: currentScreenHandler is " +
                        player.currentScreenHandler.getClass().getSimpleName() +
                        " (not GenericContainerScreenHandler)");
                }
                yield true;
            }

            case TAKING -> {
                int remaining = dispatcher.size();
                if (remaining == 0) {
                    log.accept("TAKING: dispatcher empty, going to CLOSING");
                    state = State.CLOSING;
                } else {
                    log.accept(String.format("TAKING: %d clicks remaining in dispatcher", remaining));
                }
                yield true;
            }

            case CLOSING -> {
                log.accept("CLOSING: closing screen");
                player.closeHandledScreen();
                state = State.WAITING_CLOSED;
                yield true;
            }

            case WAITING_CLOSED -> {
                if (!(mc.currentScreen instanceof HandledScreen)) {
                    log.accept("WAITING_CLOSED: screen gone, going to BREAKING (breakTool=" + breakTool + ")");
                    if (breakTool == BreakTool.BEST_PICKAXE) swapToBestPickaxe(player);
                    state = State.BREAKING;
                }
                yield true;
            }

            case BREAKING -> {
                if (world.getBlockState(shulkerPos).isAir()) {
                    log.accept("BREAKING: block is air, going to PICKUP_WAIT");
                    if (swappedForBreaking) {
                        InvUtils.swapBack();
                        swappedForBreaking = false;
                    }
                    pickupTimeoutStart = System.currentTimeMillis();
                    state = State.PICKUP_WAIT;
                    yield true;
                }
                log.accept(String.format("BREAKING: block still present (%s), calling breakBlock",
                    world.getBlockState(shulkerPos).getBlock().getName().getString()));
                BlockUtils.breakBlock(shulkerPos, true);
                yield true;
            }

            case PICKUP_WAIT -> {
                int currentShulkers = countShulkers(player);
                if (currentShulkers >= shulkerCountBeforePlace) {
                    log.accept(String.format("PICKUP_WAIT: shulkers back to %d (expected %d), going to IDLE",
                        currentShulkers, shulkerCountBeforePlace));
                    state = State.IDLE;
                    yield false;
                }
                if (System.currentTimeMillis() - pickupTimeoutStart > PICKUP_TIMEOUT_MS) {
                    log.accept(String.format("PICKUP_WAIT: timed out. shulkers=%d expected=%d, going to IDLE",
                        currentShulkers, shulkerCountBeforePlace));
                    state = State.IDLE;
                    yield false;
                }
                ItemEntity dropped = findNearbyShulkerItem(world);
                if (dropped != null) {
                    Vec3d toItem = dropped.getPos().subtract(player.getPos()).multiply(1, 0, 1);
                    double dist = toItem.length();
                    log.accept(String.format("PICKUP_WAIT: item entity at %s, dist=%.2f, shulkers=%d/%d",
                        dropped.getBlockPos(), dist, currentShulkers, shulkerCountBeforePlace));
                    if (dist > 0.5) {
                        Vec3d vel = player.getVelocity();
                        player.setVelocity(vel.x + (toItem.x / dist) * 0.2,
                                           vel.y,
                                           vel.z + (toItem.z / dist) * 0.2);
                    }
                } else {
                    log.accept(String.format("PICKUP_WAIT: no item entity found within 4 blocks of %s, shulkers=%d/%d",
                        shulkerPos, currentShulkers, shulkerCountBeforePlace));
                }
                yield true;
            }
        };
    }

    private int countDamagedElytras(ClientPlayerEntity player) {
        int count = 0;
        for (Slot s : player.playerScreenHandler.slots) {
            if (s.id == 6) continue;
            if (s.getStack().getItem() == Items.ELYTRA && s.getStack().getDamage() > 0) count++;
        }
        return count;
    }

    private FindItemResult findElytraShulker() {
        return InvUtils.find(stack -> {
            if (!stack.isIn(ItemTags.SHULKER_BOXES)) return false;
            ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
            if (c == null) return false;
            return c.streamNonEmpty().anyMatch(s -> s.getItem() == Items.ELYTRA && s.getDamage() > 0);
        });
    }

    private BlockPos getPlacementPos(ClientPlayerEntity player, PlacementDir dir, ClientWorld world) {
        Direction facing = player.getHorizontalFacing();
        BlockPos pos = switch (dir) {
            case BELOW -> player.getBlockPos();
            case FRONT -> player.getBlockPos().offset(facing);
            case BACK  -> player.getBlockPos().offset(facing.getOpposite());
        };
        if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) return null;
        if (!world.getBlockState(pos).isAir()) return null;
        return pos;
    }

    // Returns number of clicks queued
    private int queueElytrasFromShulker(ClientPlayerEntity player, GenericContainerScreenHandler handler, int containerSlots) {
        int count = 0;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            log.accept(String.format("  slot[%d]: %s dmg=%d",
                i, stack.isEmpty() ? "EMPTY" : stack.getItem().toString(), stack.getDamage()));
            if (!stack.isEmpty() && stack.getItem() == Items.ELYTRA && stack.getDamage() > 0) {
                dispatcher.enqueueClick(i, true);
                count++;
            }
        }
        return count;
    }

    private ItemEntity findNearbyShulkerItem(ClientWorld world) {
        Box searchBox = new Box(shulkerPos).expand(4);
        return world.getEntitiesByClass(ItemEntity.class, searchBox,
                e -> e.getStack().isIn(ItemTags.SHULKER_BOXES))
            .stream()
            .min((a, b) -> Double.compare(
                a.squaredDistanceTo(Vec3d.ofCenter(shulkerPos)),
                b.squaredDistanceTo(Vec3d.ofCenter(shulkerPos))))
            .orElse(null);
    }

    private int countShulkers(ClientPlayerEntity player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isIn(ItemTags.SHULKER_BOXES)) count++;
        }
        return count;
    }

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
}
