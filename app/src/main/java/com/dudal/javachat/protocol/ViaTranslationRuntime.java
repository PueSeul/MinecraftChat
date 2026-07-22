package com.dudal.javachat.protocol;

import android.content.Context;
import android.util.Log;

import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.signature.SignableCommandArgumentsProvider;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import com.viaversion.viaversion.configuration.AbstractViaConfig;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;
import com.viaversion.viaversion.platform.ViaChannelInitializer;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import com.viaversion.viaversion.protocol.version.BaseVersionProvider;
import com.viaversion.viaversion.protocols.v1_8to1_9.provider.CompressionProvider;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.util.Pair;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.AttributeKey;

import org.geysermc.mcprotocollib.network.NetworkConstants;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.compression.CompressionConfig;
import org.geysermc.mcprotocollib.network.compression.ZlibCompression;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/** Boots and attaches ViaVersion's client-side protocol translator. */
public final class ViaTranslationRuntime {
    private static final Logger LOGGER = Logger.getLogger("JavaChatVia");
    private static final AttributeKey<ProtocolVersion> TARGET_VERSION =
            AttributeKey.valueOf("javachat-target-version");
    private static final Object INIT_LOCK = new Object();

    private static volatile CompletableFuture<Void> initialization;
    private static volatile CommandSignatureTracker commandSignatures;

    private ViaTranslationRuntime() {}

