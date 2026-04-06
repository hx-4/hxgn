package hxgn.meteor;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.utils.IScreenFactory;
import meteordevelopment.meteorclient.utils.misc.ICopyable;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.util.ArrayList;
import java.util.List;

public class ConditionalRuleList implements ICopyable<ConditionalRuleList>, ISerializable<ConditionalRuleList>, IScreenFactory {

    // ── Action types ──────────────────────────────────────────────────────────

    public enum ActionType {
        ENABLE              ("Enable"),
        DISABLE             ("Disable"),
        DISABLE_TEMPORARILY ("Disable temporarily"),
        ENABLE_TEMPORARILY  ("Enable temporarily");

        public final String label;
        ActionType(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Trigger types ─────────────────────────────────────────────────────────

    public enum TriggerType {
        MODULE_ON  ("Module activated"),
        MODULE_OFF ("Module deactivated"),
        ON_LOGIN   ("On login"),
        ON_DAMAGE  ("On damage"),
        ON_ATTACK  ("On attack"),
        ON_ELYTRA  ("On elytra start");

        public final String label;
        TriggerType(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Rule ──────────────────────────────────────────────────────────────────

    public static final class ConditionalRule {
        public TriggerType   triggerType;
        public List<String>  triggerModuleIds; // MODULE_ON / MODULE_OFF only; any match fires
        public ActionType    action;
        public List<String>  targetModuleIds;
        public int           turnBackAfterSec; // *_TEMPORARILY only; 0 = no timer
        public boolean       revertOnTriggerOff; // restore pre-rule state when trigger reverses

        public ConditionalRule(TriggerType triggerType, List<String> triggerModuleIds,
                               ActionType action, List<String> targetModuleIds,
                               int turnBackAfterSec, boolean revertOnTriggerOff) {
            this.triggerType        = triggerType;
            this.triggerModuleIds   = new ArrayList<>(triggerModuleIds);
            this.action             = action;
            this.targetModuleIds    = new ArrayList<>(targetModuleIds);
            this.turnBackAfterSec   = turnBackAfterSec;
            this.revertOnTriggerOff = revertOnTriggerOff;
        }

        public NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putString("triggerType", triggerType.name());
            NbtList triggers = new NbtList();
            for (String id : triggerModuleIds) triggers.add(NbtString.of(id));
            tag.put("triggerModules", triggers);
            tag.putString("action", action.name());
            NbtList targets = new NbtList();
            for (String id : targetModuleIds) targets.add(NbtString.of(id));
            tag.put("targets", targets);
            tag.putInt("turnBack", turnBackAfterSec);
            tag.putBoolean("revert", revertOnTriggerOff);
            return tag;
        }

        public static ConditionalRule fromNbt(NbtCompound tag) {
            TriggerType type;
            try { type = TriggerType.valueOf(tag.getString("triggerType")); }
            catch (IllegalArgumentException e) { type = TriggerType.MODULE_ON; }

            ActionType action;
            try { action = ActionType.valueOf(tag.getString("action")); }
            catch (IllegalArgumentException e) {
                // Migrate from old boolean enableTarget field
                action = tag.getBoolean("enable") ? ActionType.ENABLE : ActionType.DISABLE;
            }

            List<String> triggers = new ArrayList<>();
            if (tag.contains("triggerModules", NbtElement.LIST_TYPE)) {
                NbtList tlist = tag.getList("triggerModules", NbtElement.STRING_TYPE);
                for (NbtElement e : tlist) triggers.add(e.asString());
            } else {
                String old = tag.getString("triggerModuleId");
                if (!old.isEmpty()) triggers.add(old);
            }

            List<String> targets = new ArrayList<>();
            if (tag.contains("targets", NbtElement.LIST_TYPE)) {
                NbtList tlist = tag.getList("targets", NbtElement.STRING_TYPE);
                for (NbtElement e : tlist) targets.add(e.asString());
            } else {
                String old = tag.getString("target");
                if (!old.isEmpty()) targets.add(old);
            }

            return new ConditionalRule(type, triggers, action, targets,
                tag.getInt("turnBack"), tag.getBoolean("revert"));
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    public final List<ConditionalRule> rules = new ArrayList<>();

    // ── ICopyable ─────────────────────────────────────────────────────────────

    @Override
    public ConditionalRuleList set(ConditionalRuleList other) {
        rules.clear();
        for (ConditionalRule r : other.rules) {
            rules.add(new ConditionalRule(r.triggerType, r.triggerModuleIds,
                r.action, r.targetModuleIds, r.turnBackAfterSec, r.revertOnTriggerOff));
        }
        return this;
    }

    @Override
    public ConditionalRuleList copy() {
        ConditionalRuleList copy = new ConditionalRuleList();
        copy.set(this);
        return copy;
    }

    // ── ISerializable ─────────────────────────────────────────────────────────

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        NbtList list = new NbtList();
        for (ConditionalRule r : rules) list.add(r.toNbt());
        tag.put("rules", list);
        return tag;
    }

    @Override
    public ConditionalRuleList fromTag(NbtCompound tag) {
        rules.clear();
        if (tag.contains("rules", NbtElement.LIST_TYPE)) {
            NbtList list = tag.getList("rules", NbtElement.COMPOUND_TYPE);
            for (NbtElement e : list) rules.add(ConditionalRule.fromNbt((NbtCompound) e));
        }
        return this;
    }

    // ── IScreenFactory ────────────────────────────────────────────────────────

    @Override
    public WidgetScreen createScreen(GuiTheme theme) {
        return new ConditionalRuleScreen(theme, this);
    }
}
