package hxgn.meteor;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.InvUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public class ClickDispatcher {

    private final Setting<Integer> delay;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private long lastClickTime = 0L;

    public ClickDispatcher(Setting<Integer> delay) {
        this.delay = delay;
    }

    // slotId is a container/screen-handler slot ID (as in Slot.id), used directly with InvUtils.slotId()
    public void enqueueClick(int slotId, boolean shift) {
        if (shift) {
            queue.add(() -> InvUtils.shiftClick().slotId(slotId));
        } else {
            queue.add(() -> InvUtils.click().slotId(slotId));
        }
    }

    public void enqueueSwap(int sourceSlotId, int targetSlotId) {
        enqueueClick(sourceSlotId, false);
        enqueueClick(targetSlotId, false);
        enqueueClick(sourceSlotId, false);
    }

    public void drain() {
        if (!queue.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - lastClickTime >= delay.get()) {
                Objects.requireNonNull(queue.poll()).run();
                lastClickTime = now;
            }
        }
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void clear() {
        queue.clear();
    }
}
