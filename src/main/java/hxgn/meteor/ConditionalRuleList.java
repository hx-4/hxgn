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
        ENABLE                ("Enable"),
        DISABLE               ("Disable"),
        DISABLE_TEMPORARILY   ("Disable temporarily"),
        ENABLE_TEMPORARILY    ("Enable temporarily"),
        RE_ENABLE_SELF_AFTER  ("Re-enable self after"),
        RE_DISABLE_SELF_AFTER ("Re-disable self after");

        public final String label;
        ActionType(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Trigger mode — sub-mode for consolidated trigger types ────────────────
    // ON_ELYTRA / ON_SPRINT → START / STOP
    // ON_BLOCK              → BREAK / PLACE
    // ON_DEATH              → DIE   / RESPAWN
    // ON_HEALTH / ON_HUNGER / ON_Y → BELOW / ABOVE

    public enum TriggerMode {
        START  ("Start"),
        STOP   ("Stop"),
        BREAK  ("Break"),
        PLACE  ("Place"),
        DIE    ("Die"),
        RESPAWN("Respawn"),
        BELOW  ("Below"),
        ABOVE  ("Above");

        public final String label;
        TriggerMode(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Y-mode for elytra altitude filter ─────────────────────────────────────

    public enum YMode {
        ANY  ("Any"),
        BELOW("Below"),
        ABOVE("Above");

        public final String label;
        YMode(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Trigger types ─────────────────────────────────────────────────────────

    public enum TriggerType {
        MODULE_ON          ("Module activated"),
        MODULE_OFF         ("Module deactivated"),
        ON_LOGIN           ("On login"),
        ON_DAMAGE          ("On damage"),
        ON_ATTACK          ("On attack"),
        ON_ELYTRA          ("Elytra"),            // triggerMode: START/STOP; Y filter: triggerYMode + triggerThreshold
        ON_SPRINT          ("Sprint"),             // triggerMode: START/STOP
        ON_BLOCK           ("Block"),              // triggerMode: BREAK/PLACE; block filter: triggerText
        ON_DEATH           ("Death"),              // triggerMode: DIE/RESPAWN
        ON_HEALTH          ("Health"),             // triggerMode: BELOW/ABOVE; triggerThreshold (half-hearts)
        ON_HUNGER          ("Hunger"),             // triggerMode: BELOW/ABOVE; triggerThreshold (food level)
        ON_Y               ("Y coordinate"),       // triggerMode: BELOW/ABOVE; triggerThreshold (Y)
        ON_DIMENSION_CHANGE("On dimension change"),// triggerText (dimension ID, empty = any)
        ON_CHAT_CONTAINS   ("Chat contains");      // triggerText, triggerResponse, triggerResponseTimeout

        public final String label;
        TriggerType(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Rule ──────────────────────────────────────────────────────────────────

    public static final class ConditionalRule {
        public TriggerType   triggerType;
        public List<String>  triggerModuleIds; // MODULE_ON / MODULE_OFF only; any match fires
        public TriggerMode   triggerMode;       // sub-mode for ON_ELYTRA/SPRINT/BLOCK/DEATH/HEALTH/HUNGER/Y
        public String        triggerText;
        public int           triggerThreshold;
        public String        triggerResponse;
        public int           triggerResponseTimeout;
        public ActionType    action;
        public List<String>  targetModuleIds;
        public int           turnBackAfterSec; // *_TEMPORARILY / RE_*_SELF_AFTER only; 0 = no timer
        public boolean       revertOnTriggerOff; // restore pre-rule state when trigger reverses
        public YMode         triggerYMode;       // ON_ELYTRA only: altitude filter

        public ConditionalRule(TriggerType triggerType, List<String> triggerModuleIds,
                               TriggerMode triggerMode,
                               String triggerText, int triggerThreshold,
                               String triggerResponse, int triggerResponseTimeout,
                               ActionType action, List<String> targetModuleIds,
                               int turnBackAfterSec, boolean revertOnTriggerOff,
                               YMode triggerYMode) {
            this.triggerType              = triggerType;
            this.triggerModuleIds         = new ArrayList<>(triggerModuleIds);
            this.triggerMode              = triggerMode != null ? triggerMode : TriggerMode.START;
            this.triggerText              = triggerText != null ? triggerText : "";
            this.triggerThreshold         = triggerThreshold;
            this.triggerResponse          = triggerResponse != null ? triggerResponse : "";
            this.triggerResponseTimeout   = triggerResponseTimeout;
            this.action                   = action;
            this.targetModuleIds          = new ArrayList<>(targetModuleIds);
            this.turnBackAfterSec         = turnBackAfterSec;
            this.revertOnTriggerOff       = revertOnTriggerOff;
            this.triggerYMode             = triggerYMode != null ? triggerYMode : YMode.ANY;
        }

        public NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putString("triggerType", triggerType.name());
            NbtList triggers = new NbtList();
            for (String id : triggerModuleIds) triggers.add(NbtString.of(id));
            tag.put("triggerModules", triggers);
            tag.putString("triggerMode", triggerMode.name());
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
            tag.putString("triggerYMode", triggerYMode.name());
            return tag;
        }

        public static ConditionalRule fromNbt(NbtCompound tag) {
            // Migrate pre-consolidation trigger type names → new consolidated types + modes
            String typeStr = tag.getString("triggerType");
            TriggerMode migratedMode = null;
            switch (typeStr) {
                case "ON_ELYTRA"       -> migratedMode = TriggerMode.START;
                case "ON_ELYTRA_STOP"  -> { typeStr = "ON_ELYTRA";  migratedMode = TriggerMode.STOP;    }
                case "ON_SPRINT_START" -> { typeStr = "ON_SPRINT";   migratedMode = TriggerMode.START;   }
                case "ON_SPRINT_STOP"  -> { typeStr = "ON_SPRINT";   migratedMode = TriggerMode.STOP;    }
                case "ON_BREAK_BLOCK"  -> { typeStr = "ON_BLOCK";    migratedMode = TriggerMode.BREAK;   }
                case "ON_PLACE_BLOCK"  -> { typeStr = "ON_BLOCK";    migratedMode = TriggerMode.PLACE;   }
                case "ON_DEATH"        -> migratedMode = TriggerMode.DIE;
                case "ON_RESPAWN"      -> { typeStr = "ON_DEATH";    migratedMode = TriggerMode.RESPAWN; }
                case "ON_HEALTH_BELOW" -> { typeStr = "ON_HEALTH";   migratedMode = TriggerMode.BELOW;   }
                case "ON_HEALTH_ABOVE" -> { typeStr = "ON_HEALTH";   migratedMode = TriggerMode.ABOVE;   }
                case "ON_HUNGER_BELOW" -> { typeStr = "ON_HUNGER";   migratedMode = TriggerMode.BELOW;   }
                case "ON_HUNGER_ABOVE" -> { typeStr = "ON_HUNGER";   migratedMode = TriggerMode.ABOVE;   }
                case "ON_Y_BELOW"      -> { typeStr = "ON_Y";        migratedMode = TriggerMode.BELOW;   }
                case "ON_Y_ABOVE"      -> { typeStr = "ON_Y";        migratedMode = TriggerMode.ABOVE;   }
            }

            TriggerType type;
            try { type = TriggerType.valueOf(typeStr); }
            catch (IllegalArgumentException e) { type = TriggerType.MODULE_ON; }

            ActionType action;
            try { action = ActionType.valueOf(tag.getString("action")); }
            catch (IllegalArgumentException e) {
                // Migrate from old boolean enableTarget field
                action = tag.getBoolean("enable") ? ActionType.ENABLE : ActionType.DISABLE;
            }

            TriggerMode triggerMode = migratedMode;
            if (triggerMode == null) {
                try { triggerMode = TriggerMode.valueOf(tag.getString("triggerMode")); }
                catch (Exception e) { triggerMode = TriggerMode.START; }
            }

            YMode yMode;
            try { yMode = YMode.valueOf(tag.getString("triggerYMode")); }
            catch (Exception e) { yMode = YMode.ANY; }

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

            return new ConditionalRule(type, triggers, triggerMode,
                tag.getString("triggerText"), tag.getInt("triggerThreshold"),
                tag.getString("triggerResponse"), tag.getInt("triggerResponseTimeout"),
                action, targets,
                tag.getInt("turnBack"), tag.getBoolean("revert"), yMode);
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    public final List<ConditionalRule> rules = new ArrayList<>();

    // ── ICopyable ─────────────────────────────────────────────────────────────

    @Override
    public ConditionalRuleList set(ConditionalRuleList other) {
        rules.clear();
        for (ConditionalRule r : other.rules) {
            rules.add(new ConditionalRule(r.triggerType, r.triggerModuleIds, r.triggerMode,
                r.triggerText, r.triggerThreshold,
                r.triggerResponse, r.triggerResponseTimeout,
                r.action, r.targetModuleIds, r.turnBackAfterSec, r.revertOnTriggerOff,
                r.triggerYMode));
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
