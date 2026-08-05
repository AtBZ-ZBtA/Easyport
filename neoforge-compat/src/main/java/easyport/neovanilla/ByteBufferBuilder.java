package easyport.neovanilla;

/**
 * 1.21's {@code com.mojang.blaze3d.vertex.ByteBufferBuilder}, which 1.20.1 has no object for.
 *
 * <h2>What it was and what is left of it</h2>
 *
 * 1.21 split the vertex buffer out of {@code BufferBuilder}: you allocate a growable off-heap
 * arena, hand it to a builder along with the mode and format, and the builder writes into it. In
 * 1.20.1 the builder owns its own buffer and is constructed with nothing but an initial capacity in
 * bytes.
 *
 * So the only thing a 1.20.1 game needs from this object is that capacity, which is exactly the
 * argument the 1.21 constructor takes. Everything else it offered — clearing, discarding, closing —
 * managed an allocation that does not exist separately here, and is a no-op rather than a
 * reimplementation.
 *
 * <b>What that costs, stated rather than buried:</b> a mod that reuses one arena across many meshes
 * to avoid reallocating gets 1.20.1's behaviour instead, which is one buffer per builder. That is
 * slower, and it is what every 1.20.1 mod already does. It is not incorrect.
 *
 * {@code ByteBufferBuilder.Result} is absent for the same reason {@code MeshData.sortQuads} is:
 * it exists only to carry a re-sorted index buffer back to {@code VertexBuffer.uploadIndexBuffer},
 * and 1.20.1 sorts by configuring the builder before it ends. Four jars reach for it, and they get
 * a named unresolved member rather than an object that reports success and sorts nothing.
 */
public final class ByteBufferBuilder implements AutoCloseable {

    private final int capacity;

    public ByteBufferBuilder(int capacity) {
        this.capacity = capacity;
    }

    /** The initial size in bytes, which is all 1.20.1's {@code BufferBuilder} constructor wants. */
    public int capacity() {
        return capacity;
    }

    /** No separately-owned allocation to clear. See the class note. */
    public void clear() {}

    /** No separately-owned allocation to discard. See the class note. */
    public void discard() {}

    @Override
    public void close() {}
}
