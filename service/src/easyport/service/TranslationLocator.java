package easyport.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * The in-game half of Easyport: translates foreign-version mods during the launch that needs them.
 *
 * <h2>Why this can work at all</h2>
 *
 * FML's {@code ModDirTransformerDiscoverer} walks the mods folder <em>before</em> mod discovery
 * and promotes any jar declaring one of {@code TransformerDiscovererConstants.SERVICES} onto the
 * SERVICE module layer. {@code IModFileCandidateLocator} is in that set, so a jar in
 * {@code mods/} that declares this class in {@code META-INF/services} is running before the
 * loader has decided what the mods are -- which is the only window in which a jar can be
 * translated and still be loaded by the same launch. No launcher arguments, no restart, no
 * second pass. Phase 0 proved the injection point; this is the phase that puts a translator
 * behind it.
 *
 * <h2>What it does with what it finds</h2>
 *
 * Jars in {@code mods-from-other-version/} are translated into {@code mods/} under a
 * {@code -easyport.jar} name, so the folder the user manages says plainly which mods are ports
 * and which are native. They are also handed straight to the discovery pipeline, because the
 * mods folder was enumerated before this ran and a file appearing in it now is not guaranteed to
 * be noticed.
 *
 * <h2>Failure is per mod, deliberately</h2>
 *
 * Anything that goes wrong with one jar is logged and skipped. A translation layer that takes the
 * launch down with it is worse than one that leaves a mod out: the user can read a log line about
 * one mod, and cannot do anything at all with a game that will not start.
 */
public class TranslationLocator implements IModFileCandidateLocator {

    /**
     * Obtained the same way {@code TransformerDiscovererConstants} does.
     *
     * {@code ILaunchContext.LOGGER} produced no visible output when tried from this layer, which
     * makes a working locator indistinguishable from one that never ran. Prefer this.
     */
    private static final Logger LOG = LoggerFactory.getLogger("easyport");

    /** Where the user drops mods built for the other version. */
    static final String INBOX = "mods-from-other-version";

    /**
     * Suffix marking a jar this tool produced.
     *
     * It is also the ownership claim: a {@code *-easyport.jar} in the mods folder is assumed to be
     * ours and is removed when its source leaves the inbox. Nothing else in the folder is touched.
     */
    static final String SUFFIX = "-easyport.jar";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path inbox = gameDir.resolve(INBOX);
        Path mods = gameDir.resolve("mods");

        List<Path> sources = new ArrayList<>();
        if (Files.isDirectory(inbox)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox, "*.jar")) {
                for (Path p : ds) if (Files.isRegularFile(p)) sources.add(p);
            } catch (IOException e) {
                LOG.error("[easyport] cannot read {}", inbox, e);
                return;
            }
        }

        Translator translator;
        try {
            translator = new Translator(gameDir);
        } catch (Exception e) {
            LOG.error("[easyport] could not prepare the translator; no mods will be translated", e);
            return;
        }

        // Runs even with an empty inbox, so removing the last foreign mod removes its output.
        int removed = translator.removeOrphanedOutputs(mods, sources);
        if (sources.isEmpty()) {
            if (removed > 0) LOG.info("[easyport] {} folder is empty; removed {} stale translation(s)",
                    INBOX, removed);
            return;
        }

        LOG.info("[easyport] {} jar(s) in {}", sources.size(), INBOX);

        // forge-compat carries the net.minecraftforge API the translated mods are compiled
        // against. Without it every one of them fails on the first Forge class it touches, so it
        // is injected whenever anything at all was translated -- and injected first, because a
        // missing dependency is reported against the mod that needed it rather than the one that
        // is absent.
        List<Path> inject = new ArrayList<>();
        try {
            inject.add(translator.forgeCompatJar());
        } catch (Exception e) {
            LOG.error("[easyport] could not unpack forge-compat; translated mods will not link", e);
            return;
        }

        for (Path source : sources) {
            try {
                Path out = translator.translate(source, mods);
                if (out != null) inject.add(out);
            } catch (Exception e) {
                LOG.error("[easyport] failed to translate {} -- skipping it", source.getFileName(), e);
            }
        }

        for (Path jar : inject) {
            // Guards against handing the pipeline something the ordinary mods-folder locator has
            // already claimed, which surfaces as a duplicate-mod error naming our jar.
            if (context.isLocated(jar)) continue;
            LOG.info("[easyport] injecting {}", jar.getFileName());
            pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        }
    }

    @Override
    public String toString() {
        return "easyport";
    }
}
