import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reports every Forge type the corpus uses that neither a shim nor a rename currently resolves.
 *
 * <h2>Why offline</h2>
 *
 * The same answer was previously obtained by launching the game, reading the first
 * ClassNotFoundException, fixing it, and launching again -- roughly ten minutes per missing
 * class, discovered strictly one at a time because the JVM stops at the first one. Architectury
 * alone walked through TickEvent, TextureStitchEvent and EntityItemPickupEvent that way.
 *
 * Every input to that answer is static: which types the corpus references (from MemberScan),
 * which types the target platform has (from its jars), which types forge-compat supplies, and
 * what the rules rewrite. Computing it directly turns a serial hunt into one list, ranked by how
 * many jars each gap blocks.
 *
 * <h2>What counts as resolved</h2>
 *
 * A referenced type is fine if forge-compat ships it, or if a rule maps it to a type the
 * platform actually has. A prefix rule that produces a non-existent target is *not* resolved --
 * that is precisely the TextureStitchEvent case, where the rename looks plausible, the
 * transformer's existence check correctly refuses it, and the original name then fails at load.
 *
 * Usage:
 *   java tools/RenameGaps.java <member-scan-output> <rules.tsv> <forge-compat.jar> <platform.jar>...
 */
public class RenameGaps {

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("usage: RenameGaps <scan.txt> <rules.tsv> <srg2official.tsv> "
                             + "<forge-compat.jar> <platform.jar>...");
            System.exit(2);
        }

        Map<String, Integer> referenced = readScan(Path.of(args[0]));
        Rules rules = readRules(Path.of(args[1]));

        // The corpus is Forge 1.20.1, which runs SRG member names; the platform runs official
        // Mojang names. Translate applies this mapping before anything else, so a report that
        // skips it compares m_246326_ against a class that only ever declares addPotionTab and
        // concludes the member is missing. That was 90 of 495 findings -- 18% noise, all of it
        // in the part of the report meant to be trusted most.
        Map<String, String> srg = readSrg(Path.of(args[2]));

        Set<String> shimmed = classesIn(Path.of(args[3]));
        Set<String> platform = new HashSet<>();
        Set<String> abstractTypes = new HashSet<>();
        List<Path> platformJars = new ArrayList<>();
        for (int i = 4; i < args.length; i++) {
            Path jar = Path.of(args[i]);
            platformJars.add(jar);
            platform.addAll(classesIn(jar));
            abstractTypes.addAll(abstractClassesIn(jar));
        }
        Map<String, Set<String>> platformMembers = declaredMembers(platformJars);

        // Simple name -> platform classes carrying it, for suggesting rename targets.
        Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
        for (String c : platform) {
            bySimpleName.computeIfAbsent(simpleName(c), k -> new ArrayList<>()).add(c);
        }

        List<String> gaps = new ArrayList<>();
        List<String> shadowed = new ArrayList<>();
        List<String> abstractTargets = new ArrayList<>();
        int resolvedShim = 0, resolvedRename = 0;

        for (var entry : referenced.entrySet()) {
            String type = entry.getKey();
            String target = rules.apply(type);

            // Rules are tested before shims because that is the order Translate uses: its
            // remapper rewrites the name and never consults forge-compat. Checking shims first
            // here would report a comfortable picture of a transformer doing something else.
            if (target != null && platform.contains(target)) {
                resolvedRename++;
                // A rename that lands on a real platform class silently wins over a shim for the
                // same type. Sometimes that is the intent; sometimes it means a broadly-scoped
                // prefix rule has quietly disabled a shim written to paper over a signature
                // difference, and the failure appears later as NoSuchMethodError.
                if (shimmed.contains(type)) {
                    shadowed.add(String.format("%5d  %s  -> %s", entry.getValue(), type, target));
                }
                // The quietest way a rename can be wrong: NeoForge split several concrete Forge
                // events into Pre/Post pairs and kept the old name as an abstract parent. The
                // rename resolves, the mod loads, the listener registers -- and nothing ever
                // posts that class, so it never fires and nothing reports it. LivingDamageEvent
                // is exactly this, reached by a prefix rule added here.
                //
                // Narrowed to abstract targets that *have* a Pre or Post nested class. Flagging
                // every abstract target instead produced 91 hits, nearly all of them ordinary
                // interfaces like IItemHandler that are entirely correct targets -- a warning
                // list that size gets skimmed and then ignored, which is worse than none.
                if (abstractTypes.contains(target)
                        && (platform.contains(target + "$Pre") || platform.contains(target + "$Post"))) {
                    abstractTargets.add(String.format("%5d  %s  -> %s  (has Pre/Post)",
                            entry.getValue(), type, target));
                }
                continue;
            }

            if (shimmed.contains(type)) { resolvedShim++; continue; }

            String note = target != null ? "rule -> " + target + " (MISSING)" : "no shim, no rule";
            gaps.add(String.format("%5d  %-72s  %s%s",
                    entry.getValue(), type, note, suggest(type, bySimpleName)));
        }

        // Sorting by the leading count keeps the ranking; the field is width-padded so a plain
        // string compare would order 100 before 99.
        gaps.sort((a, b) -> Integer.parseInt(b.substring(0, 5).trim())
                          - Integer.parseInt(a.substring(0, 5).trim()));

        System.out.println("referenced Forge types: " + referenced.size());
        System.out.println("  resolved by forge-compat: " + resolvedShim);
        System.out.println("  resolved by a rule:       " + resolvedRename);
        System.out.println("  UNRESOLVED:               " + gaps.size());
        if (!shadowed.isEmpty()) {
            System.out.println();
            System.out.println("=== SHIMS SHADOWED BY A RULE (" + shadowed.size() + ") ===");
            System.out.println("A rule redirects these to a real platform class, so forge-compat's");
            System.out.println("version is never reached. Check each is deliberate.");
            shadowed.forEach(System.out::println);
        }
        // A rename that resolves is not a rename that works. NeoForge kept plenty of Forge's
        // names on types with different shapes -- ICapabilityProvider is a three-parameter
        // generic interface there and a one-method LazyOptional producer in Forge -- so a rule
        // can silently redirect a mod onto something it cannot actually use.
        //
        // Checking every member the corpus calls against the target's real member set turns that
        // into a static finding rather than an AbstractMethodError or NoSuchMethodError reached
        // only when the code path runs, which for capabilities may be a long way into a game.
        // The same question asked of forge-compat: does the shim that will resolve this call
        // actually declare it? Shims are hand-written from the Forge API, so a parameter type
        // recalled slightly wrong -- Consumer where Forge had NonNullConsumer -- produces a class
        // that compiles, loads, and throws NoSuchMethodError at every real call site.
        // forge-compat plus the platform, so inheritance resolves. The shims are deliberately thin:
        // IEventBus extends NeoForge's interface and inherits most of its methods, so indexing
        // forge-compat alone reported 327 jars calling an addListener that is right there on the
        // supertype.
        List<Path> shimIndex = new ArrayList<>();
        shimIndex.add(Path.of(args[3]));
        shimIndex.addAll(platformJars);
        Map<String, Set<String>> shimMembers = declaredMembers(shimIndex);
        List<String> shimGaps = new ArrayList<>();

        List<String> mismatched = new ArrayList<>();
        for (MemberRef ref : readMembers(Path.of(args[0]))) {
            String target = rules.apply(ref.owner());

            String refName = official(ref.name(), srg);


            String refDesc = remapDescriptor(ref.desc(), rules, platform);


            if (rules.redirects(ref.owner(), target, refName, refDesc)) continue;



            if ((target == null || !platform.contains(target)) && shimmed.contains(ref.owner())) {
                Set<String> declared = shimMembers.get(ref.owner());
                String want = official(ref.name(), srg) + " " + remapDescriptor(ref.desc(), rules, platform);
                if (declared != null && !declared.contains(want) && !ref.name().equals("<init>")) {
                    shimGaps.add(String.format("%5d  %s.%s%n         shim lacks  %s",
                            ref.jars(), ref.owner(), ref.name(), want));
                }
                continue;
            }

            if (target == null || !platform.contains(target)) continue;
            Set<String> members = platformMembers.get(target);
            if (members == null) continue;
            String wanted = official(ref.name(), srg) + " " + remapDescriptor(ref.desc(), rules, platform);
            if (members.contains(wanted)) continue;
            // Constructors of a renamed type are a different question -- shapes routinely change
            // and CTOR rules exist for that -- so they are left to the ctor rules to report.
            if (ref.name().equals("<init>")) continue;
            mismatched.add(String.format("%5d  %s.%s%n         -> %s  has no  %s",
                    ref.jars(), ref.owner(), official(ref.name(), srg), target, wanted));
        }
        mismatched.sort((a, b) -> Integer.parseInt(b.substring(0, 5).trim())
                                - Integer.parseInt(a.substring(0, 5).trim()));
        if (!mismatched.isEmpty()) {
            System.out.println();
            System.out.println("=== RENAME TARGET MISSING A CALLED MEMBER (" + mismatched.size() + ") ===");
            System.out.println("The rule resolves, but the target does not have what the corpus");
            System.out.println("calls on it. Each is a NoSuchMethodError or AbstractMethodError");
            System.out.println("waiting to happen -- convert the rule to a shim, or drop it.");
            mismatched.forEach(System.out::println);
        }

        shimGaps.sort((a, b) -> Integer.parseInt(b.substring(0, 5).trim())
                              - Integer.parseInt(a.substring(0, 5).trim()));
        if (!shimGaps.isEmpty()) {
            System.out.println();
            System.out.println("=== SHIM MISSING A CALLED MEMBER (" + shimGaps.size() + ") ===");
            System.out.println("forge-compat supplies the class but not this member, or not with");
            System.out.println("this descriptor. Every one is a NoSuchMethodError at a real call");
            System.out.println("site -- the class resolves, so nothing fails until it is used.");
            shimGaps.forEach(System.out::println);
        }

        if (!abstractTargets.isEmpty()) {
            System.out.println();
            System.out.println("=== RENAMES ONTO AN ABSTRACT TYPE (" + abstractTargets.size() + ") ===");
            System.out.println("These resolve and then do nothing if the type is an event: the bus");
            System.out.println("dispatches by exact class and nothing posts an abstract one. Check");
            System.out.println("each against the Pre/Post pair that replaced it.");
            abstractTargets.forEach(System.out::println);
        }
        System.out.println();
        System.out.println("=== UNRESOLVED ===");
        gaps.forEach(System.out::println);
    }

    /**
     * The class's own name, keeping nesting: {@code a/b/Outer$Inner} -> {@code Outer$Inner}.
     *
     * Nesting is kept deliberately. Dropping it would match {@code TickEvent$Phase} against every
     * unrelated {@code Phase} in the platform, and the suggestions are only useful if they are
     * mostly right -- a list padded with plausible-looking wrong answers is worse than no list,
     * because each one still costs a verify cycle to disprove.
     */
    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }

    /**
     * Candidate rename targets, found by matching the class's own name against the platform.
     *
     * Catches the large class of types that only moved package -- which the corpus is full of,
     * since NeoForge reorganised namespaces wholesale without renaming much. It does not catch
     * types that were also *renamed* ({@code TextureStitchEvent$Post} became
     * {@code TextureAtlasStitchedEvent}), and it cannot tell a real move from a coincidence.
     * These are leads to check, not rules to paste.
     */
    private static String suggest(String type, Map<String, List<String>> bySimpleName) {
        List<String> candidates = bySimpleName.get(simpleName(type));
        if (candidates == null || candidates.isEmpty()) return "";
        return "  ?= " + String.join(" | ", candidates.size() > 3
                ? candidates.subList(0, 3) : candidates);
    }

    /** A member the corpus calls: "M owner.name desc" or "F owner.name desc". */
    private record MemberRef(String kind, String owner, String name, String desc, int jars) {}

    /** Pulls the MEMBERS section, which says what the corpus actually calls on each type. */
    private static List<MemberRef> readMembers(Path p) throws IOException {
        List<MemberRef> out = new ArrayList<>();
        boolean inMembers = false;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.startsWith("=== MEMBERS")) { inMembers = true; continue; }
            if (!inMembers) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            // "  143  M net/x/Y.name (Ldesc;)V"
            String[] parts = t.split("\\s+");
            if (parts.length < 4) continue;
            int jars;
            try {
                jars = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            String ownerAndName = parts[2];
            int dot = ownerAndName.lastIndexOf('.');
            if (dot < 0) continue;
            out.add(new MemberRef(parts[1], ownerAndName.substring(0, dot),
                    ownerAndName.substring(dot + 1), parts[3], jars));
        }
        return out;
    }

    /** Every method/field a class declares, as "name desc", including inherited members. */
    private static Map<String, Set<String>> declaredMembers(List<Path> jars) throws IOException {
        Map<String, Set<String>> out = new java.util.HashMap<>();
        Map<String, String> superOf = new java.util.HashMap<>();
        Map<String, List<String>> interfacesOf = new java.util.HashMap<>();

        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    try (var in = zf.getInputStream(e)) {
                        var reader = new org.objectweb.asm.ClassReader(in);
                        Set<String> members = new HashSet<>();
                        reader.accept(new org.objectweb.asm.ClassVisitor(
                                org.objectweb.asm.Opcodes.ASM9) {
                            @Override public org.objectweb.asm.MethodVisitor visitMethod(
                                    int a, String n, String d, String s, String[] ex) {
                                members.add(n + " " + d);
                                return null;
                            }
                            @Override public org.objectweb.asm.FieldVisitor visitField(
                                    int a, String n, String d, String s, Object v) {
                                members.add(n + " " + d);
                                return null;
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE);
                        out.put(reader.getClassName(), members);
                        if (reader.getSuperName() != null) {
                            superOf.put(reader.getClassName(), reader.getSuperName());
                        }
                        interfacesOf.put(reader.getClassName(), List.of(reader.getInterfaces()));
                    } catch (Exception ignored) {
                        // unparseable; treated as having no members
                    }
                }
            }
        }

        // Fold in inherited members. A mod calling toString() or a method declared on a
        // superclass is calling something that exists, and reporting it as missing would bury
        // the real findings under noise.
        Map<String, Set<String>> resolved = new java.util.HashMap<>();
        for (String c : out.keySet()) {
            Set<String> all = new HashSet<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            queue.add(c);
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String k = queue.poll();
                if (!seen.add(k)) continue;
                Set<String> own = out.get(k);
                if (own != null) all.addAll(own);
                String sup = superOf.get(k);
                if (sup != null) queue.add(sup);
                List<String> ifs = interfacesOf.get(k);
                if (ifs != null) queue.addAll(ifs);
            }
            resolved.put(c, all);
        }
        return resolved;
    }

    /**
     * Rewrites every type inside a descriptor exactly as Translate would.
     *
     * "Exactly as Translate would" is the whole point, and getting it wrong made this report
     * useless once already. Translate refuses a rename whose target does not exist in the
     * platform and leaves the original name in place; a version of this that applied rules
     * unconditionally produced descriptors naming classes that never appear in a translated jar
     * -- reporting, for instance, that LazyOptional.of takes a NeoForge NonNullSupplier when
     * NeoForge has no such type and the Forge name survives untouched.
     */
    private static String remapDescriptor(String desc, Rules rules, Set<String> platform) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < desc.length()) {
            char ch = desc.charAt(i);
            if (ch == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) { sb.append(desc.substring(i)); break; }
                String type = desc.substring(i + 1, end);
                String mapped = rules.apply(type);
                boolean applies = mapped != null && platform.contains(mapped);
                sb.append('L').append(applies ? mapped : type).append(';');
                i = end + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * The official name for an SRG member, or the name unchanged.
     *
     * Unchanged is the common case and the right default: Forge's SRG namespace only obfuscates
     * members Mojang obfuscated, so anything Forge or a mod declared itself already reads as its
     * real name and must pass through untouched.
     */
    private static String official(String name, Map<String, String> srg) {
        String mapped = srg.get(name);
        return mapped != null ? mapped : name;
    }

    /** SRG member name -> official name, from the composed table in mappings/. */
    private static Map<String, String> readSrg(Path p) throws IOException {
        Map<String, String> out = new java.util.HashMap<>();
        if (!Files.isRegularFile(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            out.put(line.substring(0, tab), line.substring(tab + 1).trim());
        }
        return out;
    }

    /** Pulls the "<count>  <internal/name>" lines out of a MemberScan TYPES section. */
    private static Map<String, Integer> readScan(Path p) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        boolean inTypes = false;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.startsWith("=== TYPES")) { inTypes = true; continue; }
            if (line.startsWith("=== MEMBERS")) break;
            if (!inTypes) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            int sp = t.indexOf(' ');
            if (sp < 0) continue;
            try {
                out.put(t.substring(sp).trim(), Integer.parseInt(t.substring(0, sp)));
            } catch (NumberFormatException ignored) {
                // header or stray line
            }
        }
        return out;
    }

    private record Rules(Map<String, String> exact, Map<String, String> prefix,
                         Set<String> removed, Set<String> redirectedMembers) {

        /**
         * True if a method-level rule already moves this call somewhere else.
         *
         * Without this the report re-accuses every member that RENAME_METHOD or
         * METHOD_TO_STATIC has already fixed -- IEventBus.post kept appearing as the largest
         * shim gap at 110 jars long after it was redirected to a bridge. A work queue that
         * lists completed work is worse than a shorter one, because the top of it stops being
         * where to look.
         *
         * Rule owners are written post-rename, so both forms are tested.
         */
        boolean redirects(String owner, String renamedOwner, String name, String desc) {
            return redirectedMembers.contains(owner + "\t" + name + "\t" + desc)
                || (renamedOwner != null
                    && redirectedMembers.contains(renamedOwner + "\t" + name + "\t" + desc));
        }
        /** The rewritten name, or null when no rule applies. */
        String apply(String type) {
            if (removed.contains(type)) return null;
            String hit = exact.get(type);
            if (hit != null) return hit;
            for (var e : prefix.entrySet()) {
                if (type.startsWith(e.getKey())) {
                    return e.getValue() + type.substring(e.getKey().length());
                }
            }
            return null;
        }
    }

    private static Rules readRules(Path p) throws IOException {
        Map<String, String> exact = new LinkedHashMap<>();
        Map<String, String> prefix = new LinkedHashMap<>();
        Set<String> removed = new HashSet<>();
        Set<String> redirected = new HashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t");
            if (c.length < 2) continue;
            switch (c[0]) {
                case "TYPE_RENAME" -> { if (c.length >= 3) exact.put(c[1], c[2]); }
                case "TYPE_PREFIX_RENAME" -> { if (c.length >= 3) prefix.put(c[1], c[2]); }
                case "REMOVED" -> removed.add(c[1]);
                case "RENAME_METHOD", "METHOD_TO_STATIC" -> {
                    if (c.length >= 4) redirected.add(c[1] + "	" + c[2] + "	" + c[3]);
                }
                default -> { }
            }
        }
        return new Rules(exact, prefix, removed, redirected);
    }

    /**
     * Abstract classes and interfaces in a jar.
     *
     * Reads the access flags with ASM rather than by hand: they sit past the constant pool, so
     * there is no fixed offset to seek to and the pool has to be parsed either way.
     */
    private static Set<String> abstractClassesIn(Path jar) throws IOException {
        Set<String> out = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (var in = zf.getInputStream(e)) {
                    var reader = new org.objectweb.asm.ClassReader(in);
                    if ((reader.getAccess() & org.objectweb.asm.Opcodes.ACC_ABSTRACT) != 0) {
                        out.add(reader.getClassName());
                    }
                } catch (Exception ignored) {
                    // Unparseable class: it cannot be a useful rename target either way.
                }
            }
        }
        return out;
    }

    private static Set<String> classesIn(Path jar) throws IOException {
        Set<String> out = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.endsWith(".class")) out.add(n.substring(0, n.length() - 6));
            }
        }
        return out;
    }
}
