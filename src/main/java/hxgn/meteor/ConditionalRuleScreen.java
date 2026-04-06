package hxgn.meteor;

import hxgn.meteor.ConditionalRuleList.ActionType;
import hxgn.meteor.ConditionalRuleList.ConditionalRule;
import hxgn.meteor.ConditionalRuleList.TriggerType;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
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
                TriggerType.MODULE_ON, new ArrayList<>(), ActionType.DISABLE,
                new ArrayList<>(), 0, false));
            mc.setScreen(new EditRuleScreen(theme, data, data.rules.size() - 1, this));
        };
    }

    private void populateTable(WTable table) {
        table.add(theme.label("Trigger")).expandX();
        table.add(theme.label("Action")).expandX();
        table.add(theme.label("Targets")).expandX();
        table.add(theme.label("Delay")).expandX();
        table.add(theme.label("Revert")).expandX();
        table.add(theme.label("")).widget();
        table.add(theme.label("")).widget();
        table.row();

        for (int i = 0; i < data.rules.size(); i++) {
            ConditionalRule rule = data.rules.get(i);
            final int idx = i;

            boolean hasTriggerModules = rule.triggerType == TriggerType.MODULE_ON
                                     || rule.triggerType == TriggerType.MODULE_OFF;

            String triggerLabel = hasTriggerModules
                ? (rule.triggerModuleIds.isEmpty() ? "(none)"
                   : rule.triggerModuleIds.size() == 1 ? moduleTitle(rule.triggerModuleIds.get(0))
                   : rule.triggerModuleIds.size() + " modules")
                + " · " + rule.triggerType.label
                : rule.triggerType.label;

            String targetsLabel = rule.targetModuleIds.isEmpty() ? "(none)"
                : rule.targetModuleIds.size() == 1
                    ? moduleTitle(rule.targetModuleIds.get(0))
                    : rule.targetModuleIds.size() + " modules";

            boolean hasTurnBack = (rule.action == ActionType.DISABLE_TEMPORARILY
                                || rule.action == ActionType.ENABLE_TEMPORARILY)
                               && rule.turnBackAfterSec > 0;

            table.add(theme.label(triggerLabel)).expandX();
            table.add(theme.label(rule.action.label)).expandX();
            table.add(theme.label(targetsLabel)).expandX();
            table.add(theme.label(hasTurnBack ? rule.turnBackAfterSec + "s" : "—")).expandX();
            table.add(theme.label(rule.revertOnTriggerOff ? "Yes" : "No")).expandX();

            WButton editBtn = table.add(theme.button("Edit")).widget();
            editBtn.action = () -> mc.setScreen(new EditRuleScreen(theme, data, idx, this));

            WButton removeBtn = table.add(theme.button("Remove")).widget();
            removeBtn.action = () -> { data.rules.remove(idx); reload(); };

            table.row();
        }
    }

    // ── Edit screen ───────────────────────────────────────────────────────────

    public static class EditRuleScreen extends WindowScreen {

        private final ConditionalRuleList data;
        private final int idx;
        private final ConditionalRuleScreen parent;

        private TriggerType        editType;
        private final List<String> editTriggerModules;
        private ActionType         editAction;
        private final List<String> editTargetModules;
        private int                editTurnBack;
        private boolean            editRevert;

        public EditRuleScreen(GuiTheme theme, ConditionalRuleList data, int idx,
                              ConditionalRuleScreen parent) {
            super(theme, "Edit Rule");
            this.data   = data;
            this.idx    = idx;
            this.parent = parent;

            ConditionalRule r       = data.rules.get(idx);
            this.editType           = r.triggerType;
            this.editTriggerModules = new ArrayList<>(r.triggerModuleIds);
            this.editAction         = r.action;
            this.editTargetModules  = new ArrayList<>(r.targetModuleIds);
            this.editTurnBack       = r.turnBackAfterSec;
            this.editRevert         = r.revertOnTriggerOff;
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

            // Action
            form.add(theme.label("Action:"));
            WDropdown<ActionType> actionDrop = form.add(
                theme.dropdown(ActionType.values(), editAction)).expandX().widget();
            actionDrop.action = () -> { editAction = actionDrop.get(); reload(); };
            form.row();

            // Target modules
            addModuleSelector(form, "Target modules:", editTargetModules, "Select Target Modules");

            // Delay (only for *_TEMPORARILY actions)
            if (editAction == ActionType.DISABLE_TEMPORARILY
                    || editAction == ActionType.ENABLE_TEMPORARILY) {
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
                                  || editAction == ActionType.ENABLE_TEMPORARILY;
                boolean needsRevert = editType == TriggerType.MODULE_ON
                                   || editType == TriggerType.MODULE_OFF;
                data.rules.set(idx, new ConditionalRule(
                    editType, editTriggerModules, editAction,
                    editTargetModules,
                    needsDelay   ? editTurnBack : 0,
                    needsRevert  ? editRevert   : false));
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
            WTable left = table.add(theme.table()).top().widget();
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
            WTable right = table.add(theme.table()).top().widget();
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
