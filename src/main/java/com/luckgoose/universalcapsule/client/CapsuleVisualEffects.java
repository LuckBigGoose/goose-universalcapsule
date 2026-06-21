package com.luckgoose.universalcapsule.client;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.network.CapsuleVisualEffectPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 客户端投掷视觉效果管理器。
 *
 * 效果只存在客户端，用于飞行物品、环形扫描线、粒子和本地音效；服务端权威逻辑不依赖这里。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID, value = Dist.CLIENT)
public final class CapsuleVisualEffects {

    /** 防止异常高频广播时客户端效果列表无限增长。 */
    private static final int MAX_ACTIVE_EFFECTS = 64;
    private static final int FLIGHT_TICKS = 18;
    private static final int TOTAL_TICKS = CapsuleVisualEffectPacket.TOTAL_TICKS;
    private static final int STARTUP_TICKS = 23;
    private static final int EFFECT_END_TICKS = 36;
    private static final List<Effect> EFFECTS = new ArrayList<>();
    private static final BufferBuilder LINE_BUFFER = new BufferBuilder(2048);

    private CapsuleVisualEffects() {
    }

    /** 添加一个新的客户端效果；超出上限时丢弃最旧效果。 */
    public static void add(CapsuleVisualEffectPacket.EffectType type, Vec3 start, BlockPos origin, BlockPos size, ItemStack stack) {
        if (EFFECTS.size() >= MAX_ACTIVE_EFFECTS) {
            EFFECTS.remove(0);
        }
        EFFECTS.add(new Effect(type, start, origin, size, stack));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            EFFECTS.clear();
            return;
        }
        Iterator<Effect> it = EFFECTS.iterator();
        while (it.hasNext()) {
            Effect effect = it.next();
            effect.tick(mc);
            if (effect.age > TOTAL_TICKS) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || EFFECTS.isEmpty()) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        for (Effect effect : EFFECTS) {
            if (effect.age > FLIGHT_TICKS + 6) continue;
            Vec3 pos = effect.position(event.getPartialTick());
            pose.pushPose();
            pose.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((effect.age + event.getPartialTick()) * 28.0F));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(18.0F));
            float scale = 0.85F + Mth.sin((effect.age + event.getPartialTick()) * 0.45F) * 0.08F;
            pose.scale(scale, scale, scale);
            mc.getItemRenderer().renderStatic(effect.stack, ItemDisplayContext.GROUND, 15728880,
                    OverlayTexture.NO_OVERLAY, pose, buffer, mc.level, 0);
            pose.popPose();
        }
        buffer.endBatch();
        beginLineState();
        LINE_BUFFER.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        for (Effect effect : EFFECTS) {
            if (effect.shouldRenderScan()) {
                effect.renderScan(pose, event.getPartialTick());
            }
        }
        pose.popPose();
        BufferUploader.drawWithShader(LINE_BUFFER.end());
        endLineState();
    }

    private static void beginLineState() {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.0F);
    }

    private static void endLineState() {
        RenderSystem.lineWidth(1.0F);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    private static final class Effect {
        private final CapsuleVisualEffectPacket.EffectType type;
        private final Vec3 start;
        private final BlockPos origin;
        private final BlockPos size;
        private final Vec3 target;
        private final ItemStack stack;
        private int age;
        private boolean launchSoundPlayed;
        private boolean clickSoundPlayed;
        private boolean effectSoundPlayed;
        private boolean finishSoundPlayed;

        private Effect(CapsuleVisualEffectPacket.EffectType type, Vec3 start, BlockPos origin, BlockPos size, ItemStack stack) {
            this.type = type;
            this.start = start;
            this.origin = origin;
            this.size = size;
            this.stack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
            this.target = new Vec3(
                    origin.getX() + size.getX() * 0.5D,
                    origin.getY() + size.getY() + 1.35D,
                    origin.getZ() + size.getZ() * 0.5D);
        }

        private void tick(Minecraft mc) {
            age++;
            playStageSounds(mc);
            if (age <= FLIGHT_TICKS) {
                spawnTrail(mc);
                return;
            }
            if (age <= STARTUP_TICKS) {
                spawnStartupSmoke(mc);
                return;
            }
            if (type == CapsuleVisualEffectPacket.EffectType.CAPTURE) {
                spawnCaptureParticles(mc);
            } else if (type == CapsuleVisualEffectPacket.EffectType.CAPTURE_FAIL) {
                spawnFailParticles(mc);
            } else {
                spawnPlaceParticles(mc);
            }
        }

        private void playStageSounds(Minecraft mc) {
            if (mc.level == null) return;
            if (!launchSoundPlayed) {
                launchSoundPlayed = true;
                mc.level.playLocalSound(start.x, start.y, start.z, SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.35F, 1.45F, false);
            }
            if (!clickSoundPlayed && age == FLIGHT_TICKS + 1) {
                clickSoundPlayed = true;
                mc.level.playLocalSound(target.x, target.y, target.z, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.PLAYERS, 0.45F, 1.7F, false);
            }
            if (!effectSoundPlayed && age == STARTUP_TICKS + 1) {
                effectSoundPlayed = true;
                if (type == CapsuleVisualEffectPacket.EffectType.CAPTURE_FAIL) {
                    mc.level.playLocalSound(target.x, target.y, target.z, SoundEvents.STONE_BUTTON_CLICK_OFF, SoundSource.PLAYERS, 0.35F, 0.8F, false);
                } else if (type == CapsuleVisualEffectPacket.EffectType.CAPTURE) {
                    mc.level.playLocalSound(target.x, target.y, target.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.32F, 1.8F, false);
                } else {
                    mc.level.playLocalSound(target.x, target.y, target.z, SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.42F, 1.2F, false);
                }
            }
            if (!finishSoundPlayed && age == EFFECT_END_TICKS) {
                finishSoundPlayed = true;
                if (type != CapsuleVisualEffectPacket.EffectType.CAPTURE_FAIL) {
                    mc.level.playLocalSound(target.x, target.y, target.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.25F, type == CapsuleVisualEffectPacket.EffectType.CAPTURE ? 1.55F : 1.05F, false);
                }
            }
        }

        private Vec3 position(float partialTick) {
            float t = Mth.clamp((age + partialTick) / FLIGHT_TICKS, 0.0F, 1.0F);
            float eased = 1.0F - (1.0F - t) * (1.0F - t);
            Vec3 base = start.lerp(target, eased);
            double arc = Mth.sin(t * Mth.PI) * 0.85D;
            return base.add(0.0D, arc, 0.0D);
        }

        private void spawnCaptureParticles(Minecraft mc) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = Mth.clamp((size.getX() + size.getY() + size.getZ()) / 2, 8, 24);
            for (int i = 0; i < count; i++) {
                Vec3 from = randomBoundaryPoint(random);
                Vec3 toTarget = target.subtract(from);
                double speedFactor = 0.07D + toTarget.length() * 0.018D;
                Vec3 motion = toTarget.normalize().scale(speedFactor);
                Vec3 swirl = swirlMotion(from, target, random, 0.018D);
                mc.level.addParticle(ParticleTypes.CLOUD, from.x, from.y, from.z,
                        motion.x + swirl.x, motion.y + swirl.y, motion.z + swirl.z);
                if (i % 8 == 0) {
                    mc.level.addParticle(ParticleTypes.END_ROD, from.x, from.y, from.z,
                            motion.x * 0.3D, motion.y * 0.3D, motion.z * 0.3D);
                }
            }
            if (age == EFFECT_END_TICKS) {
                smokeBurst(mc, 18, 0.32D);
            }
        }

        private void spawnPlaceParticles(Minecraft mc) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = Mth.clamp((size.getX() + size.getY() + size.getZ()) / 2, 10, 28);
            for (int i = 0; i < count; i++) {
                Vec3 to = randomBoundaryPoint(random);
                Vec3 outward = to.subtract(target);
                double speedFactor = 0.06D + outward.length() * 0.016D;
                Vec3 motion = outward.normalize().scale(speedFactor);
                Vec3 swirl = swirlMotion(target, to, random, 0.016D);
                mc.level.addParticle(ParticleTypes.CLOUD, target.x, target.y, target.z,
                        motion.x + swirl.x, motion.y + swirl.y, motion.z + swirl.z);
                if (i % 8 == 0) {
                    mc.level.addParticle(ParticleTypes.END_ROD, target.x, target.y, target.z,
                            motion.x * 0.35D, motion.y * 0.35D, motion.z * 0.35D);
                }
            }
            if (age == EFFECT_END_TICKS) {
                smokeBurst(mc, 24, 0.38D);
            }
        }

        private void spawnFailParticles(Minecraft mc) {
            if (age == FLIGHT_TICKS + 1 || age % 5 == 0) {
                mc.level.addParticle(ParticleTypes.POOF, target.x, target.y, target.z, 0.0D, 0.02D, 0.0D);
            }
            if (age % 3 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < 6; i++) {
                    double dx = random.nextDouble(-0.18D, 0.18D);
                    double dy = random.nextDouble(-0.04D, 0.16D);
                    double dz = random.nextDouble(-0.18D, 0.18D);
                    mc.level.addParticle(ParticleTypes.CLOUD, target.x, target.y, target.z, dx, dy, dz);
                }
            }
        }

        private void spawnTrail(Minecraft mc) {
            Vec3 pos = position(0.0F);
            Vec3 prev = previousPosition();
            Vec3 back = prev.subtract(pos).normalize();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 3; i++) {
                double spread = random.nextDouble(0.045D, 0.11D);
                Vec3 offset = new Vec3(random.nextDouble(-spread, spread), random.nextDouble(-spread, spread), random.nextDouble(-spread, spread));
                Vec3 p = pos.add(back.scale(i * 0.18D)).add(offset);
                mc.level.addParticle(ParticleTypes.CLOUD, p.x, p.y, p.z,
                        back.x * 0.025D, back.y * 0.025D, back.z * 0.025D);
            }
            if (age % 5 == 0) {
                mc.level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D);
            }
        }

        private void spawnStartupSmoke(Minecraft mc) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 4; i++) {
                double angle = random.nextDouble(Math.PI * 2.0D);
                double radius = random.nextDouble(0.18D, 0.38D);
                double dx = Math.cos(angle) * radius;
                double dz = Math.sin(angle) * radius;
                mc.level.addParticle(ParticleTypes.CLOUD, target.x + dx, target.y + random.nextDouble(-0.08D, 0.12D), target.z + dz,
                        dx * 0.035D, 0.015D, dz * 0.035D);
            }
            if (age == FLIGHT_TICKS + 1) {
                mc.level.addParticle(ParticleTypes.FLASH, target.x, target.y, target.z, 0.0D, 0.0D, 0.0D);
            }
        }

        private void smokeBurst(Minecraft mc, int count, double speed) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < count; i++) {
                double yaw = random.nextDouble(Math.PI * 2.0D);
                double pitch = random.nextDouble(-0.25D, 0.55D);
                double horizontal = Math.cos(pitch) * speed * random.nextDouble(0.35D, 1.0D);
                double dx = Math.cos(yaw) * horizontal;
                double dz = Math.sin(yaw) * horizontal;
                double dy = Math.sin(pitch) * speed + random.nextDouble(0.02D, 0.12D);
                mc.level.addParticle(i % 4 == 0 ? ParticleTypes.POOF : ParticleTypes.CLOUD,
                        target.x, target.y - 0.15D, target.z, dx, dy, dz);
            }
        }

        private boolean shouldRenderScan() {
            return type != CapsuleVisualEffectPacket.EffectType.CAPTURE_FAIL && age > FLIGHT_TICKS && age <= EFFECT_END_TICKS;
        }

        private void renderScan(PoseStack pose, float partialTick) {
            float t = Mth.clamp((age + partialTick - FLIGHT_TICKS) / (float) (EFFECT_END_TICKS - FLIGHT_TICKS), 0.0F, 1.0F);
            AABB bounds = new AABB(
                    origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ()
            ).inflate(0.015D);
            float alpha = 0.3F + Mth.sin(t * Mth.PI) * 0.35F;
            float r = type == CapsuleVisualEffectPacket.EffectType.CAPTURE ? 0.88F : 0.55F;
            float g = type == CapsuleVisualEffectPacket.EffectType.CAPTURE ? 0.95F : 0.86F;
            float b = 1.0F;
            appendBoxLines(pose, bounds, r, g, b, 0.35F);
            for (int i = 0; i < 3; i++) {
                float offset = (t + i / 3.0F) % 1.0F;
                double y = bounds.minY + (bounds.maxY - bounds.minY) * offset;
                appendHorizontalRing(pose, bounds, y, r, g, b, alpha * (1.0F - i * 0.18F));
            }
        }

        private void appendHorizontalRing(PoseStack pose, AABB box, double y, float r, float g, float b, float a) {
            org.joml.Matrix4f mat = pose.last().pose();
            line(mat, box.minX, y, box.minZ, box.maxX, y, box.minZ, r, g, b, a);
            line(mat, box.maxX, y, box.minZ, box.maxX, y, box.maxZ, r, g, b, a);
            line(mat, box.maxX, y, box.maxZ, box.minX, y, box.maxZ, r, g, b, a);
            line(mat, box.minX, y, box.maxZ, box.minX, y, box.minZ, r, g, b, a);
        }

        private void appendBoxLines(PoseStack pose, AABB box, float r, float g, float b, float a) {
            org.joml.Matrix4f mat = pose.last().pose();
            line(mat, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, r, g, b, a);
            line(mat, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, r, g, b, a);
            line(mat, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, r, g, b, a);
            line(mat, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ, r, g, b, a);
            line(mat, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, r, g, b, a);
            line(mat, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, a);
            line(mat, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, r, g, b, a);
            line(mat, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, r, g, b, a);
            line(mat, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, r, g, b, a);
            line(mat, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, r, g, b, a);
            line(mat, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, r, g, b, a);
            line(mat, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, r, g, b, a);
        }

        private void line(org.joml.Matrix4f mat, double x1, double y1, double z1, double x2, double y2, double z2,
                          float r, float g, float b, float a) {
            LINE_BUFFER.vertex(mat, (float) x1, (float) y1, (float) z1).color(r, g, b, a).endVertex();
            LINE_BUFFER.vertex(mat, (float) x2, (float) y2, (float) z2).color(r, g, b, a).endVertex();
        }

        private Vec3 previousPosition() {
            int current = age;
            age = Math.max(0, age - 1);
            Vec3 previous = position(0.0F);
            age = current;
            return previous;
        }

        private Vec3 swirlMotion(Vec3 from, Vec3 to, ThreadLocalRandom random, double strength) {
            Vec3 dir = to.subtract(from).normalize();
            Vec3 tangent = new Vec3(-dir.z, random.nextDouble(-0.45D, 0.45D), dir.x);
            if (tangent.lengthSqr() < 1.0E-5D) {
                tangent = new Vec3(dir.y, -dir.x, 0.0D);
            }
            return tangent.normalize().scale(strength * random.nextDouble(0.45D, 1.25D));
        }

        private Vec3 randomBoundaryPoint(ThreadLocalRandom random) {
            double x = origin.getX() + random.nextDouble(Math.max(1, size.getX()));
            double y = origin.getY() + random.nextDouble(Math.max(1, size.getY()));
            double z = origin.getZ() + random.nextDouble(Math.max(1, size.getZ()));
            int face = random.nextInt(6);
            if (face == 0) x = origin.getX();
            else if (face == 1) x = origin.getX() + size.getX();
            else if (face == 2) y = origin.getY();
            else if (face == 3) y = origin.getY() + size.getY();
            else if (face == 4) z = origin.getZ();
            else z = origin.getZ() + size.getZ();
            return new Vec3(x, y, z);
        }
    }
}
