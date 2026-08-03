import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds an SRG -> official member mapping for Minecraft 1.20.1.
 *
 * Forge 1.20.1 runs on SRG member names while NeoForge 1.21.1 runs on official Mojang names,
 * so every vanilla member looks different between the two sides for reasons that have nothing
 * to do with the API changing. Roughly three quarters of the mined rule surface is unusable
 * until the source side is normalised. This produces the table that does it.
 *
 * Note what Forge 1.20.1 bytecode actually contains: *official* class names with *SRG* member
 * names, e.g. net/minecraft/world/item/ItemStack#m_41720_. Class names therefore need no
 * translation; only members do. And because SRG member names are globally unique by
 * construction, a flat name -> name table is sufficient — no per-class keying required.
 *
 * Composition, since no published mapping goes directly from SRG to official:
 *
 *     Mojang mappings (ProGuard)   official -> obfuscated
 *     MCPConfig joined.tsrg        obfuscated -> SRG
 *     joined on the obfuscated middle, inverted   ==>   SRG -> official
 *
 * Run:
 *   java tools/SrgToOfficial.java <mojang-client.txt> <joined.tsrg> [out.tsv]
 */
public class SrgToOfficial {

    /** One class's obfuscated identity plus the official names of its members. */
    private static final class ObfClass {
        /** obfuscated member name + descriptor -> official name. Fields use a null descriptor. */
        final Map<String, String> members = new HashMap<>();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java tools/SrgToOfficial.java <mojang-client.txt> <joined.tsrg> [out.tsv]");
            System.exit(2);
        }
        Path mojangFile = Paths.get(args[0]);
        Path tsrgFile   = Paths.get(args[1]);
        Path out        = Paths.get(args.length > 2 ? args[2] : "mappings/srg2official.tsv");
        Files.createDirectories(out.toAbsolutePath().getParent());

        System.out.println("Parsing Mojang mappings (official -> obfuscated) ...");
        Map<String, String> officialToObfClass = new HashMap<>();
        Map<String, ObfClass> byObfClass = new HashMap<>();
        parseMojang(mojangFile, officialToObfClass, byObfClass);
        System.out.printf("  %d classes%n", officialToObfClass.size());

        System.out.println("Joining against joined.tsrg (obfuscated -> SRG) ...");
        Map<String, String> srgToOfficial = new TreeMap<>();
        int unmatched = joinTsrg(tsrgFile, byObfClass, srgToOfficial);
        System.out.printf("  %d SRG members mapped, %d unmatched%n", srgToOfficial.size(), unmatched);

