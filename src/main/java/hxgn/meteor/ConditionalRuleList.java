package hxgn.meteor;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.settings.IGeneric;
import meteordevelopment.meteorclient.utils.misc.ICopyable;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.util.ArrayList;
import java.util.List;

public class ConditionalRuleList implements IGeneric<ConditionalRuleList> {

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
        MODULE_ON      ("Module activated"),
        MODULE_OFF     ("Module deactivated"),
        ON_LOGIN       ("On login"),
        ON_DAMAGE      ("On damage"),
        ON_ATTACK      ("On attack"),
        ON_ELYTRA      ("On elytra start"),
        ON_SPRINT_START("On sprint start"),
        ON_BREAK_BLOCK ("On block break"),
        ON_DEATH       ("On death"),
        ON_HEALTH_BELOW("Health below"),     // extra: triggerThreshold (half-hearts)
        ON_CHAT_CONTAINS("Chat contains");   // extra: triggerText (keyword)

        public final String label;
        TriggerType(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Rule ──────────────────────────────────────────────────────────────────

    public static final class ConditionalRule {
        public TriggerType   triggerType;
        public List<String>  triggerModuleIds; // MODULE_ON / MODULE_OFF only; any match fires
        public String        triggerText;
        public int           triggerThreshold;
        public String        triggerResponse;
        public int           triggerResponseTimeout;
        public ActionType    action;
        public List<String>  targetModuleIds;
        public int           turnBackAfterSec; // *_TEMPORARILY only; 0 = no timer
        public boolean       revertOnTriggerOff; // restore pre-rule state when trigger reverses

        public ConditionalRule(TriggerType triggerType, List<String> triggerModuleIds,
                               String triggerText, int triggerThreshold,
                               String triggerResponse, int triggerResponseTimeout,
                               ActionType action, List<String> targetModuleIds,
                               int turnBackAfterSec, boolean revertOnTriggerOff) {
            this.triggerType              = triggerType;
            this.triggerModuleIds         = new ArrayList<>(triggerModuleIds);
            this.triggerText              = triggerText != null ? triggerText : "";
            this.triggerThreshold         = triggerThreshold;
            this.triggerResponse          = triggerResponse != null ? triggerResponse : "";
            this.triggerResponseTimeout   = triggerResponseTimeout;
            this.action                   = action;
            this.targetModuleIds          = new ArrayList<>(targetModuleIds);
            this.turnBackAfterSec         = turnBackAfterSec;
            this.revertOnTriggerOff       = revertOnTriggerOff;
        }

        public NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putString("triggerType", triggerType.name());
            NbtList triggers = new NbtList();
            for (String id : triggerModuleIds) triggers.add(NbtString.of(id));
            tag.put("triggerModules", triggers);
            tag.putString("triggerText", triggerText);
            tag.putInt("triggerThreshold", triggerThreshold);
            tag.putString("triggerResponse", triggerResponse);
            tag.putInt("triggerResponseTimeout", triggerResponseTimeout);
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
            try { type = TriggerType.valueOf(tag.getString("triggerType", "")); }
            catch (IllegalArgumentException e) { type = TriggerType.MODULE_ON; }

            ActionType action;
            try { action = ActionType.valueOf(tag.getString("action", "")); }
            catch (IllegalArgumentException e) {
                // Migrate from old boolean enableTarget field
                action = tag.getBoolean("enable", false) ? ActionType.ENABLE : ActionType.DISABLE;
            }

            List<String> triggers = new ArrayList<>();
            if (tag.contains("triggerModules")) {
                for (NbtElement e : tag.getListOrEmpty("triggerModules")) triggers.add(e.asString().orElse(""));
            } else {
                String old = tag.getString("triggerModuleId", "");
                if (!old.isEmpty()) triggers.add(old);
            }

            List<String> targets = new ArrayList<>();
            if (tag.contains("targets")) {
                for (NbtElement e : tag.getListOrEmpty("targets")) targets.add(e.asString().orElse(""));
            } else {
                String old = tag.getString("target", "");
                if (!old.isEmpty()) targets.add(old);
            }

            return new ConditionalRule(type, triggers,
                tag.getString("triggerText", ""), tag.getInt("triggerThreshold", 0),
                tag.getString("triggerResponse", ""), tag.getInt("triggerResponseTimeout", 0),
                action, targets,
                tag.getInt("turnBack", 0), tag.getBoolean("revert", false));
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
                r.triggerText, r.triggerThreshold,
                r.triggerResponse, r.triggerResponseTimeout,
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
        if (tag.contains("rules")) {
            for (NbtElement e : tag.getListOrEmpty("rules")) rules.add(ConditionalRule.fromNbt((NbtCompound) e));
        }
        return this;
    }

    // ── IGeneric ──────────────────────────────────────────────────────────────

    @Override
    public WidgetScreen createScreen(GuiTheme theme, GenericSetting<ConditionalRuleList> setting) {
        return new ConditionalRuleScreen(theme, this);
    }
}
