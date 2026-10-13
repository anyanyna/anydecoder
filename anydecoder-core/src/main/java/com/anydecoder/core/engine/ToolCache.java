package com.anydecoder.core.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Finds the jar for each engine, in order of how much the user had to do to provide it:
 *
 * <ol>
 *   <li>an explicit path the caller configured;</li>
 *   <li>an earlier run's copy in the tools directory;</li>
 *   <li>the copy bundled inside anydecoder's own artifact, extracted on first use;</li>
 *   <li>a download from Maven Central.</li>
 * </ol>
 *
 * <p>Bundling means a normal install never reaches step four, and pinning the version in the
 * artifact means every user of a given anydecoder release decompiles with the same engine --
 * which matters, because engine behaviour is one of the things the gate's verdicts depend on.
 *
 * <p><b>Where the tools directory is.</b> The caller decides. The command line puts it beside the
 * run's scratch space rather than in the user's home directory, so a project kept on another
 * drive never causes anything to accumulate on the system drive. Nothing here assumes a home
 * directory exists or is writable.
 */
public final class ToolCache {

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String BUNDLE_ROOT = "/META-INF/anydecoder/tools/";

    private final Path toolsDirectory;
    private final Optional<Path> explicitDirectory;

    public ToolCache(Path toolsDirectory, Optional<Path> explicitDirectory) {
        this.toolsDirectory = toolsDirectory;
        this.explicitDirectory = explicitDirectory;
    }

    public static ToolCache forCurrentUser() {
        return new ToolCache(Path.of(System.getProperty("user.home"), ".anydecoder", "tools"),
                Optional.empty());
    }

    /**
     * Resolve {@code artifact} to a readable jar, fetching it if necessary.
     *
     * @throws DecompilerException when every source failed
     */
    public Path resolve(ToolArtifact artifact) throws DecompilerException {
        if (explicitDirectory.isPresent()) {
            Path candidate = explicitDirectory.get().resolve(artifact.fileName());
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }

        Path cached = artifact.pathIn(toolsDirectory);
        if (Files.isReadable(cached)) {
            return cached;
        }

        if (extractBundled(artifact, cached)) {
            return cached;
        }

        return download(artifact, cached);
    }

    private boolean extractBundled(ToolArtifact artifact, Path destination) throws DecompilerException {
        try (InputStream in = ToolCache.class.getResourceAsStream(BUNDLE_ROOT + artifact.fileName())) {
            if (in == null) {
                return false;
            }
            Files.createDirectories(destination.getParent());
            Path temp = destination.resolveSibling(destination.getFileName() + ".part");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            throw new DecompilerException("could not extract bundled " + artifact.fileName(), e);
        }
    }

    private Path download(ToolArtifact artifact, Path destination) throws DecompilerException {
        String url = MAVEN_CENTRAL + artifact.mavenPath();
        try {
            Files.createDirectories(destination.getParent());
            Path temp = destination.resolveSibling(destination.getFileName() + ".part");
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(temp);
                throw new DecompilerException(
                        "could not download " + artifact.id() + " from " + url
                                + ": HTTP " + response.statusCode());
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (IOException e) {
            throw new DecompilerException("could not download " + artifact.id() + " from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecompilerException("interrupted while downloading " + artifact.id(), e);
        }
    }
}
