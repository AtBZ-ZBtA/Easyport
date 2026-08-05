import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Answers one question, before a translation pass gets designed around the answer: <b>where does a
 * 1.21 vertex chain end?</b>
 *
 * <h2>Why it has to be asked</h2>
 *
 * Translating a 1.21.1 mod back to 1.20.1 means putting {@code endVertex()} back. 1.21 removed it —
 * a vertex is committed implicitly — and 1.20.1's builder requires it, and nothing in the mod's
 * bytecode marks where a vertex ends.
 *
 * The proposed answer is that the 1.21 idiom is a fluent chain whose value is discarded:
 *
 * <pre>consumer.addVertex(m, x, y, z).setColor(…).setUv(…).setLight(…);</pre>
 *
 * which compiles to a run of invocations ending in {@code POP}. If that holds, the {@code POP} is
 * the end of the vertex, exactly and syntactically, and the transformer can replace it.
 *
 * <b>"If that holds" is the entire question, and guessing at it would be building a rendering
 * feature on an assumption no harness here can test.</b> So this counts the shapes instead: every
 * chain rooted at {@code addVertex}, followed through the setters, classified by what finally
 * consumes the value. A chain ending anywhere other than {@code POP} is one the pass would get
 * wrong, and the ratio decides whether the design is viable, viable-with-a-guard, or dead.
 *
 * <h2>How a chain is followed</h2>
 *
 * {@code SourceInterpreter} gives, for every stack slot at every instruction, the set of
 * instructions that produced it. That is a producer map; what this needs is consumers, so it is
 * inverted: mark {@code addVertex} as a source, then repeatedly mark any {@code set*} call whose
 * *receiver* came from an already-marked instruction. The receiver is the deepest of the slots the
 * call pops, which is why the argument count has to be read off the descriptor rather than assumed.
 *
 * Whatever finally reads a marked value without being marked itself is the terminal, and its
 * opcode is the finding.
 *
 * Usage:  java -cp asm.jar:asm-tree.jar:asm-analysis.jar tools/VertexChains.java &lt;jar-dir&gt;
 */
public class VertexChains {

    private static final String VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String PREFIX = "com/mojang/blaze3d/vertex/";

    /** Terminal shape -> how many chains ended that way. */
    private static final Map<String, Integer> terminals = new TreeMap<>();
    /** Terminal shape -> the jars it was seen in, so a rare shape can be tracked to a mod. */
    private static final Map<String, Set<String>> terminalJars = new TreeMap<>();

