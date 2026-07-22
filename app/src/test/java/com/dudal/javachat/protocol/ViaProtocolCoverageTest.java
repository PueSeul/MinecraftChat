package com.dudal.javachat.protocol;

import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ViaProtocolCoverageTest {
    @BeforeClass
    public static void loadViaVersion() {
        if (!Via.isLoaded()) {
            File folder = new File("build/test-viaversion");
            assertTrue(folder.exists() || folder.mkdirs());
            ViaManagerImpl.initAndLoad(
                    new TestPlatform(folder),
                    new NoopInjector(),
                    new ViaCommandHandler(false),
                    new ViaTranslationRuntime.AndroidPlatformLoader());
        } else {
            new ViaTranslationRuntime.AndroidPlatformLoader().load();
        }
    }

    @Test
    public void everyListedVersionHasACompleteTranslationPathFrom26_2() {
        ProtocolVersion nativeVersion = ProtocolVersion.getProtocol(776);
        assertTrue(nativeVersion.isKnown());

        for (ProtocolSpec spec : ProtocolRegistry.supportedVersions()) {
            ProtocolVersion target = ProtocolVersion.getProtocol(
                    spec.getTranslationProtocolNumber());
            assertTrue(spec.getDisplayName() + " must be registered", target.isKnown());
            if (spec.getProtocolNumber() == 776) {
                continue;
            }
            List<ProtocolPathEntry> path = Via.getManager().getProtocolManager()
                    .getProtocolPath(nativeVersion, target);
            assertNotNull(spec.getDisplayName() + " translation path", path);
            assertFalse(spec.getDisplayName() + " translation path", path.isEmpty());
            for (ProtocolPathEntry entry : path) {
                Via.getManager().getProtocolManager()
                        .completeMappingDataLoading(entry.protocol().getClass());
            }
        }
    }

    static final class TestPlatform extends UserConnectionViaVersionPlatform {
        TestPlatform(File dataFolder) {
            super(dataFolder);
        }

        @Override
        public Logger createLogger(String name) {
            return Logger.getLogger(name);
        }

        @Override
        public String getPlatformName() {
            return "Minecraft Chat Tests";
        }

        @Override
        public String getPlatformVersion() {
            return "1.1";
        }
    }
}
