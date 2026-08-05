package easyport.bridge;

import org.joml.Matrix3f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * The 1.21 vertex-building rework, adapted at the call site.
 *
 * <h2>How this was missed until now</h2>
 *
 * {@code api-report/vanilla-api-usage.txt} is produced by scanning the corpus for owners under
 * {@code net/minecraft/}, and everything downstream of it -- the Phase 4 queue, the gap counts,
 * the ranking that decided what to build -- inherited that scope. {@code com.mojang.blaze3d} is
 * not under {@code net/minecraft}. It is compiled into the same jar, obfuscated by the same
 * mappings, and reworked harder in 1.21 than most of vanilla, and no report this project
 * produced had ever looked at it.
 *
 * The numbers it was hiding, in jars of the 433-mod corpus: {@code endVertex} 157,
 * {@code uv} 127, {@code vertex(Matrix4f,…)} 119, {@code color} 97+99, {@code uv2} 88. Every one
 * of those is a {@code NoSuchMethodError} the moment the mod draws anything.
 *
 * <h2>What 1.21 actually changed</h2>
 *
 * The vertex protocol was renamed wholesale to {@code addVertex}/{@code set*} and the explicit
 * end-of-vertex call was dropped -- a vertex is now committed when the next one begins or when
 * the mesh is built. Most of the family is therefore a pure rename and lives in
 * {@code rules/forward.rules.tsv}, not here. This class holds only the members where the shape
 * changed too.
 *
 * <h2>What this deliberately does not attempt</h2>
 *
 * The {@code Tesselator}/{@code BufferBuilder} *lifecycle* is not adapted, and it is a wall
 * rather than a queue item. 1.20.1 hands you a reusable builder and tells it to begin
 * ({@code t.getBuilder()} then {@code b.begin(mode, format)}, 104 jars each); 1.21 constructs the
 * builder from the mode and format ({@code t.begin(mode, format)}). Fusing those means the value
 * in the mod's {@code BufferBuilder} local has to come from a call that has not happened yet, and
 * a bridge cannot write back into a caller's local. It needs a dataflow rewrite, so the report
 * names it rather than a shim faking it.
 */
public final class VertexBridge {

    private VertexBridge() {}

    /**
     * 1.20.1's {@code vertex(double,double,double)}, narrowed.
     *
     * 1.21 kept only the float form. The narrowing is what 1.20.1's own implementation did at the
     * first opportunity -- the buffer format has never stored a double -- so nothing is lost that
     * the game was not already discarding.
     */
    public static VertexConsumer vertex(VertexConsumer c, double x, double y, double z) {
        return c.addVertex((float) x, (float) y, (float) z);
    }

    /**
     * 1.20.1's {@code normal(Matrix3f,float,float,float)}.
     *
     * 1.21 replaced the {@code Matrix3f} overload with one taking a {@code PoseStack.Pose}, which
     * a call site holding a bare normal matrix does not have. Transforming the vector here is
     * exactly what the 1.20.1 default method did before delegating.
     */
    public static VertexConsumer normal(VertexConsumer c, Matrix3f matrix,
                                        float x, float y, float z) {
        Vector3f n = matrix.transform(new Vector3f(x, y, z));
        return c.setNormal(n.x(), n.y(), n.z());
    }

    /**
     * 1.20.1's {@code endVertex()}, which 1.21 removed.
     *
     * Doing nothing is the correct translation rather than a concession: in 1.21 a vertex is
     * committed by the next {@code addVertex} or by the mesh build, so the boundary the call used
     * to mark still happens, and marking it again would be the error.
     *
     * It stays a call rather than being deleted outright because deleting an instruction is a
     * stack edit, and this one is void-to-void on a receiver the call site already pushed.
     */
    public static void endVertex(VertexConsumer c) {
        // Intentionally empty. See above.
    }

    /**
     * 1.20.1's {@code vertex(…)} taking fourteen unpacked components.
     *
     * 1.21 packs the four colour floats into one ARGB int and takes eleven. The conversion is
     * lossy in the same way every 8-bit colour channel is, which is where the values were headed
     * regardless.
     */
    public static void vertex(VertexConsumer c, float x, float y, float z,
                              float red, float green, float blue, float alpha,
                              float u, float v, int overlay, int light,
                              float normalX, float normalY, float normalZ) {
        c.addVertex(x, y, z, packColor(red, green, blue, alpha), u, v, overlay, light,
                    normalX, normalY, normalZ);
    }

