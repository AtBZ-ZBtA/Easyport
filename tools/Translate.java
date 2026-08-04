import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Easyport transformer: rewrites a mod jar from Forge 1.20.1 to NeoForge 1.21.1.
 *
 * Deliberately does as little as possible. Under the shim-first architecture most loader-API
 * references need no rewriting at all — forge-compat ships a real net.minecraftforge.* tree,
 * so a mod's existing references resolve against it untouched. Rewriting is reserved for
 * cases where a shim is impossible, which in practice means net.minecraft.*: that package is
 * owned by the game and cannot be shadowed.
 *
 * Three passes, in this order:
 *
 *   1. SRG -> official member names. Forge 1.20.1 runs SRG at runtime, NeoForge runs official
 *      Mojang names, so this must happen before anything else — every later rule is written
 *      against official names and would not match otherwise.
 *   2. Structural and call-site rules from the rule file.
 *   3. Resources: descriptor rename, datapack directory renames.
 *
 * Anything it cannot translate is reported rather than guessed. Inventing a plausible target
 * for a removed API is the measured failure mode from handport/ — 5 false positives out of 26
 * symbols — and a jar that loads while quietly doing the wrong thing is worse than one that
 * refuses.
 *
 * Run:
 *   java -cp "<asm>;<asm-tree>;<asm-commons>" tools/Translate.java \
 *       <inputJar> <outputJar> <srg2official.tsv> <rules.tsv>
 */
public class Translate {

    /** 1.21 singularised the datapack tree. Mined from the corpus, not assumed. */
    private static final Map<String, String> DIR_RENAMES = new LinkedHashMap<>();
    static {
        DIR_RENAMES.put("recipes", "recipe");
        DIR_RENAMES.put("loot_tables", "loot_table");
        DIR_RENAMES.put("advancements", "advancement");
        DIR_RENAMES.put("structures", "structure");
        DIR_RENAMES.put("predicates", "predicate");
        DIR_RENAMES.put("item_modifiers", "item_modifier");
        DIR_RENAMES.put("functions", "function");
    }
    /** Tag subdirectories singularised alongside their parent registries. */
    private static final Map<String, String> TAG_RENAMES = new LinkedHashMap<>();
    static {
        TAG_RENAMES.put("blocks", "block");
        TAG_RENAMES.put("items", "item");
        TAG_RENAMES.put("fluids", "fluid");
        TAG_RENAMES.put("entity_types", "entity_type");
        TAG_RENAMES.put("game_events", "game_event");
    }

    record CtorRule(String owner, String ctorDesc, String factoryName, String factoryDesc) {}

    /** A constructor whose two arguments were reordered, optionally narrowing the new first one. */
    record SwapRule(String owner, String oldDesc, String newDesc, String castTop) {}

    /** A method that kept its shape but changed owner or name. The plain call-site rewrite. */
    record RenameRule(String owner, String name, String desc,
                      String newOwner, String newName, String newDesc) {}

    private final Map<String, String> srgToOfficial = new HashMap<>();
    private final List<CtorRule> ctorRules = new ArrayList<>();
    private final List<SwapRule> swapRules = new ArrayList<>();
    private final List<RenameRule> renameRules = new ArrayList<>();
    private final List<RenameRule> methodToStaticRules = new ArrayList<>();
    private final Map<String, String> fieldRetypes = new LinkedHashMap<>();
    private final List<RenameRule> fieldToStaticRules = new ArrayList<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final Map<String, String> typeRenames = new LinkedHashMap<>();
    private final Map<String, String> prefixRenames = new LinkedHashMap<>();

