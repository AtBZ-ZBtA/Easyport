package easyport.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the CLI translator from inside the game.
 *
 * The translation itself is {@code easyport.tools.Translate}, unchanged and unforked -- the same
 * class the command line runs. That is the whole point of this phase: an in-game path that
 * reimplemented any part of the translation would drift from the one every measurement in this
 * project was taken against, and the drift would show up as mods that behave differently
 * depending on how they were translated.
 *
 * What this class supplies is the three things a command line gives it for free: where the rules
 * and mappings are, where the target platform is, and which jars are worth translating again.
 */
final class Translator {

    private static final Logger LOG = LoggerFactory.getLogger("easyport");

    /** Working directory for everything unpacked out of the service jar. */
    private static final String WORK = ".easyport";

    private final Path work;
    private final Path rules;
    private final Path mappings;
    private final Path forgeCompat;
    private final String[] platformJars;

    /**
     * Identifies the build of Easyport doing the translating, and is part of every cache key.
     *
     * Without it, upgrading Easyport leaves every previously translated mod in place: the source
     * jar has not changed, so nothing looks stale, and the fixes in the new version reach nothing
     * the user already ported. That is a silent failure of exactly the kind this project keeps
     * finding, so the tool's own version participates in its cache key.
     *
     * It is a file packaged into the jar rather than the jar's own modification time, because the
     * jar cannot reliably find itself. FML loads it through the secure jar handler, so its code
     * source is a {@code union:} URI that {@code Paths.get} refuses -- which silently produced a
     * stamp of zero, and a rebuilt Easyport that retranslated nothing.
     */
    private final String buildId;

    Translator(Path gameDir) throws IOException {
        this.work = gameDir.resolve(WORK);
        Files.createDirectories(work);
        this.rules = unpack("forward.rules.tsv");
        this.mappings = unpack("srg2official.tsv");
        this.forgeCompat = unpack("forge-compat.jar");
        this.buildId = Files.readString(unpack("build-id.txt")).trim();
        // forge-compat is part of what a translated mod runs against, so it belongs in the target
        // index as well as in the mods folder. A mod that mixes into Forge's own classes --
        // supermartijn642corelib patches GameData -- has those mixins judged against whatever
        // forge-compat supplies, and without it in the index they are judged against nothing.
        this.platformJars = withForgeCompat(findPlatformJars(), forgeCompat);
        if (platformJars.length == 0) {
            // Not fatal, and not silent either. Without the platform index the transformer cannot
            // read 1.21's own descriptors, which is what the Holder, argument-arity, abstract-stub
            // and mixin passes all work from -- so translation still runs and produces markedly
            // less. Saying so here is the difference between a known limitation and a mystery.
            LOG.warn("[easyport] no platform jars found on the module or class path; "
                   + "translating without a target index, which disables the passes that read "
                   + "1.21's own signatures");
        } else {
            LOG.info("[easyport] target index from {} platform jar(s)", platformJars.length);
            for (String j : platformJars) LOG.debug("[easyport]   {}", j);
        }
    }

    Path forgeCompatJar() {
        return forgeCompat;
    }

    /**
     * Translates one jar into the mods folder, or returns the existing output if it is current.
     *
     * Returns null when the jar needs no translation -- a NeoForge mod dropped into the inbox by
     * mistake is not an error, it is just already the right shape, and translating it would
     * corrupt it.
     */
    Path translate(Path source, Path modsDir) throws Exception {
        if (!isForgeMod(source)) {
            LOG.info("[easyport] {} is not a Forge 1.20.1 mod; leaving it alone",
                    source.getFileName());
            return null;
        }

        Path out = modsDir.resolve(outputName(source));
        Path stampFile = work.resolve(outputName(source) + ".stamp");
        String stamp = stampOf(source);
        if (Files.isRegularFile(out) && Files.isRegularFile(stampFile)
                && stamp.equals(Files.readString(stampFile).trim())) {
            LOG.info("[easyport] {} is up to date", out.getFileName());
            return out;
        }

        LOG.info("[easyport] translating {} -> {}", source.getFileName(), out.getFileName());
        // Built in the working folder and moved into place, so a translation interrupted half way
        // through cannot leave a truncated jar in the mods folder -- which would fail the next
        // launch as a corrupt mod rather than as a missing one. It also puts the transformer's
        // own per-jar report somewhere other than next to the user's mods.
        Path staged = work.resolve(outputName(source));
        easyport.tools.Translate t = new easyport.tools.Translate();
        if (platformJars.length > 0) t.loadTargetIndex(platformJars);
        t.run(source, staged, mappings, rules);
        Files.createDirectories(modsDir);
        Files.move(staged, out, StandardCopyOption.REPLACE_EXISTING);
        // Written only after the move, so a translation that failed part way through is retried
        // next launch rather than recorded as done.
        Files.writeString(stampFile, stamp);
        return out;
    }

