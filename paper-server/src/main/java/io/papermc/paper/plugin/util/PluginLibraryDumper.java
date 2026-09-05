package io.papermc.paper.plugin.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.entrypoint.Entrypoint;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.impl.JarLibrary;
import io.papermc.paper.plugin.loader.library.PaperLibraryStore;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import io.papermc.paper.plugin.provider.PluginProvider;
import io.papermc.paper.plugin.provider.type.paper.PaperPluginParent;
import io.papermc.paper.plugin.provider.type.spigot.SpigotPluginProvider;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import joptsimple.OptionSet;
import org.bukkit.plugin.PluginDescriptionFile;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

/**
 * Backs the {@code --dump-plugin-libraries} flag: prints every library that the discovered plugins would make the
 * server fetch, as JSON, and does not start the server.
 * <p>
 * Motivation: the XMine node image has to be self-contained, so the libraries must be baked into it at build time.
 * Reconstructing the list from the outside (parsing {@code plugin.yml}, reading bytecode) is not reliable, because
 * Paper-style plugins declare their libraries <em>in code</em>. Since the core already owns the whole declaration
 * path, the core is what prints the list.
 *
 * <h2>Where the "plugins are not started" line runs</h2>
 * The flag runs exactly one phase of startup — {@link io.papermc.paper.plugin.PluginInitializerManager#load(OptionSet)},
 * which walks the plugin directories and <em>builds providers</em> — and then returns. That gives:
 * <ul>
 *     <li><b>Runs:</b> reading {@code plugin.yml} / {@code paper-plugin.yml}, and, for plugins that declare a
 *     {@code loader:} (or {@code paper-plugin-loader:}), instantiating that {@link io.papermc.paper.plugin.loader.PluginLoader}
 *     class and calling {@link io.papermc.paper.plugin.loader.PluginLoader#classloader(io.papermc.paper.plugin.loader.PluginClasspathBuilder)}.
 *     This is unavoidable: the Paper-style declaration <em>is</em> that method call, there is nothing to read
 *     statically. It is third-party code, executed with only the plugin jar on its classpath and with no server
 *     around it. Note that {@code PluginClasspathBuilder#addLibrary} only <em>collects</em>
 *     {@link ClassPathLibrary} instances; it resolves nothing.</li>
 *     <li><b>Does not run:</b> {@link io.papermc.paper.plugin.bootstrap.PluginBootstrap#bootstrap} (entered from
 *     {@code Bootstrap.bootStrap()} via {@code LaunchEntryPointHandler.enterBootstrappers()}), the construction of
 *     the {@link org.bukkit.plugin.java.JavaPlugin} instances, {@code onLoad} and {@code onEnable} (entered from
 *     {@code CraftServer} via {@code LaunchEntryPointHandler#enter(Entrypoint.PLUGIN)}). No world, no network,
 *     no ports, no {@code Bukkit} singleton.</li>
 * </ul>
 * The boundary is held in {@link io.papermc.paper.PaperBootstrap#boot(OptionSet)}: the flag returns from there
 * instead of calling {@code net.minecraft.server.Main}, so every later entrypoint — all of them reached from inside
 * that call — is simply never reached. Anyone moving this into the server main, past {@code Bootstrap.bootStrap()},
 * would make the flag start running plugin bootstrappers without saying so; that is the one thing to watch here.
 *
 * <h2>Why nothing is downloaded</h2>
 * Resolution (network IO) happens in {@link ClassPathLibrary#register(io.papermc.paper.plugin.loader.library.LibraryStore)}
 * — for Maven, in {@link MavenLibraryResolver#register}. While a dumper is {@link #active() active},
 * {@code PaperClasspathBuilder#buildLibraryPaths} hands the collected libraries here instead of registering them,
 * so {@code register} is never called. The Spigot-style {@code libraries:} list is read straight off
 * {@link PluginDescriptionFile#getLibraries()} and is only resolved in {@code SpigotPluginProvider#createInstance},
 * which never runs.
 *
 * <h2>What this list is and is not</h2>
 * By default it is the set of <em>declared</em> coordinates, i.e. the roots the core would hand to Aether. It is not
 * the transitive closure, and the closure cannot be derived from it offline: it lives in poms on the remote
 * repositories, so working it out is resolution by definition. Concretely, BetterModel declares four roots and none
 * of them is {@code org.jetbrains.kotlin:kotlin-stdlib}, which it nevertheless needs, because that comes in through
 * one of the four.
 * <p>
 * {@code --dump-plugin-libraries-transitive} opts into that resolution: same Aether call the server would make, so
 * the report gains a {@code resolved} array per plugin with the full closure and its file paths, and the
 * {@code libraries} folder ends up populated exactly as a first start would leave it. It needs the network. Without
 * it, nothing is fetched at all.
 */
@ApiStatus.Internal
public final class PluginLibraryDumper {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /**
     * Name of the option declared in {@code org.bukkit.craftbukkit.Main}.
     */
    public static final String OPTION = "dump-plugin-libraries";

