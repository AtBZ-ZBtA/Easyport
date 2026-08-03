package translationlayer.spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Phase 0 spike: proves a jar sitting in the mods folder can inject additional mod files
 * into the same launch.
 *
 * The mechanism is ModDirTransformerDiscoverer, which walks the mods folder before mod
 * discovery and promotes any jar declaring one of TransformerDiscovererConstants.SERVICES
 * onto the SERVICE module layer. IModFileCandidateLocator is in that set, so declaring this
 * class in META-INF/services is enough to get loaded early enough to matter — no launcher
 * arguments, and no restart.
 *
 * This spike only discovers and forwards jars. Translation gets wired in later; the point
 * here is to establish that the injection point exists and fires.
 */
public class TranslationLocator implements IModFileCandidateLocator {

    /** Folder users drop foreign-version mods into, relative to the game directory. */
    private static final String INBOX = "mods-from-other-version";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        Path inbox = FMLPaths.GAMEDIR.get().resolve(INBOX);

        if (!Files.isDirectory(inbox)) {
            ILaunchContext.LOGGER.info("[translation-layer] no {} folder; nothing to do", INBOX);
            return;
        }

        List<Path> jars = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(inbox, 1)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .forEach(jars::add);
        } catch (IOException e) {
            ILaunchContext.LOGGER.error("[translation-layer] could not read {}", inbox, e);
            return;
        }

        ILaunchContext.LOGGER.info("[translation-layer] found {} jar(s) in {}", jars.size(), INBOX);

        for (Path jar : jars) {
            // context.isLocated guards against handing the pipeline something another locator
            // already claimed, which would surface as a duplicate-mod error.
            if (context.isLocated(jar)) {
                ILaunchContext.LOGGER.debug("[translation-layer] already located, skipping {}", jar.getFileName());
                continue;
            }
            ILaunchContext.LOGGER.info("[translation-layer] injecting {}", jar.getFileName());
            pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        }
    }

    @Override
    public String toString() {
        return "translation-layer";
    }
}
