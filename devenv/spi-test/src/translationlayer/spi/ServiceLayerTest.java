package translationlayer.spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.neoforged.fml.loading.TransformerDiscovererConstants;

/**
 * Asks NeoForge's own predicate whether our jar qualifies for the SERVICE module layer.
 *
 * This is the decisive Phase 0 check. TransformerDiscovererConstants.shouldLoadInServiceLayer
 * is the exact method ModDirTransformerDiscoverer calls on every jar it finds in the mods
 * folder; running it directly against our artifact tests the real production code path
 * without launching the game.
 *
 * Exits 0 if the jar would be promoted to the service layer, 1 otherwise.
 */
public class ServiceLayerTest {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: ServiceLayerTest <jar> [<jar> ...]");
            System.exit(2);
        }

        boolean allPassed = true;
        System.out.println("Querying NeoForge's TransformerDiscovererConstants.shouldLoadInServiceLayer\n");
        System.out.println("Qualifying service types:");
        TransformerDiscovererConstants.SERVICES.stream().sorted()
                .forEach(s -> System.out.println("    " + s));
        System.out.println();

        for (String arg : args) {
            Path jar = Paths.get(arg).toAbsolutePath();
            if (!Files.isRegularFile(jar)) {
                System.out.printf("  MISSING  %s%n", jar);
                allPassed = false;
                continue;
            }
            boolean promoted = TransformerDiscovererConstants.shouldLoadInServiceLayer(jar);
            System.out.printf("  %-8s %s%n", promoted ? "PROMOTED" : "IGNORED", jar.getFileName());
            // A plain content mod *should* be ignored here — it is not a service provider, and
            // a result of "everything qualifies" would mean the test proves nothing.
            allPassed &= promoted;
        }

        System.out.println();
        System.out.println(allPassed
                ? "RESULT: jar(s) load on the SERVICE layer before mod discovery."
                : "RESULT: at least one jar would NOT be promoted.");
        System.exit(allPassed ? 0 : 1);
    }
}
