package easyport.neovanilla;

import com.mojang.blaze3d.vertex.BufferBuilder;

/**
 * 1.21's {@code com.mojang.blaze3d.vertex.MeshData}, backed by 1.20.1's
 * {@code BufferBuilder.RenderedBuffer}.
 *
 * <h2>Why this is not declared in its own package</h2>
 *
 * The obvious shim is a class literally named {@code com.mojang.blaze3d.vertex.MeshData}, so no
 * type rename is needed at all. It cannot be: Minecraft is a module on the 1.20.1 boot layer, and
 * a second module contributing to a package that one already exports is a split package, which the
 * JVM refuses before any of this code runs. Every 1.21-only *vanilla* type shimmed here lives under
 * {@code easyport.neovanilla} and gets there by {@code TYPE_RENAME}, the same way
 * {@code easyport.vanilla} works in the forward direction.
 *
 * <h2>What it carries, and what it does not</h2>
 *
 * 1.21 turned the built mesh into an {@code AutoCloseable} that owns its buffer; 1.20.1's
 * {@code RenderedBuffer} is released by hand. {@code close()} forwards to {@code release()}, so a
 * try-with-resources in a 1.21 mod frees the buffer exactly once, at the same point it would have.
 *
 * {@code sortQuads} is deliberately absent. 1.21 sorts a *built* mesh; 1.20.1 sorts by telling the
 * builder how before it ends, via {@code setQuadSorting}, and there is no object here to apply that
 * to after the fact. Six corpus jars call it, and they get a named unresolved member at translate
 * time rather than a shim that accepts the call and silently leaves the quads in the wrong order —
 * which for transparency sorting is a rendering bug that looks like a mod bug.
 */
public final class MeshData implements AutoCloseable {

    private final BufferBuilder.RenderedBuffer buffer;

    public MeshData(BufferBuilder.RenderedBuffer buffer) {
        this.buffer = buffer;
    }

    /** The 1.20.1 value, for the bridges that hand it back to vanilla. */
    public BufferBuilder.RenderedBuffer buffer() {
        return buffer;
    }

    public DrawState drawState() {
        return new DrawState(buffer.drawState());
    }

    public java.nio.ByteBuffer vertexBuffer() {
        return buffer.vertexBuffer();
    }

    public java.nio.ByteBuffer indexBuffer() {
        return buffer.indexBuffer();
    }

    @Override
    public void close() {
        buffer.release();
    }

    /** 1.21's {@code MeshData.DrawState}. Only what the corpus reads off it. */
    public static final class DrawState {

        private final BufferBuilder.DrawState inner;

        DrawState(BufferBuilder.DrawState inner) {
            this.inner = inner;
        }

        public com.mojang.blaze3d.vertex.VertexFormat format() {
            return inner.format();
        }

        public int vertexCount() {
            return inner.vertexCount();
        }

        public int indexCount() {
            return inner.indexCount();
        }

        public com.mojang.blaze3d.vertex.VertexFormat.Mode mode() {
            return inner.mode();
        }
    }
}
