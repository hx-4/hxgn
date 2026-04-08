package hxgn.meteor.modules;

import hxgn.meteor.ConditionalRuleList;
import hxgn.meteor.ConditionalRuleList.ActionType;
import hxgn.meteor.ConditionalRuleList.ConditionalRule;
import hxgn.meteor.ConditionalRuleList.TriggerType;
import hxgn.meteor.HxgnAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.BreakBlockEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AutoToggle extends Module {
    // ── Info Label ────────────────────────────────────────────────────────────
    private final SettingGroup sgInfo = settings.createGroup("Info Label");

    private final Setting<Boolean> showInfo = sgInfo.add(new BoolSetting.Builder()
        .name("show-info")
        .description("Show rule and timer counts on the Active Modules HUD")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> shorthandInfo = sgInfo.add(new BoolSetting.Builder()
        .name("shorthand-info")
        .description("Compact format: rules|pending")
        .defaultValue(false)
        .build());

    // ── Conditional Rules ─────────────────────────────────────────────────────
    private final SettingGroup sgConditional = settings.createGroup("Conditional Rules");

    private final Setting<ConditionalRuleList> conditionalRules = sgConditional.add(
        new GenericSetting.Builder<ConditionalRuleList>()
            .name("rules")
            .description("Event-driven module toggles with optional timers and auto-revert")
            .defaultValue(new ConditionalRuleList())
            .build());

    // ── Smart Totem ───────────────────────────────────────────────────────────
    private final SettingGroup sgSmartTotem = settings.createGroup("Smart Totem");

    private final Setting<Boolean> smartTotem = sgSmartTotem.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Automatically enable FutureTotem when your health is low")
        .defaultValue(false)
        .build());

    private final Setting<Integer> smartTotemThreshold = sgSmartTotem.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("Enable FutureTotem when health drops at or below this value (half-hearts, 20 = full health)")
        .defaultValue(10)
        .sliderRange(1, 20)
        .visible(smartTotem::get)
        .build());

    private final Setting<Boolean> fallPrediction = sgSmartTotem.add(new BoolSetting.Builder()
        .name("fall-prediction")
        .description("Enable FutureTotem when predicted fall damage would be dangerous")
        .defaultValue(false)
        .build());

    private final Setting<Integer> fallHealthPercent = sgSmartTotem.add(new IntSetting.Builder()
        .name("fall-health-%")
        .description("Trigger when estimated fall damage exceeds this % of current HP + absorption (100 = only if lethal)")
        .defaultValue(90)
        .sliderRange(1, 100)
        .visible(fallPrediction::get)
        .build());

    // ── State ─────────────────────────────────────────────────────────────────

    private final Map<Module, Boolean> lastKnownState  = new HashMap<>();
    // Pending timed enables/disables from *_TEMPORARILY rule actions
    private final Map<Module, Long>    pendingEnables  = new HashMap<>();
    private final Map<Module, Long>    pendingDisables = new HashMap<>();
    // Saved pre-rule target states for rules with revertOnTriggerOff, keyed by rule identity
    private final Map<ConditionalRule, Map<Module, Boolean>> pendingReverts = new HashMap<>();

    // Guard against re-entrant ActiveModulesChangedEvent fired by our own toggle() calls
    private boolean processingModuleChange = false;

    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();

    private boolean wasElytra    = false;
    private boolean wasSprinting = false;
    private boolean wasDead      = false;
    private float   prevHealth   = -1f;
    // Tracks which ON_HEALTH_BELOW rules are "armed" (health was above threshold, waiting to cross down)
    private final Set<ConditionalRule> healthThresholdArmed = new HashSet<>();
    // Per-rule timestamp of the last auto-response sent (for ON_CHAT_CONTAINS timeout)
    private final Map<ConditionalRule, Long> lastResponseSent = new HashMap<>();

    public AutoToggle() {
        super(HxgnAddon.CATEGORY, "auto-toggle",
              "Toggles modules on and off automatically based on triggers and timers");
        runInMainMenu = true;
    }

    @Override
    public String getInfoString() {
        if (!showInfo.get()) return null;

        int rules   = conditionalRules.get().rules.size();
        int pending = pendingEnables.size() + pendingDisables.size() + pendingReverts.size();

        if (shorthandInfo.get()) return rules + "|" + pending;

        String info = rules + (rules == 1 ? " rule" : " rules");
        if (pending == 1) {
            long fireAt = -1;
            if (!pendingEnables.isEmpty())  fireAt = pendingEnables.values().iterator().next();
            else if (!pendingDisables.isEmpty()) fireAt = pendingDisables.values().iterator().next();

            if (fireAt > 0) {
                double secsLeft = Math.max(0, (fireAt - System.currentTimeMillis()) / 1000.0);
                info += String.format(" | %.1fs", secsLeft);
            } else {
                info += " | 1 pending";
            }
        } else if (pending > 1) {
            info += " | " + pending + " pending";
        }
        return info;
    }

    @Override
    public void onActivate() {
        lastKnownState.clear();
        pendingEnables.clear();
        pendingDisables.clear();
        pendingReverts.clear();
        healthThresholdArmed.clear();
        lastResponseSent.clear();
        wasElytra    = mc.player != null && mc.player.isGliding();
        wasSprinting = mc.player != null && mc.player.isSprinting();
        wasDead      = mc.player != null && mc.player.getHealth() <= 0;
        prevHealth   = mc.player != null ? mc.player.getHealth() : -1f;

        // Seed MODULE_ON / MODULE_OFF trigger states
        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != TriggerType.MODULE_ON && rule.triggerType != TriggerType.MODULE_OFF) continue;
            for (String id : rule.triggerModuleIds) {
                Module m = Modules.get().get(id);
                if (m != null) lastKnownState.putIfAbsent(m, m.isActive());
            }
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        if (processingModuleChange) return;
        processingModuleChange = true;
        try {
            handleModuleChange();
        } finally {
            processingModuleChange = false;
        }
    }

    private void handleModuleChange() {
        List<ConditionalRule> rules = conditionalRules.get().rules;

        // Build trigger cache for MODULE_ON/OFF rules; detect rising and falling edges.
        // Each unique ID is resolved and state-tracked exactly once to prevent duplicate
        // firings when multiple rules share a trigger module.
        Map<String, Module> triggerCache  = new HashMap<>();
        Set<Module> justActivated   = new HashSet<>();
        Set<Module> justDeactivated = new HashSet<>();

        for (ConditionalRule rule : rules) {
            if (rule.triggerType != TriggerType.MODULE_ON && rule.triggerType != TriggerType.MODULE_OFF) continue;
            for (String id : rule.triggerModuleIds) {
                if (triggerCache.containsKey(id)) continue;
                Module trigger = Modules.get().get(id);
                triggerCache.put(id, trigger);
                if (trigger == null) continue;
                boolean now  = trigger.isActive();
                Boolean prev = lastKnownState.get(trigger);
                lastKnownState.put(trigger, now);
                if (prev == null) continue;
                if ( now && !prev) justActivated.add(trigger);
                if (!now &&  prev) justDeactivated.add(trigger);
            }
        }

        // Fire MODULE_ON rules on rising edge; MODULE_OFF rules on falling edge
        if (!justActivated.isEmpty() || !justDeactivated.isEmpty()) {
            for (ConditionalRule rule : rules) {
                Set<Module> edge = rule.triggerType == TriggerType.MODULE_ON  ? justActivated
                                 : rule.triggerType == TriggerType.MODULE_OFF ? justDeactivated
                                 : null;
                if (edge == null || edge.isEmpty()) continue;
                boolean shouldFire = rule.triggerModuleIds.stream()
                    .map(triggerCache::get)
                    .anyMatch(edge::contains);
                if (!shouldFire) continue;
                applyRuleToTargets(rule);
            }
        }

        // Revert: MODULE_ON rule reverts when no trigger is active anymore;
        //         MODULE_OFF rule reverts when any trigger becomes active again.
        if (!pendingReverts.isEmpty()) {
            pendingReverts.entrySet().removeIf(entry -> {
                ConditionalRule rule = entry.getKey();
                boolean shouldRevert;
                if (rule.triggerType == TriggerType.MODULE_ON) {
                    shouldRevert = rule.triggerModuleIds.stream()
                        .map(triggerCache::get).filter(m -> m != null).noneMatch(Module::isActive);
                } else {
                    shouldRevert = rule.triggerModuleIds.stream()
                        .map(triggerCache::get).filter(m -> m != null).anyMatch(Module::isActive);
                }
                if (shouldRevert)
                    for (Map.Entry<Module, Boolean> r : entry.getValue().entrySet())
                        restoreState(r.getKey(), r.getValue());
                return shouldRevert;
            });
        }
    }

    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        fireRules(TriggerType.ON_ATTACK);
    }

    @EventHandler
    private void onBreakBlock(BreakBlockEvent event) {
        fireRules(TriggerType.ON_BREAK_BLOCK);
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;
        String text = event.getMessage().getString().toLowerCase();
        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != TriggerType.ON_CHAT_CONTAINS) continue;
            if (!rule.triggerText.isEmpty() && !text.contains(rule.triggerText.toLowerCase())) continue;

            applyRuleToTargets(rule);

            if (!rule.triggerResponse.isEmpty()) {
                long now = System.currentTimeMillis();
                long last = lastResponseSent.getOrDefault(rule, 0L);
                if (rule.triggerResponseTimeout <= 0 || now - last >= (long) rule.triggerResponseTimeout * 1000L) {
                    lastResponseSent.put(rule, now);
                    ChatUtils.sendPlayerMsg(rule.triggerResponse);
                }
            }
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        fireRules(TriggerType.ON_LOGIN);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Timed enables/disables from *_TEMPORARILY actions
        if (!pendingEnables.isEmpty() || !pendingDisables.isEmpty()) {
            long now = System.currentTimeMillis();
            pendingEnables.entrySet().removeIf(entry -> {
                if (now >= entry.getValue()) { enable(entry.getKey()); return true; }
                return false;
            });
            pendingDisables.entrySet().removeIf(entry -> {
                if (now >= entry.getValue()) {
                    Module m = entry.getKey();
                    if (m.isActive()) m.toggle();
                    return true;
                }
                return false;
            });
        }

        // Elytra (rising edge: glide start)
        boolean elytra = mc.player.isGliding();
        if (elytra && !wasElytra) fireRules(TriggerType.ON_ELYTRA);
        wasElytra = elytra;

        // Sprint (rising edge)
        boolean sprinting = mc.player.isSprinting();
        if (sprinting && !wasSprinting) fireRules(TriggerType.ON_SPRINT_START);
        wasSprinting = sprinting;

        float health = mc.player.getHealth();

        // Death (rising edge: health hits 0)
        boolean dead = health <= 0;
        if (dead && !wasDead) fireRules(TriggerType.ON_DEATH);
        wasDead = dead;

        // Damage
        if (prevHealth >= 0f && health < prevHealth) fireRules(TriggerType.ON_DAMAGE);
        prevHealth = health;

        // Health below threshold — edge-triggered per rule:
        // arms when health rises above threshold, fires once when it drops back to/below it
        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != TriggerType.ON_HEALTH_BELOW) continue;
            if (health > rule.triggerThreshold) {
                healthThresholdArmed.add(rule);
            } else if (healthThresholdArmed.remove(rule)) {
                applyRuleToTargets(rule);
            }
        }

        // Smart Totem: health threshold and fall prediction
        if (smartTotem.get() || fallPrediction.get()) {
            FutureTotem ft = Modules.get().get(FutureTotem.class);
            if (ft != null && !ft.isActive()) {
                if (smartTotem.get() && mc.player.getHealth() <= smartTotemThreshold.get()) enable(ft);
                else if (fallPrediction.get() && isLethalFallPredicted()) enable(ft);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyRuleToTargets(ConditionalRule rule) {
        for (String targetId : rule.targetModuleIds) {
            Module target = Modules.get().get(targetId);
            if (target != null) applyRule(rule, target);
        }
    }

    private void fireRules(TriggerType type) {
        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != type) continue;
            applyRuleToTargets(rule);
        }
    }

    private void applyRule(ConditionalRule rule, Module target) {
        // Snapshot state before acting so revert can restore it.
        // putIfAbsent preserves the original snapshot if the rule fires more than once.
        if (rule.revertOnTriggerOff)
            pendingReverts.computeIfAbsent(rule, k -> new HashMap<>()).putIfAbsent(target, target.isActive());

        switch (rule.action) {
            case ENABLE -> enable(target);
            case DISABLE -> {
                if (target.isActive()) {
                    target.toggle();
                    lastKnownState.put(target, false);
                }
            }
            case DISABLE_TEMPORARILY -> {
                if (target.isActive()) target.toggle();
                if (rule.turnBackAfterSec > 0)
                    pendingEnables.put(target, System.currentTimeMillis() + (long) rule.turnBackAfterSec * 1000L);
            }
            case ENABLE_TEMPORARILY -> {
                enable(target);
                if (rule.turnBackAfterSec > 0)
                    pendingDisables.put(target, System.currentTimeMillis() + (long) rule.turnBackAfterSec * 1000L);
            }
        }
    }

    private void restoreState(Module mod, boolean wasActive) {
        if (wasActive && !mod.isActive()) enable(mod); // enable() updates lastKnownState
        else if (!wasActive && mod.isActive()) {
            mod.toggle();
            lastKnownState.put(mod, false);
        }
    }

    private boolean isLethalFallPredicted() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isOnGround()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) return false;
        if (mc.player.getAbilities().flying) return false;
        if (mc.player.isGliding()) return false;
        if (mc.player.getVelocity().y >= 0) return false;
        if (mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)) return false;
        if (mc.player.hasStatusEffect(StatusEffects.LEVITATION)) return false;

        int airBelow = airBlocksBelow();
        if (airBelow == 0) return false;

        float totalFallDist = mc.player.fallDistance + airBelow;
        float rawDamage = Math.max(0, totalFallDist - 3);
        if (rawDamage <= 0) return false;

        float effectiveHP = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        return rawDamage >= effectiveHP * (fallHealthPercent.get() / 100.0f);
    }

    private int airBlocksBelow() {
        int x = mc.player.getBlockX();
        int z = mc.player.getBlockZ();
        int startY = mc.player.getBlockPos().getY();
        int minY = mc.world.getBottomY();
        for (int y = startY; y >= minY; y--) {
            scanPos.set(x, y, z);
            if (!mc.world.getBlockState(scanPos).isAir()) return startY - y;
        }
        return startY - minY;
    }

    private void enable(Module mod) {
        if (!mod.isActive()) {
            mod.toggle();
            lastKnownState.put(mod, true);
        }
    }
}