    /**
     * Name of the opt-in option that turns the listing into a real resolution.
     */
    public static final String OPTION_TRANSITIVE = "dump-plugin-libraries-transitive";

    private static volatile PluginLibraryDumper active;

    private final Map<Path, PluginEntry> plugins = new LinkedHashMap<>();
    private final boolean transitive;

    private PluginLibraryDumper(final boolean transitive) {
        this.transitive = transitive;
    }

    /**
     * @return the dumper collecting declarations, or {@code null} when the server is starting normally
     */
    public static PluginLibraryDumper active() {
        return active;
    }

    /**
     * Runs the flag end to end: arms the collector, discovers the plugins exactly like a normal start would, and
     * writes the JSON report. The caller must return afterwards instead of booting the server.
     *
     * @param options the parsed command line -- used for plugin discovery ({@code --plugins}, {@code --add-plugin},
     *                {@code --add-plugin-dir}) and for the output location
     */
    public static void dump(final OptionSet options) throws Exception {
        final PluginLibraryDumper dumper = new PluginLibraryDumper(options.has(OPTION_TRANSITIVE));
        active = dumper;
        try {
            // The very call a normal start makes, so the flag sees exactly the plugins a start with the same
            // arguments would see. It reads the plugin configurations and builds the providers; while a dumper is
            // armed, the libraries a PluginLoader declares are collected here instead of being resolved.
            io.papermc.paper.plugin.PluginInitializerManager.load(options);
        } finally {
            active = null;
        }

        dumper.collectSpigotDeclarations();
        dumper.write(options);
    }

    private void write(final OptionSet options) throws IOException {
        final Object target = options.valueOf(OPTION);
        final String json = this.toJson();
        if (target == null) {
            System.out.println(json);
            System.out.flush();
        } else {
            final Path path = ((java.io.File) target).toPath().toAbsolutePath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (final Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(json);
                writer.write('\n');
            }
            LOGGER.info("Wrote plugin library dump for {} plugin(s) to {}", this.plugins.size(), path);
        }
    }

    /**
     * Called by {@code PaperClasspathBuilder} instead of resolving, for both Paper plugins and Spigot plugins that
     * declare a {@code paper-plugin-loader}.
     *
     * @param meta      configuration of the plugin whose loader declared these
     * @param source    the plugin jar
     * @param libraries what the plugin's {@link io.papermc.paper.plugin.loader.PluginLoader} put on the builder
     */
    public void recordPaperLibraries(final PluginMeta meta, final Path source, final List<ClassPathLibrary> libraries) {
        final PluginEntry entry = this.entry(meta, source);
        for (final ClassPathLibrary library : libraries) {
            if (library instanceof final MavenLibraryResolver resolver) {
                final List<String> repositories = new ArrayList<>();
                for (final RemoteRepository repository : resolver.declaredRepositories()) {
                    repositories.add(repository.getUrl());
                }
                for (final Dependency dependency : resolver.declaredDependencies()) {
                    entry.libraries.add(LibraryEntry.maven("paper", dependency.getArtifact(), repositories));
                }
                this.resolveInto(entry, resolver);
            } else if (library instanceof final JarLibrary jar) {
                entry.libraries.add(LibraryEntry.jar("paper", jarLibraryPath(jar)));
            } else {
                // A plugin may implement ClassPathLibrary itself; the only way to learn what such an implementation
                // contributes is to run register(), which is exactly the resolution the plain flag refuses to
                // perform. Report it instead of pretending the plugin declared nothing -- and, when the caller asked
                // for resolution anyway, run it and report the files it produced.
                entry.opaque.add(library.getClass().getName());
                if (this.transitive) {
                    final PaperLibraryStore store = new PaperLibraryStore();
                    library.register(store);
                    for (final Path path : store.getPaths()) {
                        entry.resolved.add(new ResolvedEntry(null, path.toAbsolutePath().toString()));
                    }
                }
            }
        }
    }