    /**
     * 1.20.1's {@code defaultColor(int,int,int,int)}, which has no 1.21 equivalent.
     *
     * <b>This one loses behaviour, and says so.</b> 1.20.1's {@code DefaultedVertexConsumer} let a
     * consumer carry a colour applied to vertices that did not set their own; 1.21 deleted the
     * class and the mechanism with it. There is nothing to forward to.
     *
     * Silently dropping it means a mod that relied on the default draws its geometry in whatever
     * colour the format leaves -- usually white -- instead of failing to link. That trade is the
     * same one made everywhere else here: the mod loads and one visual detail is wrong, rather
     * than the mod not loading. 18 jars call it.
     */
    public static void defaultColor(VertexConsumer c, int r, int g, int b, int a) {
        // No 1.21 equivalent. See above -- this is a known behaviour loss, not an oversight.
    }

    /** The other half of {@link #defaultColor}, and unreachable behaviour for the same reason. */
    public static void unsetDefaultColor(VertexConsumer c) {
        // No 1.21 equivalent. See above.
    }

    /**
     * 1.20.1's {@code putBulkData} without an alpha channel.
     *
     * 1.21 added alpha to the parameter list rather than changing anything about what the method
     * does, so an opaque 1.0f reproduces the old behaviour exactly: the 1.20.1 implementation
     * hard-coded full alpha at this point.
     */
    public static void putBulkData(VertexConsumer c, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                                   net.minecraft.client.renderer.block.model.BakedQuad quad,
                                   float red, float green, float blue,
                                   int light, int overlay) {
        c.putBulkData(pose, quad, red, green, blue, 1.0f, light, overlay);
    }

    /**
     * 1.20.1's {@code DefaultVertexFormat.POSITION_COLOR_TEX}, which 1.21 deleted.
     *
     * <b>The substitute is a different byte layout, and that is fine here for a specific reason.</b>
     * 1.20.1 shipped both orderings -- {@code POSITION_COLOR_TEX} and {@code POSITION_TEX_COLOR},
     * same three elements, laid out in a different order -- and 1.21 kept only the second. The
     * element *set* is identical, verified by printing both formats from their own jars rather
     * than assumed from the names.
     *
     * Order does not have to be preserved because nothing writes vertices positionally any more:
     * 1.21's builder fills by element, and shaders bind attributes by name. A mod pairing this
     * format with a hand-written shader compiled against the old offsets is the one case that
     * would notice, and such a shader is declared in JSON that names the format, so it moves with
     * it.
     */
    public static com.mojang.blaze3d.vertex.VertexFormat positionColorTex() {
        return com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR;
    }

    /**
     * 1.20.1's {@code DefaultVertexFormat.ELEMENT_*} constants, which 1.21 moved onto
     * {@code VertexFormatElement} itself when that type became a record.
     *
     * Same values, new home. {@code ELEMENT_PADDING} has no accessor here on purpose: 1.21 removed
     * padding elements outright, so there is nothing to point at and the report should say so.
     */
    public static com.mojang.blaze3d.vertex.VertexFormatElement elementPosition() {
        return com.mojang.blaze3d.vertex.VertexFormatElement.POSITION;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement elementColor() {
        return com.mojang.blaze3d.vertex.VertexFormatElement.COLOR;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement elementUv0() {
        return com.mojang.blaze3d.vertex.VertexFormatElement.UV0;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement elementUv1() {
        return com.mojang.blaze3d.vertex.VertexFormatElement.UV1;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement elementUv2() {
        return com.mojang.blaze3d.vertex.VertexFormatElement.UV2;
    }

    public static com.mojang.blaze3d.vertex.VertexFormatElement elementNormal() {
        return com.mojang.blaze3d.vertex.VertexFormatElement.NORMAL;
    }

    /**
     * 1.20.1's {@code VertexFormat.getIntegerSize()}.
     *
     * 1.21 kept only the byte size. The old method returned exactly that divided by four -- every
     * vertex element is four-byte aligned -- so this is the same number by the same arithmetic
     * rather than an approximation of it.
     */
    public static int getIntegerSize(com.mojang.blaze3d.vertex.VertexFormat format) {
        return format.getVertexSize() / 4;
    }

    /** ARGB, the packing {@code FastColor.ARGB32} uses and the one {@code addVertex} expects. */
    private static int packColor(float red, float green, float blue, float alpha) {
        return (channel(alpha) << 24) | (channel(red) << 16)
             | (channel(green) << 8) | channel(blue);
    }

    /**
     * Truncation, not rounding, and no clamp -- because that is what 1.20.1's own
     * {@code color(float,float,float,float)} compiled to: {@code (int) (f * 255.0F)}, four times,
     * verified by disassembling it.
     *
     * Rounding here would be a defensible improvement and the wrong thing to do. A translator that
     * quietly produces better output than the code it is translating is a translator whose output
     * cannot be compared against the original, and every off-by-one would be indistinguishable
     * from a real defect.
     */
    private static int channel(float f) {
        return (int) (f * 255.0f);
    }
}