    private static int chains = 0;
    private static int jarsWithChains = 0;
    private static int analysisFailures = 0;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: VertexChains <jar-dir>");
            System.exit(2);
        }

        List<Path> jars;
        try (var stream = Files.walk(Path.of(args[0]))) {
            jars = stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }

        int scanned = 0;
        for (Path jar : jars) {
            String jarName = jar.getFileName().toString();
            int before = chains;
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    try (InputStream in = zf.getInputStream(e)) {
                        ClassNode node = new ClassNode();
                        new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG);
                        for (MethodNode m : node.methods) scan(node.name, m, jarName);
                    } catch (Exception ignored) {
                        // A class this reader cannot parse says nothing about vertex chains.
                    }
                }
            } catch (IOException ignored) {
                continue;
            }
            scanned++;
            if (chains > before) jarsWithChains++;
        }

        System.out.printf("scanned %d jars, %d contain a vertex chain%n", scanned, jarsWithChains);
        System.out.printf("chains rooted at addVertex: %d   (methods skipped, analysis failed: %d)%n%n",
                          chains, analysisFailures);
        System.out.println("=== how the chain ends ===");
        terminals.entrySet().stream()
                 .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                 .forEach(en -> System.out.printf("  %6d  %-28s  in %d jars%n", en.getValue(),
                         en.getKey(), terminalJars.getOrDefault(en.getKey(), Set.of()).size()));

        int pop = terminals.getOrDefault("POP", 0);
        if (chains > 0) {
            System.out.printf("%nPOP-terminated: %.1f%% of chains%n", 100.0 * pop / chains);
        }
    }

    private static void scan(String owner, MethodNode m, String jarName) {
        if (m.instructions == null || m.instructions.size() == 0) return;

        // Cheap pre-filter. The analysis is the expensive part and most methods never touch this
        // API at all, so do not pay for one unless an addVertex is actually present.
        boolean any = false;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode min && isChainRoot(min)) { any = true; break; }
        }
        if (!any) return;

        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (AnalyzerException | RuntimeException ex) {
            analysisFailures++;
            return;
        }

        AbstractInsnNode[] insns = m.instructions.toArray();
        Map<AbstractInsnNode, Integer> index = new HashMap<>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);

        // Mark the chain: roots first, then any setter whose receiver came from something marked.
        // Iterated to a fixed point because a chain is arbitrarily long and the instructions are
        // not necessarily in chain order once the compiler has had its way with them.
        Set<AbstractInsnNode> marked = new HashSet<>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof MethodInsnNode min && isChainRoot(min)) marked.add(min);
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof MethodInsnNode min) || marked.contains(min)) continue;
                if (!returnsConsumer(min) || frames[i] == null) continue;
                if (receiverIsMarked(frames[i], min, marked)) { marked.add(min); grew = true; }
            }
        }
        if (marked.isEmpty()) return;

        // A marked instruction is a chain terminal when nothing else marked consumes its value.
        // Count roots, not links, or a five-setter chain would count as five chains.
        Set<AbstractInsnNode> consumedByChain = new HashSet<>();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode min) || !marked.contains(min)) continue;
            if (frames[i] == null) continue;
            for (AbstractInsnNode src : receiverSources(frames[i], min)) consumedByChain.add(src);
        }

        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode min) || !marked.contains(min)) continue;
            if (consumedByChain.contains(min)) continue;    // a link, not the end
            chains++;
            record(terminalOf(insns, frames, index, min), jarName);
        }
    }

    /** What consumes the value this chain produces, named by opcode. */
    private static String terminalOf(AbstractInsnNode[] insns, Frame<SourceValue>[] frames,
                                     Map<AbstractInsnNode, Integer> index, MethodInsnNode end) {
        // A void call has no value to consume and is its own terminal -- the 11-argument
        // addVertex is the case, and it already ends its vertex in 1.20.1's own implementation.
        if (Type.getReturnType(end.desc).getSort() == Type.VOID) return "VOID_FORM";

        for (int i = 0; i < insns.length; i++) {
            Frame<SourceValue> f = frames[i];
            if (f == null) continue;
            int consumed = consumedSlots(insns[i]);
            if (consumed < 0) continue;
            for (int s = 0; s < consumed && s < f.getStackSize(); s++) {
                SourceValue v = f.getStack(f.getStackSize() - 1 - s);
                if (v.insns.contains(end)) return opcodeName(insns[i]);
            }
        }
        return "UNCONSUMED";
    }

    /**
     * How many stack slots an instruction reads, or -1 when this does not need to know.
     *
     * Only the shapes that can plausibly consume a chain are listed; anything else returning -1
     * makes it {@code UNCONSUMED}, which is reported rather than assumed away.
     */
    private static int consumedSlots(AbstractInsnNode insn) {
        return switch (insn.getOpcode()) {
            case Opcodes.POP, Opcodes.ASTORE, Opcodes.ARETURN, Opcodes.ATHROW,
                 Opcodes.CHECKCAST, Opcodes.INSTANCEOF, Opcodes.MONITORENTER,
                 Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.ARRAYLENGTH -> 1;
            case Opcodes.PUTFIELD, Opcodes.AASTORE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> 3;
            case Opcodes.PUTSTATIC -> 1;
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL ->
                    insn instanceof MethodInsnNode min
                            ? Type.getArgumentTypes(min.desc).length + 1 : -1;
            case Opcodes.INVOKESTATIC ->
                    insn instanceof MethodInsnNode min
                            ? Type.getArgumentTypes(min.desc).length : -1;
            default -> -1;
        };
    }

    private static String opcodeName(AbstractInsnNode insn) {
        return switch (insn.getOpcode()) {
            case Opcodes.POP -> "POP";
            case Opcodes.ASTORE -> "ASTORE (stored in a local)";
            case Opcodes.ARETURN -> "ARETURN (returned)";
            case Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> "PUTFIELD (stored in a field)";
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE,
                 Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC -> "passed to a call";
            default -> "other (" + insn.getOpcode() + ")";
        };
    }

    private static boolean isChainRoot(MethodInsnNode min) {
        return min.name.equals("addVertex") && min.owner.startsWith(PREFIX);
    }

    private static boolean returnsConsumer(MethodInsnNode min) {
        if (!min.owner.startsWith(PREFIX)) return false;
        Type ret = Type.getReturnType(min.desc);
        return ret.getSort() == Type.OBJECT && ret.getInternalName().equals(VERTEX_CONSUMER);
    }

    private static boolean receiverIsMarked(Frame<SourceValue> f, MethodInsnNode min,
                                            Set<AbstractInsnNode> marked) {
        for (AbstractInsnNode src : receiverSources(f, min)) {
            if (marked.contains(src)) return true;
        }
        return false;
    }

    /** The instructions that produced this call's receiver — the deepest slot it pops. */
    private static List<AbstractInsnNode> receiverSources(Frame<SourceValue> f, MethodInsnNode min) {
        if (min.getOpcode() == Opcodes.INVOKESTATIC) return List.of();
        int args = Type.getArgumentTypes(min.desc).length;
        int depth = 0;
        for (Type t : Type.getArgumentTypes(min.desc)) depth += t.getSize();
        int slot = f.getStackSize() - 1 - depth;
        if (slot < 0 || args < 0) return List.of();
        return new ArrayList<>(f.getStack(slot).insns);
    }

    private static void record(String terminal, String jarName) {
        terminals.merge(terminal, 1, Integer::sum);
        terminalJars.computeIfAbsent(terminal, k -> new HashSet<>()).add(jarName);
    }
}
