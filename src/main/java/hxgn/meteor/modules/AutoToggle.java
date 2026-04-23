package hxgn.meteor.modules;

import hxgn.meteor.ConditionalRuleList;
import hxgn.meteor.ConditionalRuleList.ActionType;
import hxgn.meteor.ConditionalRuleList.ConditionalRule;
import hxgn.meteor.ConditionalRuleList.TriggerMode;
import hxgn.meteor.ConditionalRuleList.TriggerType;
import hxgn.meteor.ConditionalRuleList.YMode;
import hxgn.meteor.HxgnAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.BreakBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.PlaceBlockEvent;
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
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // Pending timed enables/disables from *_TEMPORARILY / RE_*_SELF_AFTER actions
    private final Map<Module, Long>    pendingEnables  = new HashMap<>();
    private final Map<Module, Long>    pendingDisables = new HashMap<>();
    // Saved pre-rule target states for rules with revertOnTriggerOff, keyed by rule identity
    private final Map<ConditionalRule, Map<Module, Boolean>> pendingReverts = new HashMap<>();

    // Guard against re-entrant ActiveModulesChangedEvent fired by our own toggle() calls
    private boolean processingModuleChange = false;

    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();

    private boolean prevElytra    = false;
    private boolean prevSprinting = false;
    private boolean prevDead      = false;
    private float   prevHealth   = -1f;

    // Edge-trigger armed sets: rule is "armed" when on the safe side of the threshold,
    // fires once when it crosses to the trigger side.
    private final Set<ConditionalRule> healthArmed = new HashSet<>();
    private final Set<ConditionalRule> hungerArmed = new HashSet<>();
    private final Set<ConditionalRule> yArmed      = new HashSet<>();

    // Per-rule timestamp of the last auto-response sent (for ON_CHAT_CONTAINS timeout)
    private final Map<ConditionalRule, Long> lastResponseSent = new HashMap<>();
    private String prevDimension = null;

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
            if (!pendingEnables.isEmpty())       fireAt = pendingEnables.values().iterator().next();
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
        healthArmed.clear();
        hungerArmed.clear();
        yArmed.clear();
        lastResponseSent.clear();
        prevDimension = mc.world != null ? mc.world.getRegistryKey().getValue().toString() : null;
        prevElytra    = mc.player != null && mc.player.isGliding();
        prevSprinting = mc.player != null && mc.player.isSprinting();
        prevDead      = mc.player != null && mc.player.getHealth() <= 0;
        prevHealth   = mc.player != null ? mc.player.getHealth() : -1f;

        // Seed MODULE trigger states
        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != TriggerType.MODULE) continue;
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

        // Build trigger cache for MODULE rules; detect rising and falling edges.
        // Each unique ID is resolved and state-tracked exactly once to prevent duplicate
        // firings when multiple rules share a trigger module.
        Map<String, Module> triggerCache  = new HashMap<>();
        Set<Module> justActivated   = new HashSet<>();
        Set<Module> justDeactivated = new HashSet<>();

        for (ConditionalRule rule : rules) {
            if (rule.triggerType != TriggerType.MODULE) continue;
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

        // Fire MODULE rules on edge: ACTIVATE on rising, DEACTIVATE on falling
        if (!justActivated.isEmpty() || !justDeactivated.isEmpty()) {
            for (ConditionalRule rule : rules) {
                if (rule.triggerType != TriggerType.MODULE) continue;
                Set<Module> edge = rule.triggerMode == TriggerMode.ACTIVATE   ? justActivated
                                 : rule.triggerMode == TriggerMode.DEACTIVATE ? justDeactivated
                                 : null;
                if (edge == null || edge.isEmpty()) continue;
                boolean shouldFire = rule.triggerModuleIds.stream()
                    .map(triggerCache::get)
                    .anyMatch(edge::contains);
                if (!shouldFire) continue;
                applyRuleToTargets(rule);
            }
        }

        // Revert: ACTIVATE rule reverts when no trigger is active; DEACTIVATE when any becomes active.
        if (!pendingReverts.isEmpty()) {
            pendingReverts.entrySet().removeIf(entry -> {
                ConditionalRule rule = entry.getKey();
                if (rule.triggerType != TriggerType.MODULE || !rule.revertOnTriggerOff) return false;
                boolean shouldRevert;
                if (rule.triggerMode == TriggerMode.ACTIVATE) {
                    shouldRevert = rule.triggerModuleIds.stream()
                        .map(triggerCache::get).filter(Objects::nonNull).noneMatch(Module::isActive);
                } else {
                    shouldRevert = rule.triggerModuleIds.stream()
                        .map(triggerCache::get).filter(Objects::nonNull).anyMatch(Module::isActive);
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
        fireRules(TriggerType.ON_ATTACK, null);
    }

    @EventHandler
    private void onBreakBlock(BreakBlockEvent event) {
        if (mc.world == null) return;
        String blockId = Registries.BLOCK.getId(mc.world.getBlockState(event.blockPos).getBlock()).toString();
        handleBlockEvent(TriggerMode.BREAK, blockId);
    }

    @EventHandler
    private void onPlaceBlock(PlaceBlockEvent event) {
        handleBlockEvent(TriggerMode.PLACE, Registries.BLOCK.getId(event.block).toString());
    }

    private void handleBlockEvent(TriggerMode mode, String blockId) {
        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != TriggerType.ON_BLOCK || rule.triggerMode != mode) continue;
            if (!rule.triggerText.isEmpty() && !blockId.contains(rule.triggerText.toLowerCase())) continue;
            applyRuleToTargets(rule);
        }
    }

    private static final String WHISPER_MARKER     = " whispers to you: ";
    private static final String WHISPER_OUT_MARKER = "you whisper to ";

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;
        String rawText    = event.getMessage().getString();
        String text       = rawText.toLowerCase();
        String playerName = chatPlayerName(text);
        String msgText    = chatMessageText(text);
        String ownName    = mc.player.getName().getString().toLowerCase();
        if (playerName.equals(ownName)) return;

        // Filter outgoing whisper echoes: "You whisper to <name>: <msg>" (no <> prefix)
        if (playerName.isEmpty() && text.contains(WHISPER_OUT_MARKER)) return;

        // Whisper detection: only attempt if no <name> prefix present (avoids false positives)
        int     whisperIdx        = playerName.isEmpty() ? text.indexOf(WHISPER_MARKER) : -1;
        boolean isWhisper         = whisperIdx > 0;
        String  whisperSenderOrig = "";
        String  whisperSenderLow  = "";
        String  whisperMsg        = "";
        if (isWhisper) {
            String beforeMarker = rawText.substring(0, whisperIdx).trim();
            int    lastSpace    = beforeMarker.lastIndexOf(' ');
            whisperSenderOrig = lastSpace >= 0 ? beforeMarker.substring(lastSpace + 1) : beforeMarker;
            whisperSenderLow  = whisperSenderOrig.toLowerCase();
            whisperMsg        = text.substring(whisperIdx + WHISPER_MARKER.length()).trim();
            if (whisperSenderLow.equals(ownName)) return;
        }

        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != TriggerType.ON_CHAT_CONTAINS) continue;
            if (isWhisper && !rule.chatIncludeWhispers) continue;
            String keyword     = rule.triggerText.toLowerCase();
            String matchPlayer = isWhisper ? whisperSenderLow : playerName;
            String matchMsg    = isWhisper ? whisperMsg        : msgText;

            if (rule.chatMatchPlayerOnly) {
                if (keyword.isEmpty() || !matchPlayer.contains(keyword)) continue;
            } else if (rule.chatMatchEntire) {
                if (!matchMsg.equals(keyword)) continue;
            } else {
                String matchFull = isWhisper ? whisperMsg : text;
                if (!keyword.isEmpty() && !matchFull.contains(keyword)) continue;
            }

            applyRuleToTargets(rule);

            if (!rule.triggerResponse.isEmpty()) {
                long now = System.currentTimeMillis();
                long last = lastResponseSent.getOrDefault(rule, 0L);
                if (rule.triggerResponseTimeout <= 0 || now - last >= (long) rule.triggerResponseTimeout * 1000L) {
                    lastResponseSent.put(rule, now);
                    String response = (rule.chatReplyWithWhisper && isWhisper)
                        ? "/w " + whisperSenderOrig + " " + rule.triggerResponse
                        : rule.triggerResponse;
                    ChatUtils.sendPlayerMsg(response);
                }
            }
        }
    }

    private static String chatPlayerName(String text) {
        int start = text.indexOf('<');
        int end   = text.indexOf('>');
        if (start >= 0 && end > start) return text.substring(start + 1, end);
        return "";
    }

    private static String chatMessageText(String text) {
        int end = text.indexOf('>');
        if (end >= 0 && end + 1 < text.length()) return text.substring(end + 1).trim();
        return text;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        fireRules(TriggerType.ON_LOGIN, null);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        List<ConditionalRule> rules = conditionalRules.get().rules;

        // Drop stale references left by rule edits (rule objects replaced when saved)
        if (!healthArmed.isEmpty())      healthArmed.retainAll(rules);
        if (!hungerArmed.isEmpty())      hungerArmed.retainAll(rules);
        if (!yArmed.isEmpty())           yArmed.retainAll(rules);
        if (!lastResponseSent.isEmpty()) lastResponseSent.keySet().retainAll(rules);
        if (!pendingReverts.isEmpty())   pendingReverts.keySet().retainAll(rules);

        // Timed enables/disables from *_TEMPORARILY and RE_*_SELF_AFTER actions
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

        double y = mc.player.getY();

        // Elytra (start / stop edges, both support optional altitude filter)
        boolean elytra = mc.player.isGliding();
        if (elytra != prevElytra) {
            TriggerMode edgeMode   = elytra ? TriggerMode.START : TriggerMode.STOP;
            TriggerMode revertMode = elytra ? TriggerMode.STOP  : TriggerMode.START;
            for (ConditionalRule rule : rules) {
                if (rule.triggerType != TriggerType.ON_ELYTRA || rule.triggerMode != edgeMode) continue;
                if (!checkYMode(rule, y)) continue;
                applyRuleToTargets(rule);
            }
            revertRulesWithMode(TriggerType.ON_ELYTRA, revertMode);
        }
        prevElytra = elytra;

        // Sprint (start / stop edges)
        boolean sprinting = mc.player.isSprinting();
        if (sprinting != prevSprinting) {
            TriggerMode edgeMode   = sprinting ? TriggerMode.START : TriggerMode.STOP;
            TriggerMode revertMode = sprinting ? TriggerMode.STOP  : TriggerMode.START;
            fireRules(TriggerType.ON_SPRINT, edgeMode);
            revertRulesWithMode(TriggerType.ON_SPRINT, revertMode);
        }
        prevSprinting = sprinting;

        float health = mc.player.getHealth();

        // Death / Respawn
        boolean dead = health <= 0;
        if (dead  && !prevDead) { fireRules(TriggerType.ON_DEATH, TriggerMode.DIE);     revertRulesWithMode(TriggerType.ON_DEATH, TriggerMode.RESPAWN); }
        if (!dead && prevDead)  { fireRules(TriggerType.ON_DEATH, TriggerMode.RESPAWN); revertRulesWithMode(TriggerType.ON_DEATH, TriggerMode.DIE);     }
        prevDead = dead;

        // Damage
        if (prevHealth >= 0f && health < prevHealth) fireRules(TriggerType.ON_DAMAGE, null);
        prevHealth = health;

        // Health / Hunger / Y threshold (edge-triggered per rule; mode selects BELOW or ABOVE)
        applyThresholdTrigger(rules, TriggerType.ON_HEALTH, health, healthArmed, false);
        applyThresholdTrigger(rules, TriggerType.ON_HUNGER, mc.player.getHungerManager().getFoodLevel(), hungerArmed, false);
        applyThresholdTrigger(rules, TriggerType.ON_Y, y, yArmed, elytra);

        // Dimension change
        if (mc.world != null) {
            String dim = mc.world.getRegistryKey().getValue().toString();
            if (prevDimension != null && !dim.equals(prevDimension)) {
                for (ConditionalRule rule : rules) {
                    if (rule.triggerType != TriggerType.ON_DIMENSION_CHANGE) continue;
                    if (!rule.triggerText.isEmpty() && !rule.triggerText.equals(dim)) continue;
                    applyRuleToTargets(rule);
                }
            }
            prevDimension = dim;
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
        boolean isSelf = rule.action == ActionType.RE_ENABLE_SELF_AFTER
                      || rule.action == ActionType.RE_DISABLE_SELF_AFTER;
        List<String> ids = isSelf ? rule.triggerModuleIds : rule.targetModuleIds;
        for (String targetId : ids) {
            Module target = Modules.get().get(targetId);
            if (target != null) applyRule(rule, target);
        }
    }

    private void fireRules(TriggerType type, TriggerMode mode) {
        for (ConditionalRule rule : conditionalRules.get().rules) {
            if (rule.triggerType != type) continue;
            if (mode != null && rule.triggerMode != mode) continue;
            applyRuleToTargets(rule);
        }
    }

    // armedSet.add returns true only on re-arm (trigger→safe transition), which is when revert fires.
    // isGliding is only meaningful for ON_Y rules with triggerElytraOnly; pass false for health/hunger.
    private void applyThresholdTrigger(List<ConditionalRule> rules, TriggerType type, double value,
                                       Set<ConditionalRule> armedSet, boolean isGliding) {
        for (ConditionalRule rule : rules) {
            if (rule.triggerType != type) continue;
            if (rule.triggerElytraOnly && !isGliding) {
                armedSet.remove(rule); // reset so it re-arms cleanly when gliding resumes
                continue;
            }
            boolean shouldArm = rule.triggerMode == TriggerMode.BELOW
                ? value > rule.triggerThreshold : value < rule.triggerThreshold;
            if (shouldArm) {
                if (armedSet.add(rule) && rule.revertOnTriggerOff) revertRule(rule);
            } else if (armedSet.remove(rule)) {
                applyRuleToTargets(rule);
            }
        }
    }

    private void revertRule(ConditionalRule rule) {
        Map<Module, Boolean> snapshot = pendingReverts.remove(rule);
        if (snapshot != null)
            for (Map.Entry<Module, Boolean> entry : snapshot.entrySet())
                restoreState(entry.getKey(), entry.getValue());
    }

    private void revertRulesWithMode(TriggerType type, TriggerMode mode) {
        if (pendingReverts.isEmpty()) return;
        pendingReverts.entrySet().removeIf(entry -> {
            ConditionalRule rule = entry.getKey();
            if (rule.triggerType != type || rule.triggerMode != mode || !rule.revertOnTriggerOff) return false;
            for (Map.Entry<Module, Boolean> r : entry.getValue().entrySet())
                restoreState(r.getKey(), r.getValue());
            return true;
        });
    }

    private void applyRule(ConditionalRule rule, Module target) {
        // Snapshot state before acting so revert can restore it.
        // putIfAbsent preserves the original snapshot if the rule fires more than once.
        if (rule.revertOnTriggerOff)
            pendingReverts.computeIfAbsent(rule, k -> new HashMap<>()).putIfAbsent(target, target.isActive());

        switch (rule.action) {
            case ENABLE -> enable(target);
            case DISABLE -> disable(target);
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
            case RE_ENABLE_SELF_AFTER -> {
                if (rule.turnBackAfterSec > 0)
                    pendingEnables.put(target, System.currentTimeMillis() + (long) rule.turnBackAfterSec * 1000L);
            }
            case RE_DISABLE_SELF_AFTER -> {
                if (rule.turnBackAfterSec > 0)
                    pendingDisables.put(target, System.currentTimeMillis() + (long) rule.turnBackAfterSec * 1000L);
            }
        }
    }

    private void restoreState(Module mod, boolean wasActive) {
        if (wasActive && !mod.isActive()) enable(mod);
        else if (!wasActive && mod.isActive()) disable(mod);
    }

    private boolean checkYMode(ConditionalRule rule, double y) {
        if (rule.triggerYMode == YMode.BELOW) return y < rule.triggerThreshold;
        if (rule.triggerYMode == YMode.ABOVE) return y > rule.triggerThreshold;
        return true;
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

    private void disable(Module mod) {
        if (mod.isActive()) {
            mod.toggle();
            lastKnownState.put(mod, false);
        }
    }
}