    /**
     * Reads the Spigot-style {@code libraries:} lists off the providers that {@code PluginInitializerManager#load}
     * registered. Pure YAML, no plugin code involved.
     */
    private void collectSpigotDeclarations() {
        final var storage = LaunchEntryPointHandler.INSTANCE.get(Entrypoint.PLUGIN);
        if (storage == null) {
            return;
        }

        for (final PluginProvider<?> provider : storage.getRegisteredProviders()) {
            final PluginMeta meta = provider.getMeta();
            final PluginEntry entry = this.entry(meta, provider.getSource());
            entry.paperPlugin = provider instanceof PaperPluginParent.PaperServerPluginProvider;

            if (provider instanceof SpigotPluginProvider && meta instanceof final PluginDescriptionFile description) {
                if (description.getLibraries().isEmpty()) {
                    continue;
                }

                // Mirrors org.bukkit.plugin.java.LibraryLoader: raw coordinates against the single central mirror.
                final MavenLibraryResolver resolver = this.transitive ? new MavenLibraryResolver() : null;
                if (resolver != null) {
                    resolver.addRepository(new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
                }
                for (final String library : description.getLibraries()) {
                    final Artifact artifact = new DefaultArtifact(library);
                    entry.libraries.add(LibraryEntry.maven("spigot", artifact, List.of(MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)));
                    if (resolver != null) {
                        resolver.addDependency(new Dependency(artifact, null));
                    }
                }
                if (resolver != null) {
                    this.resolveInto(entry, resolver);
                }
            }
        }
    }

    /**
     * Only under {@code --dump-plugin-libraries-transitive}: performs the resolution the server would perform, so
     * that the transitive closure -- which lives in remote poms and cannot be computed offline -- ends up in the
     * report as well.
     */
    private void resolveInto(final PluginEntry entry, final MavenLibraryResolver resolver) {
        if (!this.transitive) {
            return;
        }

        for (final Artifact artifact : resolver.resolveArtifacts()) {
            entry.resolved.add(new ResolvedEntry(
                artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion(),
                artifact.getFile() == null ? null : artifact.getFile().toPath().toAbsolutePath().toString()
            ));
        }
    }

    private PluginEntry entry(final PluginMeta meta, final Path source) {
        return this.plugins.computeIfAbsent(source.toAbsolutePath(), path -> new PluginEntry(meta.getName(), meta.getVersion(), path));
    }

    private static String jarLibraryPath(final JarLibrary library) {
        // JarLibrary keeps its path private and has no accessor; it is a leaf value class, so reflection is the
        // cheapest way to report it without widening the public api for a rare case.
        try {
            final Field field = JarLibrary.class.getDeclaredField("path");
            field.setAccessible(true);
            return Objects.toString(field.get(library), null);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private String toJson() {
        final JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("generatedBy", "--" + OPTION);
        root.addProperty("transitive", this.transitive);

        final JsonArray plugins = new JsonArray();
        for (final PluginEntry plugin : this.plugins.values()) {
            final JsonObject object = new JsonObject();
            object.addProperty("plugin", plugin.name);
            object.addProperty("pluginVersion", plugin.version);
            object.addProperty("source", plugin.source.toString());
            object.addProperty("type", plugin.paperPlugin ? "paper-plugin" : "spigot-plugin");

            final JsonArray libraries = new JsonArray();
            for (final LibraryEntry library : plugin.libraries) {
                libraries.add(library.toJson());
            }
            object.add("libraries", libraries);

            if (this.transitive) {
                final JsonArray resolved = new JsonArray();
                for (final ResolvedEntry item : plugin.resolved) {
                    final JsonObject object2 = new JsonObject();
                    if (item.coordinate() != null) {
                        object2.addProperty("coordinate", item.coordinate());
                    }
                    if (item.path() != null) {
                        object2.addProperty("path", item.path());
                    }
                    resolved.add(object2);
                }
                object.add("resolved", resolved);
            }

            if (!plugin.opaque.isEmpty()) {
                final JsonArray opaque = new JsonArray();
                plugin.opaque.forEach(opaque::add);
                object.add("opaqueLibraryProviders", opaque);
            }

            plugins.add(object);
        }
        root.add("plugins", plugins);

        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    private static final class PluginEntry {

        private final String name;
        private final String version;
        private final Path source;
        private final List<LibraryEntry> libraries = new ArrayList<>();
        private final List<String> opaque = new ArrayList<>();
        private final List<ResolvedEntry> resolved = new ArrayList<>();
        private boolean paperPlugin;

        private PluginEntry(final String name, final String version, final Path source) {
            this.name = name;
            this.version = version;
            this.source = source;
        }
    }

    private record ResolvedEntry(String coordinate, String path) {
    }

    private record LibraryEntry(String mechanism, String kind, String coordinate, String group, String artifact,
                                String version, String classifier, String extension, List<String> repositories,
                                String path) {

        private static LibraryEntry maven(final String mechanism, final Artifact artifact, final List<String> repositories) {
            return new LibraryEntry(
                mechanism,
                "maven",
                artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion(),
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                artifact.getClassifier() == null || artifact.getClassifier().isEmpty() ? null : artifact.getClassifier(),
                artifact.getExtension(),
                List.copyOf(repositories),
                null
            );
        }

        private static LibraryEntry jar(final String mechanism, final String path) {
            return new LibraryEntry(mechanism, "jar", null, null, null, null, null, null, List.of(), path);
        }

        private JsonObject toJson() {
            final JsonObject object = new JsonObject();
            object.addProperty("mechanism", this.mechanism);
            object.addProperty("kind", this.kind);
            if (this.coordinate != null) {
                object.addProperty("coordinate", this.coordinate);
                object.addProperty("group", this.group);
                object.addProperty("artifact", this.artifact);
                object.addProperty("version", this.version);
                if (this.classifier != null) {
                    object.addProperty("classifier", this.classifier);
                }
                if (this.extension != null && !"jar".equals(this.extension)) {
                    object.addProperty("extension", this.extension);
                }
                final JsonArray repositories = new JsonArray();
                this.repositories.forEach(repositories::add);
                object.add("repositories", repositories);
            }
            if (this.path != null) {
                object.addProperty("path", this.path);
            }
            return object;
        }
    }

}