    private final Map<String, Integer> appliedCounts = new TreeMap<>();
    private final Map<String, Integer> unresolved = new TreeMap<>();
    private int classesRewritten = 0, resourcesMoved = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: java -cp \"<asm>;<asm-tree>;<asm-commons>\" tools/Translate.java "
                             + "<inputJar> <outputJar> <srg2official.tsv> <rules.tsv>");
            System.exit(2);
        }
        Translate t = new Translate();
        // Optional trailing arguments are target-platform jars. With them, mixins whose target
        // class no longer exists can be identified and dropped; without them that check is
        // skipped entirely rather than guessed at.
        if (args.length > 4) {
            t.loadTargetIndex(java.util.Arrays.copyOfRange(args, 4, args.length));
        }
        t.run(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
    }

    /**
     * Every class the target platform provides, used to decide whether a mixin can still apply.
     *
     * Empty when no platform jars are supplied, in which case mixin stripping is skipped
     * entirely — guessing that a target is absent would silently delete working mixins.
     */
    private final Set<String> targetClasses = new HashSet<>();

    /** Mixin classes to drop from their configs because their target no longer exists. */
    private final Set<String> deadMixins = new LinkedHashSet<>();

    /** Target classes that are interfaces, for detecting a mixin whose target changed kind. */
    private final Set<String> targetInterfaces = new HashSet<>();

    /** Target class -> its declared fields as "name desc", for validating {@code @Shadow}. */
    private final Map<String, Set<String>> targetFields = new HashMap<>();

    /** Vanilla class -> every member it declares, as "name desc". Fields and methods together. */
    private final Map<String, Set<String>> targetMembers = new HashMap<>();
    private final Map<String, String> targetSuper = new HashMap<>();
    private final Map<String, List<String>> targetIfaces = new HashMap<>();
    private final Map<String, Set<String>> resolvedMemberCache = new HashMap<>();

    /** "owner.member" -> the T inside its {@code Holder<T>}, read from the generic signature. */
    private final Map<String, String> holderValueType = new HashMap<>();

    /** Vanilla classes 1.21 made final, and the methods it made final, for the hierarchy checks. */
    private final Set<String> targetFinalClasses = new HashSet<>();
    private final Map<String, Set<String>> targetFinalMethods = new HashMap<>();

    private void loadTargetIndex(String[] jars) {
        for (String j : jars) {
            Path p = Paths.get(j);
            if (!Files.isRegularFile(p)) continue;
            try (ZipFile zip = new ZipFile(p.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class")) continue;
                    String internal = n.substring(0, n.length() - 6);
                    targetClasses.add(internal);
                    // Vanilla and NeoForge are indexed in detail; nothing else is. Mixins target
                    // net.minecraft, and the hierarchy checks need net.neoforged too -- placebo's
                    // only remaining blocker is overriding a method NeoForge made final. Reading
                    // every member of every jar on the path would triple a scan that runs once
                    // per mod for no finding either check can use.
                    if (!internal.startsWith("net/minecraft/")
                            && !internal.startsWith("net/neoforged/")) continue;
                    try {
                        ClassNode node = new ClassNode();
                        new ClassReader(read(zip, e)).accept(node,
                                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                        if ((node.access & Opcodes.ACC_INTERFACE) != 0) targetInterfaces.add(internal);
                        Set<String> fields = new HashSet<>();
                        if (node.fields != null) {
                            for (var f : node.fields) fields.add(f.name + " " + f.desc);
                        }
                        targetFields.put(internal, fields);

                        // Methods and hierarchy, for the Holder adaptation passes. Those ask a
                        // different question to @Shadow validation -- "what does this type have,
                        // including inherited" rather than "what does it declare" -- so the two
                        // indexes are kept apart rather than one being made to serve both.
                        Set<String> members = new HashSet<>(fields);
                        if (node.methods != null) {
                            for (var m : node.methods) members.add(m.name + " " + m.desc);
                        }
                        targetMembers.put(internal, members);
                        if (node.superName != null) targetSuper.put(internal, node.superName);
                        if (node.interfaces != null) targetIfaces.put(internal, node.interfaces);
                        if ((node.access & Opcodes.ACC_FINAL) != 0) targetFinalClasses.add(internal);
                        if (node.methods != null) {
                            Set<String> fin = new HashSet<>();
                            for (var m : node.methods) {
                                if ((m.access & Opcodes.ACC_FINAL) != 0) fin.add(m.name + " " + m.desc);
                            }
                            if (!fin.isEmpty()) targetFinalMethods.put(internal, fin);
                        }

                        // Generic signatures, kept only for Holder-typed members. The erased
                        // descriptor says Holder and nothing else; the type argument inside the
                        // signature is the only place the platform records what is *in* the
                        // holder, and unwrapping has to cast to something.
                        if (node.fields != null) {
                            for (var f : node.fields) {
                                if (f.signature != null && HOLDER_DESC.equals(f.desc)) {
                                    String arg = holderTypeArgument(f.signature);
                                    if (arg != null) holderValueType.put(internal + "." + f.name, arg);
                                }
                            }
                        }
                        if (node.methods != null) {
                            for (var m : node.methods) {
                                if (m.signature == null || !m.desc.endsWith(HOLDER_DESC)) continue;
                                String ret = m.signature.substring(m.signature.lastIndexOf(')') + 1);
                                String arg = holderTypeArgument(ret);
                                if (arg != null) holderValueType.put(internal + "." + m.name, arg);
                            }
                        }
                    } catch (Exception ignored) {
                        // Unparseable class: it still counts as present, just not inspectable.
                    }
                }
            } catch (IOException ignored) {
                // A missing or unreadable platform jar just means a smaller index; the guard in
                // stripDeadMixins keeps that from being interpreted as "target absent".
            }
        }
    }

    private void run(Path in, Path out, Path mappings, Path rules) throws Exception {
        loadMappings(mappings);
        loadRules(rules);
        System.out.printf("Loaded %d SRG mappings, %d type renames, %d ctor rules, %d removed-API entries%n",
                          srgToOfficial.size(), typeRenames.size(), ctorRules.size(), removed.size());

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {

            findDeadMixins(zip);

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                byte[] data = read(zip, e);
                String name = e.getName();

                // Bundled libraries the target platform already provides must be dropped, not
                // copied. Mods ship dependencies under META-INF/jarjar/, and when two mods
                // bundle the same library -- or NeoForge already carries it -- the duplicates
                // become separate modules exporting one package and the layer refuses to
                // resolve:
                //   Modules MixinExtras and mixinextras.neoforge export package
                //   com.llamalad7.mixinextras to ...
                // That kills the whole launch, so it reads as the candidate mod failing rather
                // than as a packaging conflict between its dependencies.
                if (name.startsWith("META-INF/jarjar/") && shouldDropBundled(name)) {
                    count(appliedCounts, "JARJAR_DROP " + name.substring(name.lastIndexOf('/') + 1));
                    continue;
                }

                // Bundled mods must be translated too, not copied. They are ordinary Forge
                // 1.20.1 jars carrying META-INF/mods.toml, so NeoForge rejects them outright
                // and the outer mod then fails on a dependency that is physically present --
                // create ships flywheel and ponder inside itself and still reports both as
                // "not installed".
                if (name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    data = transformNestedJar(data, name, 1);
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(data);
                    zos.closeEntry();
                    continue;
                }

                if (name.endsWith(".class")) {
                    data = transformClass(data);
                    classesRewritten++;
                } else {
                    String moved = renamePath(name);
                    if (!moved.equals(name)) { resourcesMoved++; name = moved; }
                    if (name.equals("META-INF/neoforge.mods.toml")) data = migrateDescriptor(data);
                    // The jarjar index lists every bundled jar by path. Dropping a jar without
                    // removing its entry leaves FML resolving a path that is no longer there,
                    // which surfaces as "Invalid paths argument" and an IOException naming the
                    // *outer* mod -- so create.jar reads as corrupt when the real cause is a
                    // stale index entry for a library that was deliberately removed.
                    if (name.equals("META-INF/jarjar/metadata.json")) data = pruneJarJarIndex(data);
                    if (isMixinConfig(name) && !deadMixins.isEmpty()) data = stripDeadMixins(data);
                    // Access transformers address members by name as text, exactly like refmaps,
                    // and are just as invisible to the bytecode remapper. 234 of the 433 corpus
                    // jars ship one -- more than half -- and every line in every one of them
                    // names an SRG member that does not exist under official mappings.
                    //
                    // Nothing reports it. The AT is parsed, no line matches, the widening simply
                    // does not happen, and the mod loads normally until it touches the member it
                    // asked to be made public. Placebo surfaced it as an IllegalAccessError on
                    // TextColor.NAMED_COLORS, several rounds after its classes translated cleanly.
                    if (isRefmap(name) || isAccessTransformer(name)) {
                        data = remapSrgInText(new String(data, StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8);
                    }
                }
                // Signatures cover the pre-translation bytes and cannot survive rewriting; a
                // stale signature file makes the jar fail verification outright.
                if (name.startsWith("META-INF/") &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue;

                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        }
        report(out);
    }

    // ---- bytecode ----------------------------------------------------------------------

    private byte[] transformClass(byte[] data) {
        // Pass 1: SRG -> official. Must precede rule matching, which uses official names.
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(new ClassRemapper(node, new Remapper() {
            @Override public String mapMethodName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapFieldName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapInvokeDynamicMethodName(String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            /**
             * Type renames, applied only to the explicitly listed types.
             *
             * Deliberately not a blanket net.minecraftforge -> net.neoforged rename: under
             * shim-first, most references should keep resolving against forge-compat. Only
             * types the loader itself scans for by name must move, because a shimmed copy is
             * a different type to that scan.
             */
            @Override public String map(String internalName) {
                String renamed = typeRenames.get(internalName);
                if (renamed != null) {
                    if (!renameTargetExists(renamed)) {
                        count(unresolved, "TYPE_RENAME target missing: " + renamed);
                        return internalName;
                    }
                    count(appliedCounts, "TYPE_RENAME " + internalName);
                    return renamed;
                }
                // Scoped prefix renames, for packages that are wholly event classes. Events are
                // dispatched by exact class identity, so every one of them must be rewritten --
                // enumerating several hundred individually would be noise, and any that is
                // missed fails silently by never firing.
                for (var e : prefixRenames.entrySet()) {
                    if (internalName.startsWith(e.getKey())) {
                        String target = e.getValue() + internalName.substring(e.getKey().length());
                        if (!renameTargetExists(target)) {
                            count(unresolved, "TYPE_PREFIX target missing: " + target);
                            return internalName;
                        }
                        count(appliedCounts, "TYPE_PREFIX " + e.getKey());
                        return target;
                    }
                }
                return internalName;
            }
        }), 0);

        fixEventBusSubscriber(node);
        fixIllegalHierarchy(node);

        // Mixin annotations address their targets as text, which the remapper never sees.
        remapAnnotationStrings(node.visibleAnnotations);
        remapAnnotationStrings(node.invisibleAnnotations);
        for (MethodNode m : node.methods) {
            remapAnnotationStrings(m.visibleAnnotations);
            remapAnnotationStrings(m.invisibleAnnotations);
        }
        for (var f : node.fields) {
            remapAnnotationStrings(f.visibleAnnotations);
            remapAnnotationStrings(f.invisibleAnnotations);
        }

        // Pass 2: structural rules.
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            applyRenameRules(m.instructions);
            applyMethodToStaticRules(m.instructions);
            applyFieldRetypeRules(m.instructions);
            applyFieldToStaticRules(m.instructions);
            applyCtorRules(m.instructions);
            applySwapRules(m.instructions);
            // Unwrap before wrap, and both after the explicit rules. Unwrap normalises what the
            // mod receives back to its 1.20.1 static types, which is what lets the wrap pass
            // decide about each argument by looking at the call site alone. Running them the
            // other way round would have the wrap pass reasoning about values whose type the
            // unwrap pass is about to change.
            applyHolderUnwrap(m.instructions);
            applyWrapAdapters(m);
            recordRemoved(m.instructions);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Rewrites {@code new Foo(a, b)} into {@code Foo.factory(a, b)}.
     *
     * The constructor call is three instructions apart from its allocation: NEW and DUP run
     * before the arguments are pushed, INVOKESPECIAL after. Removing the first two and
     * switching the third to INVOKESTATIC leaves the operand stack exactly as the factory
     * expects, so this is safe without any stack analysis — but only because NEW/DUP are
     * adjacent, which javac always emits for a plain constructor call. A NEW without an
     * immediately following DUP means the result is discarded or the pattern is something
     * else entirely, so it is left alone rather than guessed at.
     */
    private void applyCtorRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKESPECIAL || !min.name.equals("<init>")) continue;

            CtorRule rule = null;
            for (CtorRule r : ctorRules) {
                if (r.owner().equals(min.owner) && r.ctorDesc().equals(min.desc)) { rule = r; break; }
            }
            if (rule == null) continue;

            TypeInsnNode newInsn = findMatchingNew(insns, min);
            if (newInsn == null) {
                count(unresolved, "CTOR_TO_STATIC (no NEW/DUP pair) " + min.owner + min.desc);
                continue;
            }
            AbstractInsnNode dup = newInsn.getNext();
            insns.remove(newInsn);
            insns.remove(dup);

            min.setOpcode(Opcodes.INVOKESTATIC);
            min.name = rule.factoryName();
            min.desc = rule.factoryDesc();
            min.itf = false;
            count(appliedCounts, "CTOR_TO_STATIC " + rule.owner() + "#" + rule.factoryName());
        }
    }

    private static final String EBS_DESC = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    private static final String EBS_BUS_DESC = "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;";
    private static final String SUBSCRIBE_DESC = "Lnet/neoforged/bus/api/SubscribeEvent;";

    /**
     * Repairs {@code @EventBusSubscriber} after the type rename, in two ways the remapper
     * cannot handle on its own.
     *
     * **The enum constant.** Forge's bus enum is FORGE/MOD, NeoForge's is GAME/MOD. Renaming
     * the *type* is not enough: ASM rewrites an annotation's enum descriptor but passes the
     * constant *name* through untouched, so {@code bus = Bus.FORGE} survives as a reference to
     * a constant that no longer exists. That fails at annotation resolution rather than at
     * load, which makes it hard to trace back here.
     *
     * **Classes with no handlers.** FML registers annotated classes itself, and NeoForge throws
     * when handed one with no {@code @SubscribeEvent} methods where Forge accepted it silently.
     * The ForgeEventBus shim absorbs that for explicit {@code register()} calls, but cannot for
     * these — FML does the registering, so the shim is never involved. The only fix available
     * at translation time is to drop the annotation, which restores Forge's behaviour exactly:
     * a class with no handlers subscribes to nothing either way.
     */
    private void fixEventBusSubscriber(ClassNode node) {
        if (node.visibleAnnotations == null) return;

        var subscriber = node.visibleAnnotations.stream()
                .filter(a -> EBS_DESC.equals(a.desc)).findFirst().orElse(null);
        if (subscriber == null) return;

        boolean hasHandlers = node.methods.stream().anyMatch(m ->
                m.visibleAnnotations != null &&
                m.visibleAnnotations.stream().anyMatch(a -> SUBSCRIBE_DESC.equals(a.desc)));

        if (!hasHandlers) {
            node.visibleAnnotations.remove(subscriber);
            count(appliedCounts, "EBS_STRIP (no @SubscribeEvent methods) " + node.name);
            return;
        }

        if (subscriber.values == null) return;
        // AnnotationNode stores values as alternating name/value; an enum value is a
        // String[]{descriptor, constantName}.
        for (int i = 1; i < subscriber.values.size(); i += 2) {
            if (subscriber.values.get(i) instanceof String[] enumRef
                    && enumRef.length == 2
                    && EBS_BUS_DESC.equals(enumRef[0])
                    && "FORGE".equals(enumRef[1])) {
                enumRef[1] = "GAME";
                count(appliedCounts, "EBS_BUS FORGE -> GAME");
            }
        }
    }

    /**
     * Rewrites calls to methods that kept their shape but changed owner or name.
     *
     * The simplest rule kind, and the one a rename table handles well — which is exactly the
     * 13-of-13 the corpus mining got right in the hand-port scoring. Runs before the
     * structural rules so those match against already-corrected owners.
     */
    private void applyRenameRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            for (RenameRule r : renameRules) {
                if (!r.owner().equals(min.owner) || !r.name().equals(min.name)
                        || !r.desc().equals(min.desc)) continue;
                min.owner = r.newOwner();
                min.name = r.newName();
                min.desc = r.newDesc();
                count(appliedCounts, "RENAME_METHOD " + r.owner() + "#" + r.name()
                                     + " -> " + r.newName());
                break;
            }
        }
    }

    /**
     * Retypes every field read on a holder class whose constants were wrapped in {@code Holder}.
     *
     * 1.21 turned classes like {@code ArmorMaterials} from enums implementing the thing they
     * described into plain holders of {@code Holder<ArmorMaterial>} constants. The field names
     * did not change, so nothing in the SRG mapping or the type renames touches this -- but the
     * *descriptor* on every GETSTATIC did, and the verifier reads descriptors.
     *
     * That makes it fail before the code ever runs, with a VerifyError rather than a
     * NoSuchFieldError: the verifier sees {@code ArmorMaterials} pushed where
     * {@code ArmorMaterial} is required, and in 1.21 the former no longer implements the latter.
     * geckolib and aquaculture both die on exactly this.
     *
     * Written per-owner rather than per-field deliberately. The change was wholesale -- every
     * constant on the class was wrapped at once -- so enumerating twenty fields would be twenty
     * chances to miss one, and a missed one fails the same way.
     *
     * The constructor that consumes the value needs its own rule; RENAME_METHOD handles that,
     * since a constructor whose descriptor changed but whose argument order did not is just a
     * call-site rewrite.
     */
    private void applyFieldRetypeRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode fin)) continue;
            String newDesc = fieldRetypes.get(fin.owner);
            if (newDesc == null || newDesc.equals(fin.desc)) continue;
            fin.desc = newDesc;
            count(appliedCounts, "FIELD_RETYPE " + fin.owner + " -> " + newDesc);
        }
    }

    /**
     * Rewrites a static field read into a static method call.
     *
     * For constants the target platform deleted outright, where the value can still be computed.
     * {@code IEnvironment.Keys.NAMING} is the case this exists for: cyclopscore reads it to decide
     * whether it is running in a development environment, and modlauncher removed the field, so
     * the read fails with NoSuchFieldError during mod construction.
     *
     * A GETSTATIC pushes exactly one value and an INVOKESTATIC with no arguments pushes exactly
     * one value, so swapping the instruction is the whole transformation -- no stack adjustment,
     * the same shape as METHOD_TO_STATIC. The descriptors must match, which the rule states
     * explicitly rather than inferring.
     */
    private void applyFieldToStaticRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode fin)) continue;
            if (fin.getOpcode() != Opcodes.GETSTATIC) continue;
            for (RenameRule r : fieldToStaticRules) {
                if (!r.owner().equals(fin.owner) || !r.name().equals(fin.name)
                        || !r.desc().equals(fin.desc)) continue;
                insns.set(fin, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        r.newOwner(), r.newName(), r.newDesc(), false));
                count(appliedCounts, "FIELD_TO_STATIC " + r.owner() + "#" + r.name()
                                     + " -> " + r.newOwner() + "#" + r.newName());
                break;
            }
        }
    }

    /**
     * Rewrites an instance call into a static one that takes the receiver as its first argument.
     *
     * The case this exists for: a type that *must* be renamed -- because the loader dispatches
     * on it, so a shim would be a different class and never fire -- whose replacement is missing
     * a method the corpus calls. {@code RegisterEvent} is the first instance. Neither a rename
     * nor a shim can fix it alone; the call site has to move to a helper.
     *
     * No stack manipulation is required, which is what makes this cheap. When INVOKEVIRTUAL
     * executes, the receiver is already below the arguments in exactly the position
     * INVOKESTATIC reads its first parameter from. Changing the opcode and prepending the
     * receiver type to the descriptor is the whole transformation.
     *
     * Distinct from RENAME_METHOD, which preserves the opcode and therefore needs the new owner
     * to declare a matching *instance* method.
     */
    private void applyMethodToStaticRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKEVIRTUAL
                    && min.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
            for (RenameRule r : methodToStaticRules) {
                if (!r.owner().equals(min.owner) || !r.name().equals(min.name)
                        || !r.desc().equals(min.desc)) continue;
                MethodInsnNode replacement = new MethodInsnNode(
                        Opcodes.INVOKESTATIC, r.newOwner(), r.newName(), r.newDesc(), false);
                insns.set(min, replacement);
                count(appliedCounts, "METHOD_TO_STATIC " + r.owner() + "#" + r.name()
                                     + " -> " + r.newOwner() + "#" + r.newName());
                break;
            }
        }
    }

    /**
     * Reorders the two arguments of a constructor whose signature was rearranged upstream.
     *
     * Example: {@code TorchBlock(Properties, ParticleOptions)} became
     * {@code TorchBlock(SimpleParticleType, Properties)} in 1.21 — reordered *and* narrowed.
     *
     * Both arguments are already on the operand stack in source order when INVOKESPECIAL is
     * reached, so a single SWAP reorders them. Where the new signature also narrows a type, a
     * CHECKCAST must be emitted *before* the swap, while the value being narrowed is still on
     * top — after the swap it is buried and no longer reachable without more shuffling.
     *
     * Only valid for two category-1 arguments. A long or double occupies two stack slots and
     * SWAP would corrupt it, so those are refused and reported rather than mangled.
     */
    private void applySwapRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKESPECIAL || !min.name.equals("<init>")) continue;

            for (SwapRule r : swapRules) {
                if (!r.owner().equals(min.owner) || !r.oldDesc().equals(min.desc)) continue;

                if (hasWideArgument(r.oldDesc())) {
                    count(unresolved, "SWAP2 refused (wide argument) " + r.owner() + r.oldDesc());
                    break;
                }
                if (r.castTop() != null && !r.castTop().isEmpty()) {
                    insns.insertBefore(min, new TypeInsnNode(Opcodes.CHECKCAST, r.castTop()));
                }
                insns.insertBefore(min, new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                min.desc = r.newDesc();
                count(appliedCounts, "CTOR_SWAP2 " + r.owner());
                break;
            }
        }
    }

    /** True if any argument is a long or double, which occupy two stack slots. */
    private static boolean hasWideArgument(String desc) {
        for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            if (t.getSort() == org.objectweb.asm.Type.LONG || t.getSort() == org.objectweb.asm.Type.DOUBLE) {
                return true;
            }
        }
        return false;
    }

    /** Walks back to the NEW that allocated the object this constructor initialises. */
    private static TypeInsnNode findMatchingNew(InsnList insns, MethodInsnNode ctor) {
        int depth = 0;
        for (AbstractInsnNode p = ctor.getPrevious(); p != null; p = p.getPrevious()) {
            if (p instanceof MethodInsnNode m && m.name.equals("<init>")) depth++;
            if (p instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW
                    && t.desc.equals(ctor.owner)) {
                if (depth == 0) {
                    AbstractInsnNode next = t.getNext();
                    return (next != null && next.getOpcode() == Opcodes.DUP) ? t : null;
                }
                depth--;
            }
        }
        return null;
    }

    /** Records calls into APIs with no replacement, so the report can name them. */
    private void recordRemoved(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            String sym = min.owner + "#" + min.name + min.desc;
            if (removed.contains(sym)) count(unresolved, "REMOVED " + sym);
        }
    }

    // ---- Holder adaptation -------------------------------------------------------------

    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String HOLDER_DESC = "Lnet/minecraft/core/Holder;";
    private static final String HOLDER_BRIDGE = "easyport/bridge/HolderBridge";

    /**
     * Every member a vanilla type has, inherited members included.
     *
     * Inheritance matters more here than it looks. {@code ArmorItem.getMaterial()} is declared on
     * {@code ArmorItem}, but plenty of the calls this pass has to judge land on members a
     * supertype declares, and treating those as absent would have this pass adapting calls that
     * were never broken.
     *
     * Empty when the platform index is not loaded, which switches both adaptation passes off
     * rather than having them guess.
     */
    private Set<String> resolvedTargetMembers(String cls) {
        Set<String> hit = resolvedMemberCache.get(cls);
        if (hit != null) return hit;
        Set<String> all = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(cls);
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (!seen.add(k)) continue;
            Set<String> own = targetMembers.get(k);
            if (own != null) all.addAll(own);
            String sup = targetSuper.get(k);
            if (sup != null) queue.add(sup);
            List<String> ifs = targetIfaces.get(k);
            if (ifs != null) queue.addAll(ifs);
        }
        resolvedMemberCache.put(cls, all);
        return all;
    }

    /**
     * Handles a mod class whose *hierarchy* became illegal, which no call-site rewrite can reach.
     *
     * Offline verification turned up a failure family nothing else in this project could see, and
     * it is not small: six classes across four mods, every one of them fatal at load. 1.21 sealed
     * a lot of vanilla down.
     *
     * <h2>Overriding a method that became final -- fixed</h2>
     *
     * {@code TurtleLandEntity} overrides {@code LivingEntity.canBreatheUnderwater()}, which 1.21
     * made final. The whole class fails to load, taking with it every entity, item and block that
     * class registers.
     *
     * Renaming the declaration out of the way fixes the load. Nothing else has to change: an
     * internal call to {@code this.canBreatheUnderwater()} then resolves to the inherited final
     * method, which is vanilla's own behaviour and the only behaviour available now. What is lost
     * is precisely the mod's override, and that is what the report names.
     *
     * This is a behaviour change, so it is loud. A silently different turtle is worse than a
     * reported one, and the alternative -- refusing to translate the mod -- loses everything else
     * the class carries.
     *
     * <h2>Extending a class or implementing a type that changed kind -- reported</h2>
     *
     * {@code OutOfJarResourceLocation extends ResourceLocation}, now a final record.
     * {@code AquaArmorMaterials implements ArmorMaterial}, now a record rather than an interface.
     * Neither has a mechanical fix: the mod's class exists to *be* that type, and there is no
     * longer a version of that type it can be. Reported here rather than left to surface as an
     * {@code IncompatibleClassChangeError} halfway through a launch.
     */
    private void fixIllegalHierarchy(ClassNode node) {
        if (targetMembers.isEmpty()) return;

        if (node.superName != null && targetFinalClasses.contains(node.superName)) {
            count(unresolved, "HIERARCHY cannot extend final class: " + node.name
                            + " extends " + node.superName);
        }
        for (String itf : node.interfaces) {
            // targetMembers is the "was actually inspected" test, and it has to be. Asking
            // targetInterfaces alone reports every type the index did not look inside as "no
            // longer an interface" -- the first run of this check accused IFluidHandler,
            // IItemHandlerModifiable and BiomeModifier, all of which are interfaces and always
            // were. Same shape as the rename-target validation: never claim absence from a part
            // of the index that was never populated.
            if (targetMembers.containsKey(itf) && !targetInterfaces.contains(itf)) {
                count(unresolved, "HIERARCHY not an interface any more: " + node.name
                                + " implements " + itf);
            }
        }

        for (MethodNode m : node.methods) {
            if (m.name.startsWith("<") || (m.access & Opcodes.ACC_STATIC) != 0) continue;
            String owner = finalOwnerOf(node.superName, m.name + " " + m.desc);
            if (owner == null) continue;
            count(unresolved, "HIERARCHY override of a now-final method dropped: "
                            + node.name + "." + m.name + m.desc + " (final on " + owner + ")");
            m.name = "easyport$" + m.name;
            // All three access bits must be cleared before setting private. Clearing only PUBLIC
            // left a protected method as private|protected, which is 0x6 and a ClassFormatError:
            // "illegal modifiers". The verifier caught it; a launch would have reported it as the
            // mod being corrupt.
            m.access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            m.access |= Opcodes.ACC_PRIVATE;
            // Private, so nothing can dispatch to it by accident. Only the declaration is touched:
            // call sites keep naming the original, and now reach the inherited final method --
            // which is the behaviour that is actually available.
        }
    }

    /** The nearest platform supertype declaring this member final, or null. */
    private String finalOwnerOf(String superName, String member) {
        String cls = superName;
        Set<String> seen = new HashSet<>();
        while (cls != null && seen.add(cls)) {
            Set<String> fin = targetFinalMethods.get(cls);
            if (fin != null && fin.contains(member)) return cls;
            cls = targetSuper.get(cls);
        }
        return null;
    }

    /**
     * What to cast an unwrapped value to.
     *
     * The obvious answer -- whatever the corpus said the type was -- is wrong for the case that
     * matters most. {@code ArmorMaterials} was an *enum implementing* {@code ArmorMaterial} in
     * 1.20.1, so a mod reading {@code ArmorMaterials.IRON} has the descriptor
     * {@code Lnet/minecraft/world/item/ArmorMaterials;}. In 1.21 that class is a plain carrier of
     * {@code Holder<ArmorMaterial>} constants and is no longer an {@code ArmorMaterial} at all;
     * casting the unwrapped value back to it produces bytecode that fails verification with
     * "expected ArmorMaterial, but found ArmorMaterials".
     *
     * So the platform's own generic signature decides, and the corpus descriptor is only the
     * fallback for a member with no signature -- where it is also usually right, because a type
     * that was never an enum did not change identity when it got wrapped.
     */
    private String unwrappedType(String owner, String name, String corpusDesc) {
        String fromSignature = holderValueType.get(owner + "." + name);
        if (fromSignature != null) return fromSignature;
        return corpusDesc.substring(1, corpusDesc.length() - 1);
    }

    /**
     * The {@code T} in a {@code Lnet/minecraft/core/Holder&lt;LT;&gt;;} generic signature.
     *
     * Deliberately literal rather than a full signature parse: a nested or wildcard argument is
     * not something to guess at, so anything that is not a plain class type yields null and the
     * caller falls back.
     */
    private static String holderTypeArgument(String signature) {
        String open = "Lnet/minecraft/core/Holder<";
        int i = signature.indexOf(open);
        if (i < 0) return null;
        int start = i + open.length();
        if (start >= signature.length() || signature.charAt(start) != 'L') return null;
        int end = signature.indexOf(';', start);
        if (end < 0) return null;
        String inner = signature.substring(start + 1, end);
        // A nested generic argument (Holder<Foo<Bar>>) leaves a '<' inside; the erasure is still
        // just the outer name, so take it.
        int lt = inner.indexOf('<');
        if (lt >= 0) inner = inner.substring(0, lt);
        return inner.isEmpty() ? null : inner;
    }

    /**
     * Unwraps vanilla values that 1.21 started returning inside a {@code Holder}.
     *
     * The source half of the Holder boundary. A field read of {@code MobEffects.POISON} or a call
     * to {@code MobEffectInstance.getEffect()} now yields a {@code Holder}; this retypes the
     * reference and immediately unwraps, so what the mod's own bytecode receives is the
     * {@code MobEffect} it was compiled against.
     *
     * Both cases leave the value on top of the stack, which is the whole reason this half is
     * cheap: two instructions inserted directly after, no operand-stack analysis, no reordering.
     * The sink half is not so lucky -- see {@link #applyWrapAdapters}.
     *
     * Deliberately driven by the platform index rather than by a list of wrapped types. Any
     * member whose descriptor differs from the corpus's only by a {@code Holder} in the value
     * position is one of these, and asking the platform means the set never needs updating when
     * a later NeoForge build wraps something else.
     */
    private void applyHolderUnwrap(InsnList insns) {
        if (targetMembers.isEmpty()) return;
        for (AbstractInsnNode insn : insns.toArray()) {
            if (insn instanceof FieldInsnNode fin) {
                if (!fin.owner.startsWith("net/minecraft/")) continue;
                if (fin.getOpcode() != Opcodes.GETSTATIC && fin.getOpcode() != Opcodes.GETFIELD) continue;
                if (!fin.desc.startsWith("L") || fin.desc.equals(HOLDER_DESC)) continue;
                Set<String> members = resolvedTargetMembers(fin.owner);
                if (members.isEmpty()) continue;
                if (members.contains(fin.name + " " + fin.desc)) continue;
                if (!members.contains(fin.name + " " + HOLDER_DESC)) continue;
                String valueType = unwrappedType(fin.owner, fin.name, fin.desc);
                fin.desc = HOLDER_DESC;
                insns.insert(fin, new TypeInsnNode(Opcodes.CHECKCAST, valueType));
                insns.insert(fin, new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER,
                        "value", "()Ljava/lang/Object;", true));
                count(appliedCounts, "HOLDER_UNWRAP field " + fin.owner);
            } else if (insn instanceof MethodInsnNode min) {
                if (!min.owner.startsWith("net/minecraft/")) continue;
                if (min.name.equals("<init>")) continue;
                Type ret = Type.getReturnType(min.desc);
                if (ret.getSort() != Type.OBJECT || ret.getInternalName().equals(HOLDER)) continue;
                Set<String> members = resolvedTargetMembers(min.owner);
                if (members.isEmpty()) continue;
                if (members.contains(min.name + " " + min.desc)) continue;
                String holderDesc = min.desc.substring(0, min.desc.indexOf(')') + 1) + HOLDER_DESC;
                if (!members.contains(min.name + " " + holderDesc)) continue;
                String valueType = unwrappedType(min.owner, min.name, ret.getDescriptor());
                min.desc = holderDesc;
                insns.insert(min, new TypeInsnNode(Opcodes.CHECKCAST, valueType));
                insns.insert(min, new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER,
                        "value", "()Ljava/lang/Object;", true));
                count(appliedCounts, "HOLDER_UNWRAP return " + min.owner + "." + min.name);
            }
        }
    }

    /**
     * Wraps mod values on their way into vanilla methods that now take a {@code Holder}.
     *
     * The sink half, and the awkward one. {@code new MobEffectInstance(effect, 100, 0)} needs the
     * first of three arguments wrapped, and by the time the call executes that value is buried two
     * slots down the operand stack. There is no instruction that reaches past the top.
     *
     * <h2>Spill and reload</h2>
     *
     * The arguments above the one being wrapped are stored into fresh locals, the wrap runs on
     * what is now the top of the stack, and they are pushed back in order:
     *
     * <pre>
     *   ..., effect, 100, 0        ISTORE d ; ISTORE c
     *   ..., effect                INVOKESTATIC HolderBridge.wrap
     *   ..., Holder                ILOAD c ; ILOAD d
     *   ..., Holder, 100, 0        INVOKESPECIAL &lt;init&gt;(Holder,I,I)V
     * </pre>
     *
     * Correct without any data-flow analysis, because the descriptor already says exactly what is
     * on the stack and in what order. Slot sizes come from {@code Type.getSize()}, so a long or
     * double argument spills and reloads as one value rather than being torn in half.
     *
     * <h2>Why not a generated adapter</h2>
     *
     * An earlier version redirected the call to a synthesised static method taking the arguments
     * in their existing order, which needs no stack surgery at all. It is a genuinely neater
     * rewrite and it cannot express the case that matters most: {@code super(...)}. geckolib's
     * {@code WolfArmorItem} extends {@code ArmorItem} and calls its changed constructor with no
     * NEW/DUP to redirect and an uninitialised {@code this} beneath the arguments. That is not an
     * edge case -- subclassing a vanilla type whose constructor was rewrapped is the single
     * commonest shape of this problem, and it is exactly what the previous approach to
     * {@code ArmorMaterials} could not reach.
     *
     * <h2>Matching</h2>
     *
     * A platform member is a candidate when it has the same name and arity, every parameter either
     * matches exactly or is a {@code Holder} where the corpus passes an object, and at least one
     * parameter is such a substitution. <b>An ambiguous match is skipped and reported.</b> Vanilla
     * overloads heavily, and guessing between two candidates produces a translation that links
     * and calls the wrong method.
     */
    private void applyWrapAdapters(MethodNode method) {
        if (targetMembers.isEmpty()) return;
        InsnList insns = method.instructions;
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (!min.owner.startsWith("net/minecraft/")) continue;
            if (min.getOpcode() == Opcodes.INVOKEDYNAMIC) continue;
            Set<String> members = resolvedTargetMembers(min.owner);
            if (members.isEmpty()) continue;
            if (members.contains(min.name + " " + min.desc)) continue;

            String match = null;
            int candidates = 0;
            for (String m : members) {
                int sp = m.indexOf(' ');
                if (sp < 0 || sp != min.name.length() || !m.startsWith(min.name)) continue;
                String cand = m.substring(sp + 1);
                if (!cand.startsWith("(")) continue;              // a field of the same name
                if (!wrapCompatible(min.desc, cand)) continue;
                candidates++;
                match = cand;
            }
            if (candidates == 0) continue;
            if (candidates > 1) {
                count(unresolved, "HOLDER_WRAP ambiguous: " + min.owner + "." + min.name + min.desc);
                continue;
            }

            Type[] have = Type.getArgumentTypes(min.desc);
            Type[] want = Type.getArgumentTypes(match);

            // Highest-indexed argument needing a wrap. Everything above it must spill; everything
            // below it never moves, so wrapping from the top down keeps each step's spill set as
            // small as it can be.
            for (int i = have.length - 1; i >= 0; i--) {
                if (have[i].equals(want[i])) continue;
                int base = method.maxLocals;
                InsnList fix = new InsnList();
                int slot = base;
                for (int j = have.length - 1; j > i; j--) {
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ISTORE), slot));
                    slot += have[j].getSize();
                }
                fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOLDER_BRIDGE, "wrap",
                        "(Ljava/lang/Object;)" + HOLDER_DESC, false));
                // Reload in the mirror order of the spill, so the operand stack is rebuilt
                // exactly as it was rather than reversed.
                for (int j = i + 1; j < have.length; j++) {
                    slot -= have[j].getSize();
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ILOAD), slot));
                }
                insns.insertBefore(min, fix);
                method.maxLocals = base + Arrays.stream(have, i + 1, have.length)
                                               .mapToInt(Type::getSize).sum();
                count(appliedCounts, "HOLDER_WRAP " + min.owner + "." + min.name);
            }
            min.desc = match;
        }
    }

    /**
     * Whether {@code target} is {@code called} with some object parameters wrapped in a Holder.
     *
     * Requires at least one actual substitution, so an unrelated overload that happens to differ
     * only in return type is not treated as a match. Return types must agree exactly: a return
     * that also changed is the {@link #applyHolderUnwrap} case, which runs first and would have
     * handled it.
     */
    private static boolean wrapCompatible(String called, String target) {
        Type[] a = Type.getArgumentTypes(called);
        Type[] b = Type.getArgumentTypes(target);
        if (a.length != b.length) return false;
        if (!Type.getReturnType(called).equals(Type.getReturnType(target))) return false;
        boolean substituted = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(b[i])) continue;
            if (b[i].getSort() == Type.OBJECT && b[i].getInternalName().equals(HOLDER)
                    && a[i].getSort() == Type.OBJECT) {
                substituted = true;
                continue;
            }
            return false;
        }
        return substituted;
    }

    // ---- resources ---------------------------------------------------------------------

    /**
     * Whether a rename target actually exists on the target platform.
     *
     * Renaming to a class that does not exist is worse than not renaming at all: it converts a
     * reportable problem into a ClassNotFoundException naming a {@code net.neoforged} class
     * that Easyport invented, which reads as a platform bug rather than a translation gap.
     *
     * The prefix rules make this easy to hit. {@code net/minecraftforge/event/} →
     * {@code net/neoforged/neoforge/event/} is right for most of the tree, but NeoForge
     * restructured tick events — Forge's single {@code TickEvent$ClientTickEvent} with a phase
     * field became {@code event/tick/ClientTickEvent$Pre} and {@code $Post}. The prefix rule
     * happily produced {@code neoforge/event/TickEvent$ClientTickEvent}, which exists nowhere.
     *
     * Returns true when no platform index is loaded, so validation is skipped rather than
     * blocking every rename.
     */
    private boolean renameTargetExists(String internalName) {
        if (targetClasses.isEmpty()) return true;
        if (targetClasses.contains(internalName)) return true;

        // Only claim a class is absent when the index demonstrably covers its package. An
        // index is never guaranteed complete -- platform classes are spread across several
        // jars, and a forgotten one makes every rename into it look invalid.
        //
        // This has now caused two regressions. First the FML loader jar was missing, so @Mod
        // was rejected. Then distmarker was missing, so OnlyIn was rejected 105 times and every
        // library that had been loading stopped. In both cases the check was confidently wrong
        // about classes that existed.
        //
        // Requiring a sibling in the same package makes the failure mode safe: an unindexed
        // package yields "assume present" and the rename proceeds as it did before validation
        // existed, while a package we genuinely know still catches invented targets like
        // neoforge/event/TickEvent$ClientTickEvent.
        int lastSlash = internalName.lastIndexOf('/');
        if (lastSlash < 0) return true;
        String pkg = internalName.substring(0, lastSlash + 1);
        boolean packageKnown = knownPackages.computeIfAbsent(pkg, p ->
                targetClasses.stream().anyMatch(c -> c.startsWith(p)));
        return !packageKnown;
    }

    /** Cached per-package answers; the scan over targetClasses is otherwise repeated constantly. */
    private final Map<String, Boolean> knownPackages = new HashMap<>();

    /** SRG member names appearing inside text: refmaps, @At targets, @Accessor names. */
    private static final java.util.regex.Pattern SRG_TOKEN =
        java.util.regex.Pattern.compile("\\b([mf]_\\d+_)\\b");

    /**
     * Rewrites SRG member names embedded in strings.
     *
     * The bytecode remapper only reaches real member references. Mixins address their targets
     * as *text* — refmap JSON entries, {@code @At(target = "...m_12345_...")},
     * {@code @Accessor("f_678_")} — and those strings pass through untouched, so every
     * injection point still names a member that does not exist under official mappings.
     *
     * This is why four of the highest-fan-in libraries failed with InvalidInjectionException
     * and InvalidAccessorException after their classes translated cleanly: the code was right
     * and the coordinates pointing into it were stale.
     */
    private String remapSrgInText(String text) {
        if (srgToOfficial.isEmpty() || text == null) return text;
        java.util.regex.Matcher m = SRG_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            String official = srgToOfficial.get(m.group(1));
            if (official != null) changed = true;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    official != null ? official : m.group(1)));
        }
        m.appendTail(sb);
        if (changed) count(appliedCounts, "SRG_IN_TEXT");
        return sb.toString();
    }

    /** Walks annotation values, rewriting SRG names in any string they contain. */
    private void remapAnnotationStrings(List<org.objectweb.asm.tree.AnnotationNode> anns) {
        if (anns == null) return;
        for (var ann : anns) remapAnnotationValues(ann.values);
    }

    private void remapAnnotationValues(List<Object> values) {
        if (values == null) return;
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            if (v instanceof String s) {
                values.set(i, remapSrgInText(s));
            } else if (v instanceof List<?> list) {
                @SuppressWarnings("unchecked") List<Object> l = (List<Object>) list;
                remapAnnotationValues(l);
            } else if (v instanceof org.objectweb.asm.tree.AnnotationNode nested) {
                remapAnnotationValues(nested.values);
            }
        }
    }

    private static boolean isRefmap(String name) {
        return name.endsWith(".json") && name.contains("refmap");
    }

    /**
     * Access transformer configs, which NeoForge still reads from the conventional path.
     *
     * Matched on the filename rather than the exact path: the default location is
     * META-INF/accesstransformer.cfg, but a mod may declare additional ones under other names,
     * and remapping a file that turns out not to be an AT is harmless -- SRG tokens only appear
     * in files that mean them.
     */
    private static boolean isAccessTransformer(String name) {
        return name.startsWith("META-INF/") && name.endsWith(".cfg") && name.contains("accesstransformer");
    }

    private static boolean isMixinConfig(String name) {
        return name.endsWith(".json") && name.contains("mixins") && !name.contains("/");
    }

    /**
     * Finds mixins whose target class no longer exists in the target version.
     *
     * A mixin naming a deleted vanilla class can never apply — architectury's
     * MixinLootDataManager targets {@code LootDataManager}, which 1.21 removed when loot
     * handling moved to registries. Unlike an ordinary class reference this cannot be fixed by
     * relocating a stub: mixins are applied to the real loaded class, so a stand-in in another
     * package is not the thing being patched.
     *
     * Dropping the mixin is the honest outcome. It removes a behaviour the mod intended, so it
     * is reported rather than done quietly — but the alternative is the whole mod failing to
     * load, taking every one of its dependents with it. architectury alone has 12.
     *
     * Only skips a mixin when the target is confidently known to be absent: the target must
     * live in {@code net/minecraft/} and the platform index must be populated. Anything else is
     * left alone, since wrongly deleting a working mixin is far worse than leaving a dead one.
     */
    private void findDeadMixins(ZipFile zip) {
        if (targetClasses.isEmpty()) return;

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!isMixinConfig(e.getName())) continue;
            try {
                String json = new String(read(zip, e), StandardCharsets.UTF_8);
                java.util.regex.Matcher pm = java.util.regex.Pattern
                        .compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (!pm.find()) continue;
                String pkg = pm.group(1).replace('.', '/');

                java.util.regex.Matcher cm = java.util.regex.Pattern
                        .compile("\"([A-Za-z0-9_$.]+)\"").matcher(json);
                while (cm.find()) {
                    String simple = cm.group(1);
                    if (simple.contains(" ") || simple.startsWith("net.")) continue;
                    ZipEntry mixinEntry = zip.getEntry(pkg + "/" + simple.replace('.', '/') + ".class");
                    if (mixinEntry == null) continue;
                    byte[] mixinBytes = read(zip, mixinEntry);
                    for (String target : mixinTargets(mixinBytes)) {
                        if (!target.startsWith("net/minecraft/")) continue;

                        if (!targetClasses.contains(target)) {
                            deadMixins.add(simple);
                            count(unresolved, "MIXIN_DROP (target gone: " + target + ") " + simple);
                            continue;
                        }

                        // A target that became an interface. Mixin refuses to apply a
                        // class-mixin to an interface target and aborts the whole launch --
                        // supermartijn642corelib dies on SpriteResourceLoader, which was a class
                        // in 1.20.1 and is an interface in 1.21. Applying it properly means
                        // rewriting the mixin as an interface mixin with default methods, which
                        // is real surgery; dropping keeps the mod loading and is reported.
                        if (targetInterfaces.contains(target) && !isInterface(mixinBytes)) {
                            deadMixins.add(simple);
                            count(unresolved, "MIXIN_DROP (target became an interface: "
                                              + target + ") " + simple);
                            continue;
                        }

                        // A @Shadow field whose descriptor no longer matches. Mixin looks these
                        // up by name *and* descriptor, so a field that merely changed type reads
                        // as absent and aborts the launch -- curios dies on
                        // ApplyBonusCount.enchantment, which is still there and is now
                        // Holder<Enchantment> rather than Enchantment. Same 1.21 Holder wrapping
                        // that FIELD_RETYPE handles for ordinary code.
                        Set<String> declared = targetFields.get(target);
                        if (declared == null || declared.isEmpty()) continue;
                        for (String missing : unresolvableShadows(mixinBytes, declared)) {
                            deadMixins.add(simple);
                            count(unresolved, "MIXIN_DROP (@Shadow field not found: " + target
                                              + "#" + missing + ") " + simple);
                        }
                    }
                }
            } catch (Exception ignored) {
                // A config we cannot parse is left untouched rather than guessed at.
            }
        }
        if (!deadMixins.isEmpty()) reportOrphanedByDeadMixins(zip);
    }

    /**
     * Reports classes that only the dropped mixins referenced.
     *
     * Stripping a dead mixin keeps the mod loading, which is what it is for. It can also delete
     * content, silently, and that took a full trace through placebo to notice:
     *
     * <pre>
     *   LootTablesMixin  (dropped: LootDataManager removed in 1.21)
     *     -> LootSystem            referenced by nothing else in the jar
     *          -> StackLootEntry   static initialiser
     *               -> Registry.register(LOOT_POOL_ENTRY_TYPE, ...)
     * </pre>
     *
     * The registration sits in a static initialiser, so it only runs when something touches the
     * class, and the mixin was the only path in. Placebo translated cleanly, loaded cleanly, and
     * registered nothing -- no error anywhere.
     *
     * So: after deciding what to drop, walk what those mixins referenced and report anything the
     * rest of the jar never mentions. Not a proof of lost content -- a class can be reached
     * reflectively, or genuinely be mixin-only support code -- but it is the difference between
     * a warning and finding this by hand, once, per mod.
     */
    private void reportOrphanedByDeadMixins(ZipFile zip) {
        Set<String> referencedByDead = new LinkedHashSet<>();
        Set<String> referencedByLiving = new HashSet<>();
        Set<String> ownClasses = new HashSet<>();

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!e.getName().endsWith(".class")) continue;
            String internal = e.getName().substring(0, e.getName().length() - 6);
            ownClasses.add(internal);
            String simple = internal.substring(internal.lastIndexOf('/') + 1);
            try {
                Set<String> refs = referencedClasses(read(zip, e));
                if (deadMixins.contains(simple)) {
                    referencedByDead.addAll(refs);
                } else {
                    // Self-references do not make a class reachable. Every class mentions itself
                    // -- its own methods, its own fields -- so counting those marked everything
                    // as live and the check reported nothing at all on the very mod it was
                    // written from.
                    refs.remove(internal);
                    referencedByLiving.addAll(refs);
                }
            } catch (Exception ignored) {
                // Unreadable class: cannot contribute either way.
            }
        }

        for (String orphan : referencedByDead) {
            if (!ownClasses.contains(orphan)) continue;          // not this mod's code
            if (referencedByLiving.contains(orphan)) continue;   // still reachable
            String simple = orphan.substring(orphan.lastIndexOf('/') + 1);
            if (deadMixins.contains(simple)) continue;           // a dropped mixin itself
            count(unresolved, "MIXIN_ORPHAN (only a dropped mixin referenced it) " + orphan);
        }
    }

    /** Every class this class file mentions anywhere in its constant pool. */
    private static Set<String> referencedClasses(byte[] classBytes) {
        Set<String> out = new LinkedHashSet<>();
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min) out.add(min.owner);
                else if (insn instanceof TypeInsnNode tin) out.add(tin.desc);
                else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fin) out.add(fin.owner);
            }
        }
        if (node.superName != null) out.add(node.superName);
        if (node.interfaces != null) out.addAll(node.interfaces);
        return out;
    }

    private static boolean isInterface(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return (node.access & Opcodes.ACC_INTERFACE) != 0;
    }

    /**
     * {@code @Shadow} fields the target class no longer declares with that exact descriptor.
     *
     * Shadows are resolved by name and descriptor together, so a field whose *type* changed is
     * as unresolvable as one that was deleted -- and fails the same way, by aborting the launch
     * rather than the single mixin.
     *
     * Names are compared post-SRG. The mixin's field is named for the 1.20.1 SRG member at this
     * point in the pipeline only if the remapper has not run yet, so the SRG table is applied
     * here rather than assumed.
     */
    private List<String> unresolvableShadows(byte[] classBytes, Set<String> targetDeclared) {
        List<String> missing = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);
        if (node.fields == null) return missing;

        for (var field : node.fields) {
            if (!hasShadowAnnotation(field.visibleAnnotations)
                    && !hasShadowAnnotation(field.invisibleAnnotations)) continue;
            String name = srgToOfficial.getOrDefault(field.name, field.name);
            if (targetDeclared.contains(name + " " + field.desc)) continue;
            // Present under a different descriptor is the interesting case and the one worth
            // naming; absent entirely is reported the same way but means something else.
            missing.add(name);
        }
        return missing;
    }

    private static boolean hasShadowAnnotation(List<org.objectweb.asm.tree.AnnotationNode> anns) {
        if (anns == null) return false;
        for (var a : anns) {
            if (a.desc != null && a.desc.endsWith("/Shadow;")) return true;
        }
        return false;
    }

    /** Reads the class names a mixin declares in its {@code @Mixin} annotation. */
    private static List<String> mixinTargets(byte[] classBytes) {
        List<String> targets = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);

        // @Mixin has CLASS retention, so ASM files it under invisibleAnnotations. Checking only
        // visibleAnnotations finds nothing and silently concludes every mixin is fine.
        List<org.objectweb.asm.tree.AnnotationNode> all = new ArrayList<>();
        if (node.visibleAnnotations != null) all.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) all.addAll(node.invisibleAnnotations);

        for (var ann : all) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc) || ann.values == null) continue;
            for (int i = 1; i < ann.values.size(); i += 2) {
                if (ann.values.get(i) instanceof List<?> list) {
                    for (Object o : list) {
                        // value = Class[]; targets = String[] of fully-qualified names.
                        if (o instanceof org.objectweb.asm.Type t) targets.add(t.getInternalName());
                        else if (o instanceof String s) targets.add(s.replace('.', '/'));
                    }
                }
            }
        }
        return targets;
    }

    /** Removes dead mixin entries from a config's class lists. */
    private byte[] stripDeadMixins(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        for (String dead : deadMixins) {
            json = json.replaceAll("\\s*\"" + java.util.regex.Pattern.quote(dead) + "\"\\s*,", "")
                       .replaceAll(",\\s*\"" + java.util.regex.Pattern.quote(dead) + "\"\\s*", "")
                       .replaceAll("\"" + java.util.regex.Pattern.quote(dead) + "\"", "");
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Bundled jars can bundle their own; stop before a malformed or hostile jar recurses forever. */
    private static final int MAX_NESTING = 3;

    /**
     * Translates a bundled jar in place, in memory.
     *
     * Applies the same passes as the outer jar — class rewriting, descriptor migration,
     * resource renames — so a bundled mod ends up as loadable as a top-level one. Anything it
     * bundles in turn is translated too, up to {@link #MAX_NESTING}.
     *
     * Failures are swallowed and the original bytes returned. A bundled jar that will not parse
     * is usually a plain library rather than a mod, and losing it outright is worse than
     * shipping it untranslated: the outer mod may not need it rewritten at all.
     */
    private byte[] transformNestedJar(byte[] jarBytes, String name, int depth) {
        if (depth > MAX_NESTING) return jarBytes;
        try {
            Path tmp = Files.createTempFile("easyport-nested", ".jar");
            Files.write(tmp, jarBytes);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            try (ZipFile zip = new ZipFile(tmp.toFile());
                 ZipOutputStream zos = new ZipOutputStream(out)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    byte[] data = read(zip, e);
                    String entryName = e.getName();

                    if (entryName.startsWith("META-INF/jarjar/") && shouldDropBundled(entryName)) continue;
                    if (entryName.startsWith("META-INF/") &&
                        (entryName.endsWith(".SF") || entryName.endsWith(".RSA")
                         || entryName.endsWith(".DSA"))) continue;

                    if (entryName.startsWith("META-INF/jarjar/") && entryName.endsWith(".jar")) {
                        data = transformNestedJar(data, entryName, depth + 1);
                    } else if (entryName.endsWith(".class")) {
                        data = transformClass(data);
                    } else {
                        String moved = renamePath(entryName);
                        if (!moved.equals(entryName)) entryName = moved;
                        if (entryName.equals("META-INF/neoforge.mods.toml")) data = migrateDescriptor(data);
                        if (entryName.equals("META-INF/jarjar/metadata.json")) data = pruneJarJarIndex(data);
                    }
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(data);
                    zos.closeEntry();
                }
            }
            Files.deleteIfExists(tmp);
            count(appliedCounts, "JARJAR_TRANSLATE (depth " + depth + ")");
            return out.toByteArray();
        } catch (Exception e) {
            count(unresolved, "JARJAR_TRANSLATE failed, shipped as-is: " + name);
            return jarBytes;
        }
    }

    /**
     * True if a bundled library should be dropped rather than carried into the output.
     *
     * Matched on filename prefix because bundled jars carry their version in the name and
     * every mod bundles a different one — mixinextras-forge-0.2.0-beta.8 in ars_nouveau,
     * mixinextras-forge-0.4.1 in create.
     *
     * Only libraries NeoForge itself provides belong here. Dropping anything else would remove
     * a dependency nothing replaces.
     *
     * Note what this does *not* fix: bundled jars that stay are copied through untranslated, so
     * they still contain Forge 1.20.1 bytecode. Translating them recursively is the real fix
     * and is not done yet.
     */
    private static boolean shouldDropBundled(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
        return file.startsWith("mixinextras");
    }

    /**
     * Removes entries for bundled jars that {@link #shouldDropBundled} discarded.
     *
     * Splits the "jars" array by brace depth rather than by regex. Each entry contains nested
     * "identifier" and "version" objects, so a character-class pattern cannot span one — an
     * earlier attempt matched nothing and emitted an empty index, which is strictly worse than
     * the stale index it was meant to fix.
     */
    private byte[] pruneJarJarIndex(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        int arrayStart = json.indexOf('[', json.indexOf("\"jars\""));
        if (arrayStart < 0) return data;

        List<String> kept = new ArrayList<>();
        int depth = 0, entryStart = -1;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) entryStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && entryStart >= 0) {
                    String entry = json.substring(entryStart, i + 1);
                    java.util.regex.Matcher pm = java.util.regex.Pattern
                            .compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(entry);
                    boolean drop = pm.find() && shouldDropBundled(pm.group(1));
                    if (drop) count(appliedCounts, "JARJAR_INDEX_PRUNE");
                    else kept.add(entry.replaceAll("(?m)^", "    ").strip());
                    entryStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return ("{\n  \"jars\": [\n    " + String.join(",\n    ", kept) + "\n  ]\n}\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Applies the descriptor rename and the 1.21 datapack directory singularisation. */
    private static String renamePath(String path) {
        if (path.equals("META-INF/mods.toml")) return "META-INF/neoforge.mods.toml";

        String[] p = path.split("/");
        if (p.length >= 4 && p[0].equals("data")) {
            String renamed = DIR_RENAMES.get(p[2]);
            if (renamed != null) p[2] = renamed;
            if (p[2].equals("tags") && p.length >= 5) {
                String tagRenamed = TAG_RENAMES.get(p[3]);
                if (tagRenamed != null) p[3] = tagRenamed;
            }
            return String.join("/", p);
        }
        return path;
    }

    /**
     * Migrates the mod descriptor.
     *
     * Mining showed the key set is essentially identical between loaders, so this is mostly a
     * file rename — but the *dependency declarations* are not. A renamed descriptor whose
     * dependencies still say `minecraft [1.20.1,1.21)` gets past discovery only to be refused
     * during resolution, which is a more confusing failure than being rejected outright.
     *
     * Version ranges cannot be rewritten by pattern alone: `versionRange` is the same key in
     * every dependency block, so which value it should take depends on the `modId` declared
     * above it. This tracks the enclosing block instead of matching lines in isolation.
     */
    private static byte[] migrateDescriptor(byte[] data) {
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\\R", -1);
        StringBuilder out = new StringBuilder();
        String depModId = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("[[dependencies.")) {
                depModId = null;
            } else if (trimmed.startsWith("[")) {
                depModId = null;                        // left the dependency block entirely
            }

            if (depModId == null || !trimmed.startsWith("versionRange")) {
                var m = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(line);
                if (m.find()) {
                    String id = m.group(1);
                    if (id.equals("forge")) {
                        line = line.replace("\"forge\"", "\"neoforge\"");
                        depModId = "neoforge";
                    } else {
                        depModId = id;
                    }
                }
            }

            if (trimmed.startsWith("versionRange") && depModId != null) {
                String range = switch (depModId) {
                    case "neoforge" -> "[21.1,)";
                    case "minecraft" -> "[1.21.1,1.22)";
                    default -> null;                    // another mod's range; leave it alone
                };
                if (range != null) {
                    line = line.replaceAll("versionRange\\s*=\\s*\"[^\"]*\"",
                                           "versionRange=\"" + range + "\"");
                }
            }

            // NeoForge expresses dependency strength as type=, not the older mandatory= flag.
            if (trimmed.startsWith("mandatory")) {
                line = line.replaceAll("mandatory\\s*=\\s*true", "type=\"required\"")
                           .replaceAll("mandatory\\s*=\\s*false", "type=\"optional\"");
            }

            if (trimmed.startsWith("loaderVersion")) {
                line = line.replaceAll("loaderVersion\\s*=\\s*\"[^\"]*\"", "loaderVersion=\"[4,)\"");
            }

            out.append(line).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- reporting ---------------------------------------------------------------------

    /**
     * The per-jar confidence report. Unresolved entries are the point: a translated jar that
     * loads while quietly doing the wrong thing is worse than one that admits what it could
     * not handle.
     */
    private void report(Path out) throws IOException {
        System.out.printf("%nRewrote %d classes, moved %d resources%n", classesRewritten, resourcesMoved);

        System.out.println("\nApplied:");
        if (appliedCounts.isEmpty()) System.out.println("  (none)");
        appliedCounts.forEach((k, v) -> System.out.printf("  %4d  %s%n", v, k));

        System.out.println("\nUNRESOLVED - these need the vanilla bridge or a structural rule:");
        if (unresolved.isEmpty()) System.out.println("  (none)");
        unresolved.forEach((k, v) -> System.out.printf("  %4d  %s%n", v, k));

        StringBuilder sb = new StringBuilder("status\tcount\tsymbol\n");
        appliedCounts.forEach((k, v) -> sb.append("APPLIED\t").append(v).append('\t').append(k).append('\n'));
        unresolved.forEach((k, v) -> sb.append("UNRESOLVED\t").append(v).append('\t').append(k).append('\n'));
        Path reportFile = Paths.get(out.toString().replaceAll("\\.jar$", "") + ".report.tsv");
        Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("\nWrote " + out + "\n      " + reportFile);
    }

    private static void count(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    // ---- inputs ------------------------------------------------------------------------

    private void loadMappings(Path p) throws IOException {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            if (c.length == 2 && !c[0].equals("srg")) srgToOfficial.put(c[0], c[1]);
        }
    }

    private void loadRules(Path p) throws IOException {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "CTOR_TO_STATIC" -> {
                    if (c.length >= 5) ctorRules.add(new CtorRule(c[1], c[2], c[3], c[4]));
                }
                case "RENAME_METHOD" -> {
                    if (c.length >= 7) renameRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                }
                case "FIELD_TO_STATIC" -> {
                    if (c.length >= 7) fieldToStaticRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                }
                case "FIELD_RETYPE" -> {
                    if (c.length >= 3) fieldRetypes.put(c[1], c[2]);
                }
                case "METHOD_TO_STATIC" -> {
                    if (c.length >= 7) methodToStaticRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                }
                case "CTOR_SWAP2" -> {
                    if (c.length >= 4) swapRules.add(new SwapRule(c[1], c[2], c[3],
                                                                  c.length > 4 ? c[4] : null));
                }
                case "TYPE_PREFIX_RENAME" -> {
                    if (c.length >= 3) prefixRenames.put(c[1], c[2]);
                }
                case "TYPE_RENAME" -> {
                    if (c.length >= 3) typeRenames.put(c[1], c[2]);
                }
                case "REMOVED" -> {
                    if (c.length >= 2) removed.add(c[1]);
                }
                default -> System.err.println("  unknown rule kind, ignored: " + c[0]);
            }
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) { return in.readAllBytes(); }
    }
}
