package io.papermc.paper;

import java.util.List;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        SharedConstants.tryDetectVersion();

        getStartupVersionMessages().forEach(LOGGER::info);

        // Paper start - XMine - --dump-plugin-libraries
        // The dump runs the plugin discovery phase and nothing else, so it forks off here instead of inside
        // net.minecraft.server.Main: that way the server main is never entered at all -- no pid file, no crash
        // report preload, no Bootstrap.bootStrap() (which is what enters the plugin bootstrappers), no world, no
        // network. See PluginLibraryDumper for where the "plugins are not started" line runs exactly.
        if (options.has(io.papermc.paper.plugin.util.PluginLibraryDumper.OPTION)) {
            try {
                io.papermc.paper.plugin.util.PluginLibraryDumper.dump(options);
            } catch (final Exception e) {
                throw new RuntimeException("Failed to dump plugin libraries", e);
            }
            return;
        }
        // Paper end - XMine - --dump-plugin-libraries

        Main.main(options);
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            )
        );
    }
}
