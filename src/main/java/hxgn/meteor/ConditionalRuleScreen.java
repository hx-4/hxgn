package hxgn.meteor;

import hxgn.meteor.ConditionalRuleList.ActionType;
import hxgn.meteor.ConditionalRuleList.ConditionalRule;
import hxgn.meteor.ConditionalRuleList.TriggerMode;
import hxgn.meteor.ConditionalRuleList.TriggerType;
import hxgn.meteor.ConditionalRuleList.YMode;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPlus;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ConditionalRuleScreen extends WindowScreen {

    private final ConditionalRuleList data;

    public ConditionalRuleScreen(GuiTheme theme, ConditionalRuleList data) {
        super(theme, "Conditional Rules");
        this.data = data;
    }

    @Override
    public void initWidgets() {
        if (!data.rules.isEmpty()) {
            WTable table = add(theme.table()).expandX().widget();
            populateTable(table);
            add(theme.horizontalSeparator()).expandX();
        } else {
            add(theme.label("No rules configured."));
        }

        WButton addBtn = add(theme.button("+ Add Rule")).expandX().widget();
        addBtn.action = () -> {
            data.rules.add(new ConditionalRule(
                TriggerType.MODULE_ON, new ArrayList<>(), TriggerMode.START,
                "", 0, "", 0,
                ActionType.DISABLE, new ArrayList<>(), 0, false, YMode.ANY));
            mc.setScreen(new EditRuleScreen(theme, data, data.rules.size() - 1, this));
        };
    }

    private void populateTable(WTable table) {
        table.horizontalSpacing = 12;
        table.verticalSpacing   = 6;

        table.add(theme.label("Trigger")).expandX().padHorizontal(6).padVertical(4);
        table.add(theme.label("Action")).expandX().padHorizontal(6).padVertical(4);
        table.add(theme.label("Targets")).expandX().padHorizontal(6).padVertical(4);
        table.add(theme.label("Delay")).expandX().padHorizontal(6).padVertical(4);
        table.add(theme.label("Revert")).expandX().padHorizontal(6).padVertical(4);
        table.add(theme.label("")).widget();
        table.add(theme.label("")).widget();
        table.row();

        for (int i = 0; i < data.rules.size(); i++) {
            ConditionalRule rule = data.rules.get(i);
            final int idx = i;

            boolean hasTriggerModules = rule.triggerType == TriggerType.MODULE_ON
                                     || rule.triggerType == TriggerType.MODULE_OFF;

            String triggerLabel;
            if (hasTriggerModules) {
                String mods = rule.triggerModuleIds.isEmpty() ? "(none)"
                    : rule.triggerModuleIds.size() == 1 ? moduleTitle(rule.triggerModuleIds.get(0))
                    : rule.triggerModuleIds.size() + " modules";
                triggerLabel = mods + " · " + rule.triggerType.label;
            } else if (rule.triggerType == TriggerType.ON_ELYTRA
                    || rule.triggerType == TriggerType.ON_SPRINT) {
                String yPart = (rule.triggerType == TriggerType.ON_ELYTRA && rule.triggerYMode != YMode.ANY)
                    ? " [" + rule.triggerYMode.label + " Y=" + rule.triggerThreshold + "]" : "";
                triggerLabel = rule.triggerType.label + " " + rule.triggerMode.label + yPart;
            } else if (rule.triggerType == TriggerType.ON_BLOCK) {
                triggerLabel = rule.triggerMode.label + " block"
                    + (rule.triggerText.isEmpty() ? "" : ": " + rule.triggerText);
            } else if (rule.triggerType == TriggerType.ON_DEATH) {
                triggerLabel = rule.triggerMode.label;
            } else if (rule.triggerType == TriggerType.ON_HEALTH
                    || rule.triggerType == TriggerType.ON_HUNGER
                    || rule.triggerType == TriggerType.ON_Y) {
                triggerLabel = rule.triggerType.label + " " + rule.triggerMode.label + " " + rule.triggerThreshold;
            } else if (rule.triggerType == TriggerType.ON_CHAT_CONTAINS) {
                triggerLabel = rule.triggerText.isEmpty() ? rule.triggerType.label
                    : rule.triggerType.label + ": \"" + rule.triggerText + "\"";
            } else if (rule.triggerType == TriggerType.ON_DIMENSION_CHANGE) {
                triggerLabel = rule.triggerText.isEmpty() ? rule.triggerType.label
                    : rule.triggerType.label + ": " + rule.triggerText;
            } else {
                triggerLabel = rule.triggerType.label;
            }

            boolean isSelfAction = rule.action == ActionType.RE_ENABLE_SELF_AFTER
                                || rule.action == ActionType.RE_DISABLE_SELF_AFTER;
            String targetsLabel = isSelfAction ? "(self)"
                : rule.targetModuleIds.isEmpty() ? "(none)"
                : rule.targetModuleIds.size() == 1
                    ? moduleTitle(rule.targetModuleIds.get(0))
                    : rule.targetModuleIds.size() + " modules";

            boolean hasTurnBack = (rule.action == ActionType.DISABLE_TEMPORARILY
                                || rule.action == ActionType.ENABLE_TEMPORARILY
                                || rule.action == ActionType.RE_ENABLE_SELF_AFTER
                                || rule.action == ActionType.RE_DISABLE_SELF_AFTER)
                               && rule.turnBackAfterSec > 0;

            table.add(theme.label(triggerLabel).color(theme.textSecondaryColor())).expandX().padHorizontal(6).padVertical(4);
            table.add(theme.label(rule.action.label).color(theme.textSecondaryColor())).expandX().padHorizontal(6).padVertical(4);
            table.add(theme.label(targetsLabel).color(theme.textSecondaryColor())).expandX().padHorizontal(6).padVertical(4);
            table.add(theme.label(hasTurnBack ? rule.turnBackAfterSec + "s" : "—").color(theme.textSecondaryColor())).expandX().padHorizontal(6).padVertical(4);
            table.add(theme.label(rule.revertOnTriggerOff ? "Yes" : "No").color(theme.textSecondaryColor())).expandX().padHorizontal(6).padVertical(4);

            WButton editBtn = table.add(theme.button("Edit")).padVertical(2).widget();
            editBtn.action = () -> mc.setScreen(new EditRuleScreen(theme, data, idx, this));

            WButton removeBtn = table.add(theme.button("Remove")).padVertical(2).widget();
            removeBtn.action = () -> { data.rules.remove(idx); reload(); };

            table.row();
        }
    }

    // ── Edit screen ───────────────────────────────────────────────────────────

    public static class EditRuleScreen extends WindowScreen {

        private enum DimensionOption {
            ANY      ("Any",       ""),
            OVERWORLD("Overworld", "minecraft:overworld"),
            NETHER   ("Nether",    "minecraft:the_nether"),
            END      ("End",       "minecraft:the_end");

            final String label, key;
            DimensionOption(String label, String key) { this.label = label; this.key = key; }
            @Override public String toString() { return label; }

            static DimensionOption fromKey(String key) {
                for (DimensionOption o : values()) if (o.key.equals(key)) return o;
                return ANY;
            }
        }

        private static final TriggerMode[] MODES_START_STOP  = { TriggerMode.START, TriggerMode.STOP    };
        private static final TriggerMode[] MODES_BREAK_PLACE = { TriggerMode.BREAK, TriggerMode.PLACE   };
        private static final TriggerMode[] MODES_DIE_RESPAWN = { TriggerMode.DIE,   TriggerMode.RESPAWN };
        private static final TriggerMode[] MODES_BELOW_ABOVE = { TriggerMode.BELOW, TriggerMode.ABOVE   };

        /** Returns the valid TriggerMode options for a trigger type, or null if none. */
        private static TriggerMode[] triggerModes(TriggerType type) {
            return switch (type) {
                case ON_ELYTRA, ON_SPRINT        -> MODES_START_STOP;
                case ON_BLOCK                    -> MODES_BREAK_PLACE;
                case ON_DEATH                    -> MODES_DIE_RESPAWN;
                case ON_HEALTH, ON_HUNGER, ON_Y  -> MODES_BELOW_ABOVE;
                default                          -> null;
            };
        }

        private final ConditionalRuleList data;
        private final int idx;
        private final ConditionalRuleScreen parent;

        private TriggerType        editType;
        private final List<String> editTriggerModules;
        private TriggerMode        editTriggerMode;
        private String             editTriggerText;
        private int                editTriggerThreshold;
        private String             editTriggerResponse;
        private int                editTriggerResponseTimeout;
        private ActionType         editAction;
        private final List<String> editTargetModules;
        private int                editTurnBack;
        private boolean            editRevert;
        private YMode              editYMode;
        private DimensionOption    editDimension;

        public EditRuleScreen(GuiTheme theme, ConditionalRuleList data, int idx,
                              ConditionalRuleScreen parent) {
            super(theme, "Edit Rule");
            this.data   = data;
            this.idx    = idx;
            this.parent = parent;

            ConditionalRule r               = data.rules.get(idx);
            this.editType                   = r.triggerType;
            this.editTriggerModules         = new ArrayList<>(r.triggerModuleIds);
            this.editTriggerMode            = r.triggerMode;
            this.editTriggerText            = r.triggerText;
            this.editTriggerThreshold       = r.triggerThreshold;
            this.editTriggerResponse        = r.triggerResponse;
            this.editTriggerResponseTimeout = r.triggerResponseTimeout;
            this.editAction                 = r.action;
            this.editTargetModules          = new ArrayList<>(r.targetModuleIds);
            this.editTurnBack               = r.turnBackAfterSec;
            this.editRevert                 = r.revertOnTriggerOff;
            this.editYMode                  = r.triggerYMode;
            this.editDimension              = DimensionOption.fromKey(r.triggerText);
        }

        @Override
        public void initWidgets() {
            WTable form = add(theme.table()).expandX().widget();

            // Trigger type
            form.add(theme.label("Trigger:"));
            WDropdown<TriggerType> typeDropdown = form.add(
                theme.dropdown(TriggerType.values(), editType)).expandX().widget();
            typeDropdown.action = () -> { editType = typeDropdown.get(); reload(); };
            form.row();

            // Trigger modules (MODULE_ON / MODULE_OFF only)
            boolean hasTriggerModules = editType == TriggerType.MODULE_ON
                                     || editType == TriggerType.MODULE_OFF;
            if (hasTriggerModules) {
                addModuleSelector(form, "Trigger module(s):", editTriggerModules, "Select Trigger Modules");
            }

            // Mode dropdown (for triggers with sub-modes)
            TriggerMode[] modeOptions = triggerModes(editType);
            if (modeOptions != null) {
                // Reset editTriggerMode if it's not valid for this trigger type
                boolean modeValid = false;
                for (TriggerMode m : modeOptions) if (m == editTriggerMode) { modeValid = true; break; }
                if (!modeValid) editTriggerMode = modeOptions[0];

                form.add(theme.label("Mode:"));
                WDropdown<TriggerMode> modeDrop = form.add(
                    theme.dropdown(modeOptions, editTriggerMode)).expandX().widget();
                modeDrop.action = () -> { editTriggerMode = modeDrop.get(); reload(); };
                form.row();
            }

            // Chat fields
            if (editType == TriggerType.ON_CHAT_CONTAINS) {
                form.add(theme.label("Keyword (case-insensitive):"));
                WTextBox keywordBox = form.add(theme.textBox(editTriggerText)).minWidth(200).expandX().widget();
                keywordBox.action = () -> editTriggerText = keywordBox.get();
                form.row();

                form.add(theme.label("Auto-response (leave blank to disable):"));
                WTextBox responseBox = form.add(theme.textBox(editTriggerResponse)).minWidth(200).expandX().widget();
                responseBox.action = () -> editTriggerResponse = responseBox.get();
                form.row();

                form.add(theme.label("Response timeout (s, 0 = unlimited):"));
                WIntEdit timeoutEdit = form.add(
                    theme.intEdit(editTriggerResponseTimeout, 0, 3600, true)).expandX().widget();
                timeoutEdit.action = () -> editTriggerResponseTimeout = timeoutEdit.get();
                form.row();
            }

            // Threshold (health / hunger / Y)
            if (editType == TriggerType.ON_HEALTH) {
                form.add(theme.label("Threshold (half-hearts, 1–20):"));
                WIntEdit threshEdit = form.add(
                    theme.intEdit(editTriggerThreshold == 0 ? 10 : editTriggerThreshold, 1, 20, false))
                    .expandX().widget();
                threshEdit.action = () -> editTriggerThreshold = threshEdit.get();
                form.row();
            } else if (editType == TriggerType.ON_HUNGER) {
                form.add(theme.label("Threshold (food level, 0–20):"));
                WIntEdit threshEdit = form.add(
                    theme.intEdit(editTriggerThreshold == 0 ? 10 : editTriggerThreshold, 0, 20, false))
                    .expandX().widget();
                threshEdit.action = () -> editTriggerThreshold = threshEdit.get();
                form.row();
            } else if (editType == TriggerType.ON_Y) {
                form.add(theme.label("Threshold (Y coordinate):"));
                WIntEdit threshEdit = form.add(
                    theme.intEdit(editTriggerThreshold, -64, 320, false)).expandX().widget();
                threshEdit.action = () -> editTriggerThreshold = threshEdit.get();
                form.row();
            }

            // Block filter
            if (editType == TriggerType.ON_BLOCK) {
                form.add(theme.label("Block filter (empty = any, e.g. diamond_ore):"));
                WTextBox blockBox = form.add(theme.textBox(editTriggerText)).minWidth(200).expandX().widget();
                blockBox.action = () -> editTriggerText = blockBox.get();
                form.row();
            }

            // Dimension filter
            if (editType == TriggerType.ON_DIMENSION_CHANGE) {
                form.add(theme.label("Dimension:"));
                WDropdown<DimensionOption> dimDrop = form.add(
                    theme.dropdown(DimensionOption.values(), editDimension)).expandX().widget();
                dimDrop.action = () -> {
                    editDimension = dimDrop.get();
                    editTriggerText = editDimension.key;
                };
                form.row();
            }

            // Elytra altitude filter
            if (editType == TriggerType.ON_ELYTRA) {
                form.add(theme.label("Y filter:"));
                WDropdown<YMode> yModeDrop = form.add(
                    theme.dropdown(YMode.values(), editYMode)).expandX().widget();
                yModeDrop.action = () -> { editYMode = yModeDrop.get(); reload(); };
                form.row();
                if (editYMode != YMode.ANY) {
                    form.add(theme.label("Y threshold:"));
                    WIntEdit yEdit = form.add(
                        theme.intEdit(editTriggerThreshold, -64, 320, false)).expandX().widget();
                    yEdit.action = () -> editTriggerThreshold = yEdit.get();
                    form.row();
                }
            }

            // Action — self-targeting actions only make sense for module triggers
            boolean selfAllowed = editType == TriggerType.MODULE_ON || editType == TriggerType.MODULE_OFF;
            if (!selfAllowed && (editAction == ActionType.RE_ENABLE_SELF_AFTER
                              || editAction == ActionType.RE_DISABLE_SELF_AFTER)) {
                editAction = ActionType.ENABLE;
            }
            List<ActionType> actionChoices = new ArrayList<>();
            for (ActionType a : ActionType.values()) {
                if (!selfAllowed && (a == ActionType.RE_ENABLE_SELF_AFTER
                                  || a == ActionType.RE_DISABLE_SELF_AFTER)) continue;
                actionChoices.add(a);
            }
            form.add(theme.label("Action:"));
            WDropdown<ActionType> actionDrop = form.add(
                theme.dropdown(actionChoices.toArray(new ActionType[0]), editAction)).expandX().widget();
            actionDrop.action = () -> { editAction = actionDrop.get(); reload(); };
            form.row();

            // Target modules (hidden for self-targeting actions)
            boolean isSelfAction = editAction == ActionType.RE_ENABLE_SELF_AFTER
                                || editAction == ActionType.RE_DISABLE_SELF_AFTER;
            if (!isSelfAction) {
                addModuleSelector(form, "Target modules:", editTargetModules, "Select Target Modules");
            }

            // Delay (for *_TEMPORARILY and RE_*_SELF_AFTER actions)
            if (editAction == ActionType.DISABLE_TEMPORARILY
                    || editAction == ActionType.ENABLE_TEMPORARILY
                    || editAction == ActionType.RE_ENABLE_SELF_AFTER
                    || editAction == ActionType.RE_DISABLE_SELF_AFTER) {
                form.add(theme.label("Delay (s):"));
                WIntEdit turnEdit = form.add(
                    theme.intEdit(editTurnBack, 0, 3600, true)).expandX().widget();
                turnEdit.action = () -> editTurnBack = turnEdit.get();
                form.row();
            }

            // Revert when trigger reverses (MODULE_ON / MODULE_OFF only)
            if (hasTriggerModules) {
                form.add(theme.label("Revert when trigger reverses:"));
                WCheckbox revertBox = form.add(theme.checkbox(editRevert)).widget();
                revertBox.action = () -> editRevert = revertBox.checked;
                form.row();
            }

            add(theme.horizontalSeparator()).expandX();

            WButton saveBtn = add(theme.button("Save")).expandX().widget();
            saveBtn.action = () -> {
                boolean needsDelay = editAction == ActionType.DISABLE_TEMPORARILY
                                  || editAction == ActionType.ENABLE_TEMPORARILY
                                  || editAction == ActionType.RE_ENABLE_SELF_AFTER
                                  || editAction == ActionType.RE_DISABLE_SELF_AFTER;
                boolean needsRevert     = hasTriggerModules;
                boolean isChat          = editType == TriggerType.ON_CHAT_CONTAINS;
                boolean usesTriggerText = isChat
                                       || editType == TriggerType.ON_DIMENSION_CHANGE
                                       || editType == TriggerType.ON_BLOCK;
                boolean usesThreshold   = editType == TriggerType.ON_HEALTH
                                       || editType == TriggerType.ON_HUNGER
                                       || editType == TriggerType.ON_Y
                                       || (editType == TriggerType.ON_ELYTRA && editYMode != YMode.ANY);
                boolean usesYMode       = editType == TriggerType.ON_ELYTRA;
                List<String> savedTargets = isSelfAction ? new ArrayList<>() : editTargetModules;
                data.rules.set(idx, new ConditionalRule(
                    editType, editTriggerModules,
                    modeOptions != null ? editTriggerMode : TriggerMode.START,
                    usesTriggerText  ? editTriggerText            : "",
                    usesThreshold    ? editTriggerThreshold       : 0,
                    isChat           ? editTriggerResponse        : "",
                    isChat           ? editTriggerResponseTimeout : 0,
                    editAction, savedTargets,
                    needsDelay  ? editTurnBack : 0,
                    needsRevert ? editRevert   : false,
                    usesYMode   ? editYMode    : YMode.ANY));
                mc.setScreen(parent);
                parent.reload();
            };
        }

        private void addModuleSelector(WTable form, String label, List<String> list, String screenTitle) {
            form.add(theme.label(label));
            String btnLabel = list.isEmpty() ? "(none selected)" : list.size() + " selected";
            WButton btn = form.add(theme.button(btnLabel)).expandX().widget();
            btn.action = () -> mc.setScreen(new SelectModulesScreen(theme, screenTitle, list, this));
            form.row();
        }
    }

    // ── Module selector screen ─────────────────────────────────────────────────

    public static class SelectModulesScreen extends WindowScreen {

        private final List<String>     selected;   // module IDs — modified in place
        private final WindowScreen     returnTo;

        private String filterText = "";
        private WTable table;

        public SelectModulesScreen(GuiTheme theme, String title, List<String> selected,
                                   WindowScreen returnTo) {
            super(theme, title);
            this.selected = selected;
            this.returnTo = returnTo;
        }

        @Override
        public void initWidgets() {
            WTextBox filter = add(theme.textBox("")).minWidth(400).expandX().widget();
            filter.setFocused(true);
            filter.action = () -> {
                filterText = filter.get().trim().toLowerCase();
                table.clear();
                populateColumns();
            };

            table = add(theme.table()).expandX().widget();
            populateColumns();

            add(theme.horizontalSeparator()).expandX();
            WButton doneBtn = add(theme.button("Done")).expandX().widget();
            doneBtn.action = () -> {
                mc.setScreen(returnTo);
                returnTo.reload();
            };
        }

        private void populateColumns() {
            List<Module> all = sortedModules();
            Set<String> selectedSet = new HashSet<>(selected);

            // Left — available (not yet selected)
            WView leftView = table.add(theme.view()).top().widget();
            leftView.maxHeight = theme.scale(200);
            WTable left = leftView.add(theme.table()).expandX().widget();
            for (Module m : all) {
                if (selectedSet.contains(m.name)) continue;
                if (!filterText.isEmpty() && !m.title.toLowerCase().contains(filterText)) continue;
                left.add(theme.label(m.title)).expandX();
                WPlus add = left.add(theme.plus()).widget();
                add.action = () -> { selected.add(m.name); table.clear(); populateColumns(); };
                left.row();
            }
            if (!left.cells.isEmpty()) table.add(theme.verticalSeparator()).expandWidgetY();

            // Right — selected
            WView rightView = table.add(theme.view()).top().widget();
            rightView.maxHeight = theme.scale(200);
            WTable right = rightView.add(theme.table()).expandX().widget();
            for (Module m : all) {
                if (!selectedSet.contains(m.name)) continue;
                if (!filterText.isEmpty() && !m.title.toLowerCase().contains(filterText)) continue;
                right.add(theme.label(m.title)).expandX();
                WMinus rem = right.add(theme.minus()).widget();
                rem.action = () -> { selected.remove(m.name); table.clear(); populateColumns(); };
                right.row();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Sorted list of all loaded modules. */
    static List<Module> sortedModules() {
        return Modules.get().getAll().stream()
            .sorted(Comparator.comparing(m -> m.title))
            .toList();
    }

    /** Return the title for a stored module name, falling back to the name itself. */
    static String moduleTitle(String name) {
        Module m = Modules.get().get(name);
        return m != null ? m.title : name;
    }
}
