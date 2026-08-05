package easyport.neobridge;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import easyport.neovanilla.ByteBufferBuilder;
import easyport.neovanilla.MeshData;

/**
 * The 1.21 vertex rework, run backwards onto a 1.20.1 game.
 *
 * <h2>The asymmetry, which is the opposite of the usual one</h2>
 *
 * Going forward, the {@code Tesselator}/{@code BufferBuilder} lifecycle is the wall: two calls have
 * to become one, and a bridge cannot write the result back into the caller's local. Backwards it is
 * the easy part — one call becomes two, which is all a bridge ever does. {@code Tesselator.begin}
 * fetches the builder, tells it to begin, and hands it back.
 *
 * What is hard backwards is the opposite thing: <b>{@code endVertex()} has to be put back.</b> That
 * is not solved here, because it cannot be solved in a bridge — a bridge sees one call, and the
 * boundary is between calls. It is a transformer pass, {@code Translate#closeVertexChains}, and
 * {@code tools/VertexChains.java} is the measurement that said the pass was viable before it was
 * written.
 *
 * <h2>Every method here is a shape change, never a rename</h2>
 *
 * The renames — {@code setColor} to {@code color}, {@code setUv} to {@code uv}, and the rest — are
 * table entries in {@code rules/backward.rules.tsv}. This class holds only the calls where 1.20.1
 * wants different arguments, a different type, or two calls instead of one.
 */
public final class VertexBridge {

    private VertexBridge() {}

    // ---- starting a vertex -------------------------------------------------------------------
    // 1.21 narrowed the position to float; 1.20.1's abstract form takes doubles. The widening is
    // exact -- every float is a double -- so unlike the forward direction nothing is discarded.

    public static VertexConsumer addVertex(VertexConsumer c, float x, float y, float z) {
        return c.vertex(x, y, z);
    }

    public static VertexConsumer addVertex(VertexConsumer c, org.joml.Matrix4f pose,
                                           float x, float y, float z) {
        return c.vertex(pose, x, y, z);
    }

    /**
     * 1.21 added a {@code Pose} overload where 1.20.1 has only the matrix one.
     *
     * The pose carries both matrices and a position only needs the model one, which is what 1.21's
     * own implementation passes on.
     */
    public static VertexConsumer addVertex(VertexConsumer c, PoseStack.Pose pose,
                                           float x, float y, float z) {
        return c.vertex(pose.pose(), x, y, z);
    }

    public static VertexConsumer addVertex(VertexConsumer c, Vector3f position) {
        return c.vertex(position.x(), position.y(), position.z());
    }

    public static VertexConsumer addVertex(VertexConsumer c, PoseStack.Pose pose,
                                           Vector3f position) {
        return c.vertex(pose.pose(), position.x(), position.y(), position.z());
    }

    /**
     * The whole-vertex form: 1.21 packs colour into one ARGB int, 1.20.1 takes four floats.
     *
     * This one needs no {@code endVertex} inserted after it — 1.20.1's fourteen-argument
     * {@code vertex} ends its own vertex, which is visible in its disassembly and is why
     * {@code VertexChains} counts this shape separately instead of as a chain to close.
     */
    public static void addVertex(VertexConsumer c, float x, float y, float z, int color,
                                 float u, float v, int packedOverlay, int packedLight,
                                 float normalX, float normalY, float normalZ) {
        c.vertex(x, y, z,
                 channel(color >> 16), channel(color >> 8), channel(color), channel(color >> 24),
                 u, v, packedOverlay, packedLight, normalX, normalY, normalZ);
    }

    /** 1.21's {@code setNormal(Pose, …)}; 1.20.1 takes the normal matrix the pose carries. */
    public static VertexConsumer setNormal(VertexConsumer c, PoseStack.Pose pose,
                                           float x, float y, float z) {
        return c.normal(pose.normal(), x, y, z);
    }

    // ---- the builder lifecycle ---------------------------------------------------------------

    /**
     * 1.21's {@code Tesselator.begin(mode, format)}, which is 1.20.1's two calls.
     *
     * The forward direction cannot do this in reverse, and that is not a symmetry failure: going
     * forward the *second* call's result has to land in a local the first call already filled.
     * Here both calls happen inside one bridge and the caller gets the finished builder.
     */
    public static BufferBuilder begin(Tesselator tesselator, VertexFormat.Mode mode,
                                      VertexFormat format) {
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(mode, format);
        return builder;
    }

    /**
     * 1.21's {@code new BufferBuilder(arena, mode, format)}.
     *
     * The arena is 1.21's; 1.20.1's builder owns its buffer and takes a capacity, so the only thing
     * carried across is the size the mod asked for. Reached by {@code CTOR_TO_STATIC} rather than a
     * rename, because a constructor call is NEW/DUP/INVOKESPECIAL and the replacement has to remove
     * the first two.
     */
    public static BufferBuilder newBufferBuilder(ByteBufferBuilder arena, VertexFormat.Mode mode,
                                                 VertexFormat format) {
        BufferBuilder builder = new BufferBuilder(arena.capacity());
        builder.begin(mode, format);
        return builder;
    }

    /**
     * 1.21's {@code BufferBuilder.build()}, which returns null on an empty mesh.
     *
     * {@code endOrDiscardIfEmpty} is 1.20.1's method with that exact contract, so the null case
     * behaves the same rather than throwing where the mod expects to check.
     */
    public static MeshData build(BufferBuilder builder) {
        BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
        return rendered == null ? null : new MeshData(rendered);
    }

    /** 1.21's {@code buildOrThrow()}. 1.20.1's {@code end()} already throws on an empty mesh. */
    public static MeshData buildOrThrow(BufferBuilder builder) {
        return new MeshData(builder.end());
    }

    public static void drawWithShader(MeshData mesh) {
        BufferUploader.drawWithShader(mesh.buffer());
    }

    public static void draw(MeshData mesh) {
        BufferUploader.draw(mesh.buffer());
    }

    public static void upload(VertexBuffer buffer, MeshData mesh) {
        buffer.upload(mesh.buffer());
    }

    /**
     * One channel of a 1.21 packed ARGB colour, as a float in 0..1.
     *
     * Written as the inverse of what the forward bridge packs rather than as its own formula: a
     * translator whose two directions disagree by a least significant bit produces round-trip
     * differences that look exactly like real defects.
     */
    private static float channel(int shifted) {
        return (shifted & 0xFF) / 255.0f;
    }
}
