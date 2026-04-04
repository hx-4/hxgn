package hxgn.meteor.modules;

import hxgn.meteor.HxgnAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoToggle extends Module {

    // ── Timer ─────────────────────────────────────────────────────────────────
    private final SettingGroup sgTimer = settings.createGroup("Timer");

    private final Setting<Boolean> timerEnabled = sgTimer.add(new BoolSetting.Builder()
        .name("enabled")
        .defaultValue(false)
        .build());

    private final Setting<List<Module>> timerModules = sgTimer.add(new ModuleListSetting.Builder()
        .name("modules")
        .description("Modules to re-enable after a delay when turned off")
        .visible(timerEnabled::get)
        .build());

    private final Setting<Integer> timerDelay = sgTimer.add(new IntSetting.Builder()
        .name("delay")
        .description("Seconds before a disabled module is re-enabled")
        .defaultValue(30)
        .sliderRange(1,60)
        .visible(timerEnabled::get)
        .build());

    // ── On Login ──────────────────────────────────────────────────────────────
    private final SettingGroup sgLogin = settings.createGroup("On Login");

    private final Setting<Boolean> loginEnabled = sgLogin.add(new BoolSetting.Builder()
        .name("enabled")
        .defaultValue(true)
        .build());

    private final Setting<List<Module>> loginEnable = sgLogin.add(new ModuleListSetting.Builder()
        .name("enable")
        .description("Modules to turn ON when joining a world or server")
        .visible(loginEnabled::get)
        .build());

    private final Setting<List<Module>> loginDisable = sgLogin.add(new ModuleListSetting.Builder()
        .name("disable")
        .description("Modules to turn OFF when joining a world or server")
        .visible(loginEnabled::get)
        .build());

    // ── On Damage ─────────────────────────────────────────────────────────────
    private final SettingGroup sgDamage = settings.createGroup("On Damage");

    private final Setting<Boolean> damageEnabled = sgDamage.add(new BoolSetting.Builder()
        .name("enabled")
        .defaultValue(true)
        .build());

    private final Setting<List<Module>> damageEnable = sgDamage.add(new ModuleListSetting.Builder()
        .name("enable")
        .description("Modules to turn ON when taking damage")
        .visible(damageEnabled::get)
        .build());

    private final Setting<List<Module>> damageDisable = sgDamage.add(new ModuleListSetting.Builder()
        .name("disable")
        .description("Modules to turn OFF when taking damage")
        .visible(damageEnabled::get)
        .build());

    // ── On Attack ─────────────────────────────────────────────────────────────
    private final SettingGroup sgAttack = settings.createGroup("On Attack");

    private final Setting<Boolean> attackEnabled = sgAttack.add(new BoolSetting.Builder()
        .name("enabled")
        .defaultValue(true)
        .build());

    private final Setting<List<Module>> attackEnable = sgAttack.add(new ModuleListSetting.Builder()
        .name("enable")
        .description("Modules to turn ON when attacking an entity")
        .visible(attackEnabled::get)
        .build());

    private final Setting<List<Module>> attackDisable = sgAttack.add(new ModuleListSetting.Builder()
        .name("disable")
        .description("Modules to turn OFF when attacking an entity")
        .visible(attackEnabled::get)
        .build());

    // ── On Elytra ─────────────────────────────────────────────────────────────
    private final SettingGroup sgElytra = settings.createGroup("On Elytra");

    private final Setting<Boolean> elytraEnabled = sgElytra.add(new BoolSetting.Builder()
        .name("enabled")
        .defaultValue(true)
        .build());

    private final Setting<List<Module>> elytraEnable = sgElytra.add(new ModuleListSetting.Builder()
        .name("enable")
        .description("Modules to turn ON when starting to glide with an elytra")
        .visible(elytraEnabled::get)
        .build());

    private final Setting<List<Module>> elytraDisable = sgElytra.add(new ModuleListSetting.Builder()
        .name("disable")
        .description("Modules to turn OFF when starting to glide with an elytra")
        .visible(elytraEnabled::get)
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
        .sliderRange(1,20)
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
        .sliderRange(1,100)
        .visible(fallPrediction::get)
        .build());

    // ── State ─────────────────────────────────────────────────────────────────

    private final Map<Module, Long>    disabledAt     = new HashMap<>();
    private final Map<Module, Boolean> lastKnownState = new HashMap<>();
    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();

    private boolean hadPlayer  = false;
    private boolean wasElytra  = false;
    private float   prevHealth = -1f;

    public AutoToggle() {
        super(HxgnAddon.CATEGORY, "auto-toggle",
              "Toggles modules on and off automatically based on triggers and timers");
        runInMainMenu = true;
    }

    @Override
    public String getInfoString() {
        if (!timerEnabled.get() || disabledAt.isEmpty()) return null;
        return disabledAt.size() + " pending";
    }

    @Override
    public void onActivate() {
        disabledAt.clear();
        lastKnownState.clear();
        hadPlayer  = mc.player != null;
        wasElytra  = mc.player != null && mc.player.isGliding();
        prevHealth = mc.player != null ? mc.player.getHealth() : -1f;

        for (Module mod : timerModules.get()) {
            lastKnownState.put(mod, mod.isActive());
            if (!mod.isActive()) disabledAt.put(mod, System.currentTimeMillis());
        }
    }

    // ── Event: any module toggled — used only for timer tracking ──────────────

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        if (!timerEnabled.get()) return;
        for (Module mod : timerModules.get()) {
            boolean now  = mod.isActive();
            Boolean prev = lastKnownState.get(mod);
            if (prev == null) {
                lastKnownState.put(mod, now);
                continue;
            }
            if (prev && !now) {
                disabledAt.put(mod, System.currentTimeMillis());
            } else if (!prev && now) {
                disabledAt.remove(mod);
            }
            lastKnownState.put(mod, now);
        }
    }

    // ── Event: attack ─────────────────────────────────────────────────────────

    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        if (!attackEnabled.get()) return;
        enableList(attackEnable.get());
        disableList(attackDisable.get());
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {

        // Login detection
        boolean hasPlayer = mc.player != null;
        if (!hadPlayer && hasPlayer && loginEnabled.get()) {
            enableList(loginEnable.get());
            disableList(loginDisable.get());
        }
        hadPlayer = hasPlayer;

        if (mc.player == null) return;

        // Timer
        if (timerEnabled.get() && !timerModules.get().isEmpty()) {
            long threshold = (long) timerDelay.get() * 1000L;
            long now = System.currentTimeMillis();
            for (Module mod : timerModules.get()) {
                if (!mod.isActive()) {
                    Long t = disabledAt.get(mod);
                    if (t != null && now - t >= threshold) enable(mod);
                }
            }
        }

        // Elytra (edge: glide start)
        boolean elytra = mc.player.isGliding();
        if (elytraEnabled.get() && elytra && !wasElytra) {
            enableList(elytraEnable.get());
            disableList(elytraDisable.get());
        }
        wasElytra = elytra;

        // Damage
        float health = mc.player.getHealth();
        if (damageEnabled.get() && prevHealth >= 0f && health < prevHealth) {
            enableList(damageEnable.get());
            disableList(damageDisable.get());
        }
        prevHealth = health;

        // Smart Totem: health threshold and fall prediction
        if (smartTotem.get() || fallPrediction.get()) {
            FutureTotem ft = Modules.get().get(FutureTotem.class);
            if (ft != null && !ft.isActive()) {
                if (smartTotem.get() && mc.player.getHealth() <= smartTotemThreshold.get()) enable(ft);
                else if (fallPrediction.get() && isLethalFallPredicted()) enable(ft);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean isLethalFallPredicted() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isOnGround()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) return false;
        if (mc.player.getAbilities().flying) return false;
        if (mc.player.isGliding()) return false;
        if (mc.player.getVelocity().y >= 0) return false; // going up or stationary
        if (mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)) return false;
        if (mc.player.hasStatusEffect(StatusEffects.LEVITATION)) return false;

        int airBelow = airBlocksBelow();
        if (airBelow == 0) return false;

        float totalFallDist = mc.player.fallDistance + airBelow;
        float rawDamage = Math.max(0, totalFallDist - 3); // 3-block safe fall
        if (rawDamage <= 0) return false;

        float effectiveHP = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        return rawDamage >= effectiveHP * (fallHealthPercent.get() / 100.0f);
    }

    /** Count consecutive air blocks directly below the player's feet. Stops at the first non-air block. */
    private int airBlocksBelow() {
        int x = mc.player.getBlockX();
        int z = mc.player.getBlockZ();
        int startY = mc.player.getBlockPos().getY(); // block at player feet
        int minY = mc.world.getBottomY();
        for (int y = startY; y >= minY; y--) {
            scanPos.set(x, y, z);
            if (!mc.world.getBlockState(scanPos).isAir()) return startY - y;
        }
        return startY - minY; // all air to world bottom (void)
    }

    private void enableList(List<Module> list) {
        for (Module mod : list) enable(mod);
    }

    private void disableList(List<Module> list) {
        for (Module mod : list) {
            if (mod.isActive()) mod.toggle();
        }
    }

    private void enable(Module mod) {
        if (!mod.isActive()) {
            mod.toggle();
            disabledAt.remove(mod);
            lastKnownState.put(mod, true);
        }
    }
}
