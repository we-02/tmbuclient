package com.tmbu.tmbuclient.module.impl;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tmbu.tmbuclient.module.Category;
import com.tmbu.tmbuclient.module.Module;
import com.tmbu.tmbuclient.settings.BooleanSetting;
import com.tmbu.tmbuclient.settings.ColorSetting;
import com.tmbu.tmbuclient.settings.SliderSetting;
import com.tmbu.tmbuclient.utils.TimerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class AutoCrystal extends Module {
    private final BooleanSetting holdToUse        = addSetting(new BooleanSetting("Hold To Use", false));
    private final BooleanSetting place            = addSetting(new BooleanSetting("Place", true));
    private final BooleanSetting explode          = addSetting(new BooleanSetting("Explode", true));
    final SliderSetting  placeDelay               = addSetting(new SliderSetting("Place Delay", 80, 0, 300, 5));
    private final SliderSetting  breakDelay       = addSetting(new SliderSetting("Break Delay", 60, 0, 300, 5));
    private final SliderSetting  jitter           = addSetting(new SliderSetting("Jitter", 20, 0, 80, 5));
    private final SliderSetting  range            = addSetting(new SliderSetting("Range", 4.5, 3.0, 6.0, 0.1));
    private final BooleanSetting autoSwitch       = addSetting(new BooleanSetting("Auto Switch", true));
    private final BooleanSetting switchBack       = addSetting(new BooleanSetting("Switch Back", true));
    private final BooleanSetting onlyWithObsidian = addSetting(new BooleanSetting("Only With Obsidian", false));
    private final BooleanSetting onlyWhenNearby   = addSetting(new BooleanSetting("Only When Nearby", false));
    private final SliderSetting  nearbyRange      = addSetting(new SliderSetting("Nearby Range", 12.0, 3.0, 32.0, 0.5));
    private final BooleanSetting checkLOS         = addSetting(new BooleanSetting("Check Line Of Sight", true));
    private final BooleanSetting onlyOffSword     = addSetting(new BooleanSetting("Only Explode Not Sword", false));
    private final BooleanSetting targetAboveBase  = addSetting(new BooleanSetting("Target Above Base(not working)", false));
    private final BooleanSetting onlyTotemOffhand = addSetting(new BooleanSetting("Only Totem Offhand", false));
    private final BooleanSetting stopOnShield     = addSetting(new BooleanSetting("Stop On Shield", true));
    private final BooleanSetting stopOnAnchor     = addSetting(new BooleanSetting("Stop On Anchor", true));
    private final BooleanSetting onlyOnGround     = addSetting(new BooleanSetting("Only On Ground", false));
    private final BooleanSetting baseESP          = addSetting(new BooleanSetting("Base ESP", true));
    private final ColorSetting   espColor         = addSetting(new ColorSetting("ESP Color", 0xB4FF5000));
    private final SliderSetting  espLineWidth     = addSetting(new SliderSetting("ESP Line Width", 2.0, 1.0, 5.0, 0.5));
    private final BooleanSetting espThroughWalls  = addSetting(new BooleanSetting("ESP Through Walls", true));
    private final BooleanSetting debug            = addSetting(new BooleanSetting("Debug", false));

    final TimerUtils placeTimer = new TimerUtils();
    private final TimerUtils breakTimer = new TimerUtils();
    private final Random     rng        = new Random();

    private int     pendingSwitchBackSlot = -1;
    private boolean switchBackQueued      = false;

    private static final int  BASE_ESP_MAX    = 16;
    private static final long BASE_ESP_TTL_MS = 800;

    private final Map<BlockPos, Long> activeBases = Collections.synchronizedMap(
        new LinkedHashMap<>(BASE_ESP_MAX, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BlockPos, Long> eldest) {
                return size() > BASE_ESP_MAX;
            }
        }
    );

    static volatile AutoCrystal activeInstance = null;

    public AutoCrystal() {
        super("AutoCrystal", "Legit crystal assist — Post & PacketOrderB bypass", Category.COMBAT, GLFW.GLFW_KEY_R);
    }

    @Override
    public void onEnable() {
        placeTimer.reset();
        breakTimer.reset();
        pendingSwitchBackSlot = -1;
        switchBackQueued      = false;
        activeInstance        = this;
        activeBases.clear();
    }

    @Override
    public void onDisable() {
        activeInstance = null;
        activeBases.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && switchBackQueued && pendingSwitchBackSlot != -1) {
            client.player.getInventory().setSelectedSlot(pendingSwitchBackSlot);
        }
        pendingSwitchBackSlot = -1;
        switchBackQueued      = false;
    }

    @Override
    public void onTick(Minecraft client) {}

    public void onPreMotion(Minecraft client) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null) return;

        if (switchBackQueued && pendingSwitchBackSlot != -1) {
            player.getInventory().setSelectedSlot(pendingSwitchBackSlot);
            if (debug.getValue()) System.out.println("[AC] Switch back: " + pendingSwitchBackSlot);
            pendingSwitchBackSlot = -1;
            switchBackQueued = false;
            return;
        }

        if (holdToUse.getValue() && !isKeyPressed(client, getKeybind())) return;
        if (onlyWithObsidian.getValue() && !autoSwitch.getValue()
                && !player.getMainHandItem().is(Items.OBSIDIAN)) return;
        if (onlyWhenNearby.getValue() && !hasNearbyTarget(player, nearbyRange.getValue())) return;
        if (onlyTotemOffhand.getValue() && !player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        if (stopOnShield.getValue() && isHoldingShield(player)) {
            if (debug.getValue()) System.out.println("[AC] Suppressed — shield in hand");
            return;
        }

        if (stopOnAnchor.getValue() && isHoldingAnchor(player)) {
            if (debug.getValue()) System.out.println("[AC] Suppressed — respawn anchor in hand");
            return;
        }

        if (onlyOnGround.getValue() && !player.onGround()) {
            if (debug.getValue()) System.out.println("[AC] Suppressed — player is not on ground");
            return;
        }

        long jitterMs = (long) (rng.nextDouble() * jitter.getValue());

        // ── Break ─────────────────────────────────────────────────────────────
        if (explode.getValue() && breakTimer.hasTimeElapsed(Math.round(breakDelay.getValue()) + jitterMs, false)) {
            if (onlyOffSword.getValue() && isSword(player)) return;
            EndCrystal crystal = findLookedCrystal(client, player);
            if (crystal != null) {
                // targetAboveBase: only break if an enemy is at or above the
                // looked-at base's Y level. Keeps place and break in sync so
                // we don't get orphan explosions with no placement.
                if (targetAboveBase.getValue()) {
                    HitResult placeHit = client.hitResult;
                    if (placeHit instanceof BlockHitResult pbhr) {
                        BlockPos base = pbhr.getBlockPos();

                        if (!isEnemyAboveBase(player.level(), player, base)) {
                            if (debug.getValue()) System.out.println("[AC] Skip break — no enemy above base Y");
                            return;
                        }
                    }
                }
                client.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                gameMode.attack(player, crystal);
                breakTimer.reset();
                if (debug.getValue()) System.out.println("[AC] Break");
                return;
            }
        }

        // ── Place ─────────────────────────────────────────────────────────────
        if (!place.getValue()) return;
        if (!placeTimer.hasTimeElapsed(Math.round(placeDelay.getValue()) + jitterMs, false)) return;

        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
        if (!(hit instanceof BlockHitResult bhr)) return;

        BlockPos base = bhr.getBlockPos();
        Level level = player.level();

        if (!isValidBase(level, base)) return;

        // Self-safety check is ALWAYS enforced regardless of any other setting.
        if (!isSafeToPlace(player, base)) {
            if (debug.getValue()) System.out.println("[AC] Skip place — not safe for self");
            return;
        }

        // targetAboveBase: only place if an enemy's feet are at or above the
        // top surface of the looked-at base block (base.getY() + 1).
        if (targetAboveBase.getValue()) {
            if (!isEnemyAboveBase(level, player, base)) {
                if (debug.getValue()) System.out.println("[AC] Skip place — no enemy above base Y");
                return;
            }
        }


        InteractionHand hand = getCrystalHand(player);
        if (hand == null) {
            if (!autoSwitch.getValue()) return;

            ItemStack selectedItem = player.getInventory().getItem(player.getInventory().getSelectedSlot());
            if (isProtectedItem(selectedItem)) {
                if (debug.getValue()) System.out.println("[AC] Auto-switch blocked — protected item selected");
                return;
            }

            int slot = findHotbarSlot(player, Items.END_CRYSTAL);
            if (slot == -1) {
                if (debug.getValue()) System.out.println("[AC] No crystals in hotbar");
                return;
            }
            if (switchBack.getValue()) pendingSwitchBackSlot = player.getInventory().getSelectedSlot();
            player.getInventory().setSelectedSlot(slot);
            if (debug.getValue()) System.out.println("[AC] Switched to slot " + slot);
            hand = InteractionHand.MAIN_HAND;
        }

        gameMode.useItemOn(player, hand, bhr);
        placeTimer.reset();
        if (debug.getValue()) System.out.println("[AC] Place");

        if (baseESP.getValue()) activeBases.put(base, System.currentTimeMillis());
        if (switchBack.getValue() && pendingSwitchBackSlot != -1) switchBackQueued = true;
    }

    // ── ESP ───────────────────────────────────────────────────────────────────

    @Override
    public void onWorldRender(WorldRenderContext context) {
        if (!baseESP.getValue() || activeBases.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;

        PoseStack matrices = context.matrices();
        if (matrices == null) return;

        Vec3 cam = client.gameRenderer.getMainCamera().position();
        long now = System.currentTimeMillis();
        int color = espColor.getColor();
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8)  & 0xFF;
        int b =  color        & 0xFF;
        int a = (color >> 24) & 0xFF;
        float width = espLineWidth.getValue().floatValue();

        if (espThroughWalls.getValue()) GL11.glDisable(GL11.GL_DEPTH_TEST);

        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(RenderTypes.LINES.bufferSize())) {
            MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(byteBuffer);
            PoseStack.Pose pose = matrices.last();
            VertexConsumer lines = bufferSource.getBuffer(RenderTypes.LINES);

            synchronized (activeBases) {
                activeBases.entrySet().removeIf(e -> now - e.getValue() > BASE_ESP_TTL_MS);
                for (BlockPos base : activeBases.keySet()) {
                    AABB baseBox = new AABB(base).move(-cam.x, -cam.y, -cam.z);
                    renderBox(lines, pose, baseBox, r, g, b, a, width);
                }
            }

            bufferSource.endBatch(RenderTypes.LINES);
        }

        if (espThroughWalls.getValue()) GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private static void renderBox(VertexConsumer consumer, PoseStack.Pose pose, AABB box,
                                  int r, int g, int b, int a, float width) {
        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;

        emitLine(consumer, pose, minX, minY, minZ, maxX, minY, minZ, r, g, b, a, width);
        emitLine(consumer, pose, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a, width);
        emitLine(consumer, pose, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a, width);
        emitLine(consumer, pose, minX, minY, maxZ, minX, minY, minZ, r, g, b, a, width);
        emitLine(consumer, pose, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a, width);
        emitLine(consumer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a, width);
        emitLine(consumer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a, width);
        emitLine(consumer, pose, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a, width);
        emitLine(consumer, pose, minX, minY, minZ, minX, maxY, minZ, r, g, b, a, width);
        emitLine(consumer, pose, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a, width);
        emitLine(consumer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a, width);
        emitLine(consumer, pose, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a, width);
    }

    private static void emitLine(VertexConsumer consumer, PoseStack.Pose pose,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 int r, int g, int b, int a, float width) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = 0f, ny = 1f, nz = 0f;
        if (len > 1.0E-4F) { nx = dx / len; ny = dy / len; nz = dz / len; }
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    // ==================== Safety checks =======================================

    private boolean isSafeToPlace(LocalPlayer player, BlockPos base) {
        return (base.getY() + 1.0) > player.position().y;
    }

    private boolean isSafeToBreak(LocalPlayer player, EndCrystal crystal) {
        return crystal.position().y > player.position().y;
    }

    // ==================== Find crystal ========================================

    private EndCrystal findLookedCrystal(Minecraft client, LocalPlayer player) {
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return null;
        if (!(hit instanceof EntityHitResult eHit)) return null;
        if (!(eHit.getEntity() instanceof EndCrystal crystal)) return null;
        double r = range.getValue();
        if (player.distanceToSqr(crystal) > r * r) return null;
        if (checkLOS.getValue() && !player.hasLineOfSight(crystal)) return null;
        if (!isSafeToBreak(player, crystal)) {
            if (debug.getValue()) System.out.println("[AC] Skip break — not safe");
            return null;
        }
        return crystal;
    }

    // ==================== Base validation =====================================

    private boolean isValidBase(Level level, BlockPos base) {
        BlockState state = level.getBlockState(base);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return false;
        BlockPos above = base.above();
        if (!level.getBlockState(above).isAir()) return false;
        if (!level.getBlockState(above.above()).isAir()) return false;
        AABB box = new AABB(above).inflate(0.01);
        return level.getEntitiesOfClass(EndCrystal.class, box, Entity::isAlive).isEmpty();
    }

    private static boolean hasNearbyTarget(LocalPlayer self, double maxRange) {
        Level level = self.level();
        AABB box = self.getBoundingBox().inflate(maxRange);
        return !level.getEntitiesOfClass(Entity.class, box,
                e -> e != self && e.isAlive() &&
                     ((e instanceof Player p && !p.isSpectator()) || e instanceof Enemy)
        ).isEmpty();
    }

    // ==================== Utils ===============================================

    private static boolean isKeyPressed(Minecraft client, int key) {
        return key >= 0 && GLFW.glfwGetKey(client.getWindow().handle(), key) == GLFW.GLFW_PRESS;
    }

    private static InteractionHand getCrystalHand(LocalPlayer player) {
        if (player.getMainHandItem().is(Items.END_CRYSTAL)) return InteractionHand.MAIN_HAND;
        if (player.getOffhandItem().is(Items.END_CRYSTAL)) return InteractionHand.OFF_HAND;
        return null;
    }

    private static boolean isSword(LocalPlayer player) {
        return player.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS);
    }

    private static boolean isHoldingShield(LocalPlayer player) {
        return player.getMainHandItem().is(Items.SHIELD)
            || player.getOffhandItem().is(Items.SHIELD);
    }

    private static boolean isHoldingAnchor(LocalPlayer player) {
        return player.getMainHandItem().is(Items.RESPAWN_ANCHOR)
            || player.getOffhandItem().is(Items.RESPAWN_ANCHOR);
    }

    private static boolean isProtectedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(Items.SHIELD)
            || stack.is(Items.RESPAWN_ANCHOR)
            || stack.is(net.minecraft.tags.ItemTags.SWORDS);
    }

    private static int findHotbarSlot(LocalPlayer player, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private boolean isEnemyAboveBase(Level level, LocalPlayer self, BlockPos base) {
        double baseTopY = base.getY() + 1; // top of the base block

        for (Player entity : level.players()) {
            if (entity == self) continue;
            if (!entity.isAlive() || entity.isSpectator()) continue;

            // Only Y comparison — nothing else
            if (entity.getBoundingBox().minY >= baseTopY) {
                return true;
            }
        }

        return false;
    }
}
