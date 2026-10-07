package com.example.sonic;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class SonicClient implements ClientModInitializer {

    // ---- Ayarlar (ince ayar burada yapilir) ----
    static final double BOOST_MAX_SPEED = 1.5;   // blok/tick (1.5 = 30 blok/sn)
    static final double BOOST_ACCEL = 0.07;      // her tick hiz artisi
    static final float GAUGE_MAX = 100f;
    static final float GAUGE_DRAIN = 1.0f;       // boost sirasinda tick basina
    static final float GAUGE_REGEN = 0.35f;      // boost yokken tick basina
    static final double HOMING_RANGE = 16.0;
    static final double HOMING_SPEED = 1.9;
    static final int HOMING_MAX_TICKS = 25;
    static final float HOMING_GAUGE_BONUS = 15f;
    static final double FOV_MAX_BONUS = 30.0;

    // ---- Durum ----
    public static volatile double fovBonus = 0;
    static float gauge = GAUGE_MAX;
    static double boostSpeed = 0;
    static boolean prevJump = false;
    static LivingEntity homingTarget = null;
    static int homingTicks = 0;

    static KeyBinding boostKey;
    static KeyBinding homingKey;

    @Override
    public void onInitializeClient() {
        boostKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sonicmod.boost", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.sonicmod"));
        homingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sonicmod.homing", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.sonicmod"));

        ClientTickEvents.END_CLIENT_TICK.register(SonicClient::tick);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> renderHud(ctx));
    }

    private static void tick(MinecraftClient client) {
        var player = client.player;
        if (player == null || client.world == null || client.currentScreen != null) {
            boostSpeed = 0;
            homingTarget = null;
            fovBonus *= 0.8;
            return;
        }

        boolean jumpNow = client.options.jumpKey.isPressed();
        boolean jumpEdge = jumpNow && !prevJump;
        prevJump = jumpNow;
        boolean airborne = !player.isOnGround() && !player.isTouchingWater() && !player.isClimbing();

        // ---------- Homing Attack ----------
        boolean homingRequested = jumpEdge || homingKey.isPressed();
        if (homingTarget == null && homingRequested && airborne) {
            homingTarget = findTarget(client);
            homingTicks = 0;
        }

        if (homingTarget != null) {
            homingTicks++;
            if (!homingTarget.isAlive() || homingTicks > HOMING_MAX_TICKS) {
                homingTarget = null;
            } else {
                Vec3d to = homingTarget.getBoundingBox().getCenter().subtract(player.getPos().add(0, 0.9, 0));
                double dist = to.length();
                if (dist < 2.2) {
                    if (client.interactionManager != null) {
                        client.interactionManager.attackEntity(player, homingTarget);
                        player.swingHand(Hand.MAIN_HAND);
                    }
                    // Sonic gibi sekme
                    player.setVelocity(0, 0.65, 0);
                    player.fallDistance = 0;
                    gauge = Math.min(GAUGE_MAX, gauge + HOMING_GAUGE_BONUS);
                    homingTarget = null;
                } else {
                    Vec3d v = to.normalize().multiply(HOMING_SPEED);
                    player.setVelocity(v);
                    player.fallDistance = 0;
                }
            }
            fovBonus += (FOV_MAX_BONUS * 0.8 - fovBonus) * 0.25;
            return;
        }

        // ---------- Boost ----------
        boolean wantBoost = boostKey.isPressed() && gauge > 0;
        if (wantBoost) {
            boostSpeed = Math.min(BOOST_MAX_SPEED, boostSpeed + BOOST_ACCEL);
            if (player.horizontalCollision) {
                boostSpeed = 0.3; // duvara carpinca yavasla
            }
            Vec3d dir = Vec3d.fromPolar(0, player.getYaw());
            Vec3d vel = player.getVelocity();
            player.setVelocity(dir.x * boostSpeed, vel.y, dir.z * boostSpeed);
            player.fallDistance = 0;
            gauge = Math.max(0, gauge - GAUGE_DRAIN);
        } else {
            boostSpeed = 0;
            gauge = Math.min(GAUGE_MAX, gauge + GAUGE_REGEN);
        }

        double targetFov = FOV_MAX_BONUS * (boostSpeed / BOOST_MAX_SPEED);
        fovBonus += (targetFov - fovBonus) * 0.2;
    }

    private static LivingEntity findTarget(MinecraftClient client) {
        var player = client.player;
        Vec3d look = player.getRotationVec(1.0f).normalize();
        Vec3d eye = player.getEyePos();
        List<LivingEntity> list = client.world.getEntitiesByClass(
                LivingEntity.class,
                player.getBoundingBox().expand(HOMING_RANGE),
                e -> e != player && e.isAlive() && !e.isSpectator());

        LivingEntity best = null;
        double bestScore = -1;
        for (LivingEntity e : list) {
            Vec3d to = e.getBoundingBox().getCenter().subtract(eye);
            double d = to.length();
            if (d > HOMING_RANGE || d < 0.5) continue;
            double dot = look.dotProduct(to.normalize());
            if (dot < 0.5) continue; // onde olmali
            double score = dot * 2.0 - d / HOMING_RANGE;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private static void renderHud(net.minecraft.client.gui.DrawContext ctx) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        int barW = 100, barH = 8;
        int x = w - barW - 12;
        int y = h - 28;

        ctx.fill(x - 2, y - 2, x + barW + 2, y + barH + 2, 0xCC000000);
        int filled = (int) (barW * (gauge / GAUGE_MAX));
        int color = boostSpeed > 0 ? 0xFF00C8FF : 0xFF1E78FF;
        ctx.fill(x, y, x + filled, y + barH, color);
        ctx.drawText(client.textRenderer, "BOOST", x, y - 12, 0xFFFFFFFF, true);
    }
}