    public static void initialize(Context context) {
        if (initialization != null) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialization != null) {
                return;
            }
            Context appContext = context.getApplicationContext();
            initialization = CompletableFuture.runAsync(() -> initializeNow(appContext));
            initialization.whenComplete((ignored, error) -> {
                if (error == null) {
                    Log.i("JavaChatVia", "ViaVersion translator ready");
                } else {
                    Log.e("JavaChatVia", "ViaVersion translator failed", error);
                }
            });
        }
    }

    public static void awaitReady(Context context) {
        initialize(context);
        awaitReady();
    }

    public static void awaitReady() {
        CompletableFuture<Void> future = initialization;
        if (future == null) {
            throw new IllegalStateException("버전 변환 엔진이 아직 초기화되지 않았습니다.");
        }
        try {
            future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("버전 변환 엔진을 시작하지 못했습니다: "
                    + cause.getMessage(), cause);
        }
    }

    static void useCommandSignatures(CommandSignatureTracker tracker) {
        commandSignatures = tracker;
    }

    static void clearCommandSignatures(CommandSignatureTracker tracker) {
        if (commandSignatures == tracker) {
            commandSignatures = null;
        }
    }

    static void inject(Channel channel, ProtocolSpec target) {
        ProtocolVersion version = ProtocolVersion.getProtocol(
                target.getTranslationProtocolNumber());
        if (!version.isKnown()) {
            throw new IllegalArgumentException("알 수 없는 Minecraft 프로토콜입니다: "
                    + target.getProtocolNumber());
        }

        channel.attr(TARGET_VERSION).set(version);
        UserConnection user = ViaChannelInitializer.createUserConnection(channel, true);
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addBefore(NetworkConstants.CODEC_NAME,
                ViaDecodeHandler.NAME, new ViaDecodeHandler(user));
        pipeline.addBefore(NetworkConstants.CODEC_NAME,
                ViaEncodeHandler.NAME, new ViaEncodeHandler(user));
        if (target.getProtocolNumber() != target.getTranslationProtocolNumber()) {
            pipeline.addBefore(ViaDecodeHandler.NAME,
                    "javachat-handshake-version",
                    new HandshakeVersionEncoder(target.getProtocolNumber()));
        }
        LOGGER.info("Translator attached: 26.2 -> " + target.getDisplayName());
    }

    private static void initializeNow(Context context) {
        File dataFolder = new File(context.getNoBackupFilesDir(), "viaversion");
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("버전 변환 설정 폴더를 만들 수 없습니다.");
        }

        AndroidViaPlatform platform = new AndroidViaPlatform(dataFolder);
        AndroidPlatformLoader loader = new AndroidPlatformLoader();
        ViaManagerImpl manager = new ViaManagerImpl(
                platform,
                new NoopInjector(),
                new ViaCommandHandler(false),
                loader);
        Via.init(manager);
        platform.getConf().reload();
        manager.init();
        loader.load();

        // Avoid ViaVersion's desktop-only Java runtime check while still
        // completing every mapping before Android can open a translated link.
        while (!manager.getProtocolManager().checkForMappingCompletion(true)) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("버전 매핑 준비가 중단되었습니다.", error);
            }
        }
        LOGGER.info("ViaVersion translator ready");
    }

    static final class AndroidPlatformLoader implements ViaPlatformLoader {
        @Override
        public void load() {
            Via.getManager().getProviders().use(VersionProvider.class,
                    new AndroidVersionProvider());
            Via.getManager().getProviders().use(CompressionProvider.class,
                    new NativeCompressionProvider());
            Via.getManager().getProviders().use(SignableCommandArgumentsProvider.class,
                    new AndroidCommandArgumentsProvider());
        }

        @Override
        public void unload() {
        }
    }

    private static final class AndroidVersionProvider extends BaseVersionProvider {
        @Override
        public ProtocolVersion getClosestServerProtocol(UserConnection connection)
                throws Exception {
            if (!connection.isClientSide()) {
                return super.getClosestServerProtocol(connection);
            }
            ProtocolVersion target = connection.getChannel().attr(TARGET_VERSION).get();
            if (target == null) {
                throw new IllegalStateException("연결에 대상 Minecraft 버전이 없습니다.");
            }
            return target;
        }
    }

    private static final class NativeCompressionProvider extends CompressionProvider {
        @Override
        public void handlePlayCompression(UserConnection user, int threshold) {
            ChannelHandler handler = user.getChannel().pipeline()
                    .get(NetworkConstants.MANAGER_NAME);
            if (!(handler instanceof Session session)) {
                throw new IllegalStateException("Minecraft 네트워크 세션을 찾을 수 없습니다.");
            }
            session.setCompression(threshold < 0 ? null
                    : new CompressionConfig(threshold, new ZlibCompression(), true));
        }
    }

    private static final class AndroidCommandArgumentsProvider
            extends SignableCommandArgumentsProvider {
        @Override
        public List<Pair<String, String>> getSignableArguments(String command) {
            CommandSignatureTracker tracker = commandSignatures;
            if (tracker == null) {
                return List.of();
            }
            return tracker.signableArguments(command).stream()
                    .map(argument -> new Pair<>(argument.getName(), argument.getValue()))
                    .toList();
        }
    }

    static final class HandshakeVersionEncoder
            extends MessageToMessageEncoder<ByteBuf> {
        private final int wireProtocol;
        private boolean handled;

        HandshakeVersionEncoder(int wireProtocol) {
            this.wireProtocol = wireProtocol;
        }

        @Override
        protected void encode(ChannelHandlerContext context, ByteBuf input,
                              List<Object> output) {
            if (handled) {
                output.add(input.retain());
                return;
            }
            handled = true;

            ByteBuf view = input.duplicate();
            int packetId = readVarInt(view);
            if (packetId != 0) {
                output.add(input.retain());
                return;
            }

            readVarInt(view); // Translated protocol number
            ByteBuf rewritten = context.alloc().buffer(input.readableBytes() + 1);
            writeVarInt(rewritten, packetId);
            writeVarInt(rewritten, wireProtocol);
            rewritten.writeBytes(view);
            output.add(rewritten);
        }

        private static int readVarInt(ByteBuf input) {
            int value = 0;
            int position = 0;
            byte current;
            do {
                current = input.readByte();
                value |= (current & 0x7F) << position;
                position += 7;
                if (position >= 35) {
                    throw new IllegalArgumentException("잘못된 VarInt입니다.");
                }
            } while ((current & 0x80) != 0);
            return value;
        }

        private static void writeVarInt(ByteBuf output, int value) {
            while ((value & ~0x7F) != 0) {
                output.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            output.writeByte(value);
        }
    }

    private static final class AndroidViaPlatform
            extends UserConnectionViaVersionPlatform {
        AndroidViaPlatform(File dataFolder) {
            super(dataFolder);
        }

        @Override
        public Logger createLogger(String name) {
            return Logger.getLogger(name);
        }

        @Override
        public String getPlatformName() {
            return "Minecraft Chat Android";
        }

        @Override
        public String getPlatformVersion() {
            return "1.1";
        }

        @Override
        protected AbstractViaConfig createConfig() {
            return new AndroidViaConfig(new File(getDataFolder(), "viaversion.yml"),
                    getLogger());
        }
    }

    private static final class AndroidViaConfig extends AbstractViaConfig {
        AndroidViaConfig(File configFile, Logger logger) {
            super(configFile, logger);
        }

        @Override
        public boolean isCheckForUpdates() {
            return false;
        }

        @Override
        public boolean isSimulatePlayerTick() {
            return false;
        }
    }
}