        StringBuilder sb = new StringBuilder("srg\tofficial\n");
        srgToOfficial.forEach((srg, off) -> sb.append(srg).append('\t').append(off).append('\n'));
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);

        System.out.printf("%nWrote %s%n", out.toAbsolutePath());
        System.out.println("\nSpot checks:");
        for (String probe : List.of("m_41720_", "m_61124_", "f_279569_", "m_237115_")) {
            System.out.printf("  %-12s -> %s%n", probe, srgToOfficial.getOrDefault(probe, "(unmapped)"));
        }
    }

    // ---- Mojang ProGuard mappings ------------------------------------------------------

    /**
     * ProGuard layout, official on the left and obfuscated on the right:
     *
     *   net.minecraft.world.item.ItemStack -> dcv:
     *       int count -> b
     *       12:34:net.minecraft.world.item.Item getItem() -> c
     *
     * Method lines may carry a leading {@code start:end:} line-number range.
     */
    private static void parseMojang(Path file, Map<String, String> officialToObfClass,
                                    Map<String, ObfClass> byObfClass) throws IOException {
        // Two passes: descriptors reference other classes, so the full class table has to exist
        // before any descriptor can be translated into obfuscated form.
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(" ")) continue;
            int arrow = line.indexOf(" -> ");
            if (arrow < 0) continue;
            String official = line.substring(0, arrow).trim();
            String obf = line.substring(arrow + 4).trim();
            if (obf.endsWith(":")) obf = obf.substring(0, obf.length() - 1);
            officialToObfClass.put(official, obf);
        }

        String currentObf = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (!line.startsWith(" ")) {
                int arrow = line.indexOf(" -> ");
                if (arrow < 0) { currentObf = null; continue; }
                String obf = line.substring(arrow + 4).trim();
                if (obf.endsWith(":")) obf = obf.substring(0, obf.length() - 1);
                // TSRG writes class names with slashes; ProGuard writes them with dots. For
                // fully obfuscated classes ("dcv") there is no package so the two forms
                // coincide, but classes Mojang leaves unobfuscated (MinecraftServer and
                // friends) keep their package and silently fail to join unless normalised.
                currentObf = obf.replace('.', '/');
                byObfClass.computeIfAbsent(currentObf, k -> new ObfClass());
                continue;
            }
            if (currentObf == null) continue;

            String body = line.trim();
            int arrow = body.indexOf(" -> ");
            if (arrow < 0) continue;
            String left = body.substring(0, arrow);
            String obfName = body.substring(arrow + 4).trim();

            // Strip any "start:end:" line-number prefix.
            int lastColon = left.lastIndexOf(':');
            if (lastColon >= 0) left = left.substring(lastColon + 1);

            int space = left.indexOf(' ');
            if (space < 0) continue;
            String type = left.substring(0, space);
            String rest = left.substring(space + 1);

            ObfClass oc = byObfClass.get(currentObf);
            int paren = rest.indexOf('(');
            if (paren < 0) {
                // Field: descriptor is not needed, names are unique within a class.
                oc.members.put(obfName + "|", rest);
            } else {
                String officialName = rest.substring(0, paren);
                String params = rest.substring(paren + 1, rest.lastIndexOf(')'));
                String desc = buildObfDescriptor(params, type, officialToObfClass);
                oc.members.put(obfName + "|" + desc, officialName);
            }
        }
    }

    /** Turns ProGuard's source-level parameter and return types into an obfuscated descriptor. */
    private static String buildObfDescriptor(String params, String returnType,
                                             Map<String, String> officialToObfClass) {
        StringBuilder sb = new StringBuilder("(");
        if (!params.isBlank()) {
            for (String p : params.split(",")) sb.append(typeToObfDescriptor(p.trim(), officialToObfClass));
        }
        sb.append(')').append(typeToObfDescriptor(returnType.trim(), officialToObfClass));
        return sb.toString();
    }

    private static String typeToObfDescriptor(String type, Map<String, String> officialToObfClass) {
        int arrayDepth = 0;
        while (type.endsWith("[]")) { arrayDepth++; type = type.substring(0, type.length() - 2); }

        String base = switch (type) {
            case "int"     -> "I";
            case "boolean" -> "Z";
            case "byte"    -> "B";
            case "char"    -> "C";
            case "short"   -> "S";
            case "long"    -> "J";
            case "float"   -> "F";
            case "double"  -> "D";
            case "void"    -> "V";
            default -> {
                // Unmapped types (JDK classes, libraries) keep their own name; only Minecraft
                // classes are obfuscated, and everything else is identical on both sides.
                String obf = officialToObfClass.getOrDefault(type, type);
                yield "L" + obf.replace('.', '/') + ";";
            }
        };
        return "[".repeat(arrayDepth) + base;
    }

    // ---- MCPConfig TSRG2 ---------------------------------------------------------------

    /**
     * TSRG2 layout, obfuscated on the left and SRG on the right:
     *
     *   tsrg2 obf srg id
     *   dcv net/minecraft/src/C_2089_ 2089
     *   \t a (Ljava/lang/String;)Ldcv; m_61543_ 61543
     *   \t\t static
     *
     * Class lines are unindented, members carry one tab, and metadata carries two. The SRG
     * *class* names here are irrelevant — Forge bytecode already uses official class names.
     */
    private static int joinTsrg(Path file, Map<String, ObfClass> byObfClass,
                                Map<String, String> srgToOfficial) throws IOException {
        int unmatched = 0;
        ObfClass current = null;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("tsrg2")) continue;
            if (line.startsWith("\t\t")) continue;  // static markers, parameter names

            if (!line.startsWith("\t")) {
                String[] parts = line.trim().split(" ");
                current = parts.length > 0 ? byObfClass.get(parts[0]) : null;
                continue;
            }
            if (current == null) continue;

            String[] parts = line.trim().split(" ");
            String obfName, key, srgName;
            if (parts.length >= 3 && parts[1].startsWith("(")) {
                obfName = parts[0];
                key = obfName + "|" + parts[1];
                srgName = parts[2];
            } else if (parts.length >= 2) {
                obfName = parts[0];
                key = obfName + "|";
                srgName = parts[1];
            } else {
                continue;
            }
            // Constructors and already-plain names carry no SRG identity.
            if (!srgName.startsWith("m_") && !srgName.startsWith("f_")) continue;

            String official = current.members.get(key);
            if (official != null) srgToOfficial.put(srgName, official);
            else unmatched++;
        }
        return unmatched;
    }
}
