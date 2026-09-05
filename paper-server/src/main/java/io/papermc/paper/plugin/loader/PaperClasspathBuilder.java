package io.papermc.paper.plugin.loader;

import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.PaperLibraryStore;
import io.papermc.paper.plugin.provider.configuration.PaperPluginMeta;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

public class PaperClasspathBuilder implements PluginClasspathBuilder {

    private final List<ClassPathLibrary> libraries = new ArrayList<>();

    private final PluginProviderContext context;

    public PaperClasspathBuilder(PluginProviderContext context) {
        this.context = context;
    }

    @Override
    public @NotNull PluginProviderContext getContext() {
        return this.context;
    }

    @Override
    public @NotNull PluginClasspathBuilder addLibrary(@NotNull ClassPathLibrary classPathLibrary) {
        this.libraries.add(classPathLibrary);
        return this;
    }

    public PaperPluginClassLoader buildClassLoader(Logger logger, Path source, JarFile jarFile, PaperPluginMeta configuration) {
        List<Path> paths = this.buildLibraryPaths();
        URL[] urls = new URL[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            try {
                urls[i] = path.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new AssertionError(e);
            }
        }

        try {
            final URLClassLoader libraryLoader = new URLClassLoader(urls, this.getClass().getClassLoader());
            return new PaperPluginClassLoader(logger, source, jarFile, configuration, this.getClass().getClassLoader(), libraryLoader);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    public List<Path> buildLibraryPaths() {
        // Paper start - XMine - --dump-plugin-libraries
        // ClassPathLibrary#register is where resolution (and therefore downloading) happens. While the dump flag is
        // running we only want the declarations, so the libraries are handed over unregistered and the plugin gets an
        // empty library classpath -- which is harmless, because the flag never instantiates the plugin.
        final io.papermc.paper.plugin.util.PluginLibraryDumper dumper = io.papermc.paper.plugin.util.PluginLibraryDumper.active();
        if (dumper != null) {
            dumper.recordPaperLibraries(this.context.getConfiguration(), this.context.getPluginSource(), this.libraries);
            return List.of();
        }
        // Paper end - XMine - --dump-plugin-libraries

        PaperLibraryStore paperLibraryStore = new PaperLibraryStore();
        for (ClassPathLibrary library : this.libraries) {
            library.register(paperLibraryStore);
        }

        return paperLibraryStore.getPaths();
    }
}