    /**
     * Deletes translated jars whose source has left the inbox.
     *
     * Only files carrying {@link TranslationLocator#SUFFIX} are considered, which is the only
     * claim this tool makes on the mods folder. Without this a mod removed from the inbox keeps
     * loading forever, and the user's way of uninstalling it -- deleting the file they put
     * there -- silently does nothing.
     */
    int removeOrphanedOutputs(Path modsDir, List<Path> sources) {
        if (!Files.isDirectory(modsDir)) return 0;
        Set<String> expected = new HashSet<>();
        for (Path s : sources) expected.add(outputName(s));

        int removed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*" + TranslationLocator.SUFFIX)) {
            for (Path p : ds) {
                if (expected.contains(p.getFileName().toString())) continue;
                try {
                    Files.delete(p);
                    Files.deleteIfExists(work.resolve(p.getFileName() + ".stamp"));
                    LOG.info("[easyport] removed {} -- its source is no longer in {}",
                            p.getFileName(), TranslationLocator.INBOX);
                    removed++;
                } catch (IOException e) {
                    LOG.warn("[easyport] could not remove stale {}", p.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOG.warn("[easyport] could not scan {} for stale translations", modsDir, e);
        }
        return removed;
    }

    private static String outputName(Path source) {
        String n = source.getFileName().toString();
        return n.substring(0, n.length() - 4) + TranslationLocator.SUFFIX;
    }

    /** A Forge 1.20.1 mod declares META-INF/mods.toml; NeoForge renamed the file. */
    private static boolean isForgeMod(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("META-INF/mods.toml") != null
                && z.getEntry("META-INF/neoforge.mods.toml") == null;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- what the command line supplies as arguments -----------------------------------

    private Path unpack(String name) throws IOException {
        Path out = work.resolve(name);
        try (InputStream in = Translator.class.getResourceAsStream("/easyport/data/" + name)) {
            if (in == null) throw new IOException("service jar is missing /easyport/data/" + name);
            // Rewritten every launch rather than cached. These are small, and a stale rules file
            // silently produces the previous version's translation -- the same trap the staleness
            // stamp exists to close, and not worth two mechanisms.
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    /**
     * The platform jars, taken from the paths the JVM was launched with.
     *
     * The command line is handed these explicitly. In the game there is nothing to hand them
     * over: FML has not built the game layer yet, so NeoForge's own classes are not loaded and
     * cannot be asked where they came from. What <em>is</em> already true is that the launcher
     * put minecraft and neoforge on the module path to start the JVM at all, so that is where
     * this looks.
     *
     * Matched by name rather than by content. Reading every jar on the path to find out which
     * ones hold {@code net/minecraft} would open a hundred archives to keep three, and the
     * launcher's names for these are stable.
     */
    private static String[] findPlatformJars() {
        List<String> found = new ArrayList<>();
        for (String property : new String[] { "jdk.module.path", "java.class.path" }) {
            String value = System.getProperty(property);
            if (value == null || value.isEmpty()) continue;
            for (String entry : value.split(java.io.File.pathSeparator)) {
                Path path;
                try {
                    // The module path arrives with its separators doubled, which Windows accepts
                    // and which makes the same jar look like two different ones. Normalising to a
                    // real path is what makes the deduplication below actually deduplicate.
                    path = Paths.get(entry).toRealPath();
                } catch (IOException e) {
                    continue;                       // not a file that exists; nothing to index
                }
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar")) continue;
                boolean wanted = name.startsWith("neoforge-")
                              || name.startsWith("minecraft-")
                              || name.startsWith("client-")
                              || name.startsWith("server-")
                              || name.startsWith("fmlcore")
                              || name.startsWith("javafmllanguage");
                String canonical = path.toString();
                if (wanted && !found.contains(canonical)) found.add(canonical);
            }
        }
        return found.toArray(new String[0]);
    }

    /**
     * What a translated output must match to be reused: this build of Easyport, and this exact
     * source jar. Size as well as time, because a mod redownloaded at the same version can come
     * back with a different timestamp and identical contents, and vice versa.
     */
    private String stampOf(Path source) throws IOException {
        return buildId + "\t" + Files.getLastModifiedTime(source).toMillis()
                       + "\t" + Files.size(source);
    }

    /** Only when something was found: an index of forge-compat alone is worse than none. */
    private static String[] withForgeCompat(String[] platform, Path forgeCompat) {
        if (platform.length == 0) return platform;
        List<String> all = new ArrayList<>(List.of(platform));
        all.add(forgeCompat.toString());
        return all.toArray(new String[0]);
    }

}
