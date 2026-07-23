package com.dudal.javachat.protocol;

import com.dudal.javachat.auth.ConnectionIdentity;
import com.dudal.javachat.auth.OnlineIdentity;
import com.dudal.javachat.data.SavedServer;
import com.dudal.javachat.util.ErrorText;

import net.kyori.adventure.text.Component;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.netty.DefaultPacketHandlerExecutor;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class Mc26_2Connection implements ProtocolConnection {
    private static final Logger LOGGER = Logger.getLogger("JavaChatProtocol");

    private final SavedServer server;
    private final ConnectionIdentity identity;
    private final ConnectionListener listener;
    private final ProtocolSpec protocolSpec;
    private final PlayerTracker playerTracker = new PlayerTracker();
    private final CommandSignatureTracker commandSignatures = new CommandSignatureTracker();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicInteger suggestionSequence = new AtomicInteger();
    private final Map<Integer, String> suggestionRequests = new ConcurrentHashMap<>();

    private volatile ClientNetworkSession session;
    private volatile SignedChatState signedChat;
    private volatile UUID serverProfileId;
    private volatile boolean secureChatRequired;
    private volatile int latestSuggestionId;

    Mc26_2Connection(SavedServer server, ConnectionIdentity identity,
                     ConnectionListener listener, ProtocolSpec protocolSpec) {
        this.server = server;
        this.identity = identity;
        this.listener = listener;
        this.protocolSpec = protocolSpec;
    }

    @Override
    public void connect() {
        if (session != null && session.isConnected()) {
            return;
        }

        serverProfileId = null;
        listener.onStateChanged(ConnectionState.CONNECTING,
                server.getHost() + ":" + server.getPort() + " · "
                        + protocolSpec.getDisplayName());
        MinecraftProtocol protocol = createProtocol();
        ClientNetworkSession created = createSession(protocol);
        created.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
        created.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                listener.onStateChanged(ConnectionState.AUTHENTICATING, "Minecraft 로그인 진행 중");
            }

            @Override
            public void packetReceived(Session activeSession, Packet packet) {
                handlePacket(activeSession, packet);
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                ready.set(false);
                signedChat = null;
                serverProfileId = null;
                suggestionRequests.clear();
                playerTracker.clear();
                ViaTranslationRuntime.clearCommandSignatures(commandSignatures);
                listener.onPlayersChanged(java.util.List.of());
                String reason = event.getCause() == null
                        ? ComponentText.plain(event.getReason())
                        : ErrorText.from(event.getCause());
                listener.onStateChanged(event.getCause() == null
                                ? ConnectionState.DISCONNECTED : ConnectionState.ERROR,
                        reason.isBlank() ? "서버 연결이 종료되었습니다." : reason);
            }
        });
        session = created;
        created.connect();
    }

    @Override
    public void disconnect() {
        ClientNetworkSession active = session;
        ready.set(false);
        ViaTranslationRuntime.clearCommandSignatures(commandSignatures);
        if (active != null && active.isConnected()) {
            active.disconnect(Component.text("모바일 앱에서 연결 종료"));
        }
    }

    @Override
    public void sendChat(String message) throws Exception {
        ClientNetworkSession active = session;
        if (!ready.get() || active == null || !active.isConnected()) {
            throw new IllegalStateException("서버 연결이 완료되지 않았습니다.");
        }
        String trimmed = ChatInput.normalize(message);
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("/")) {
            sendCommand(active, trimmed.substring(1).trim());
            return;
        }
        if (trimmed.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("채팅은 UTF-8 기준 256바이트 이하여야 합니다.");
        }

        ServerboundChatPacket packet;
        SignedChatState signer = signedChat;
        if (signer != null) {
            packet = signer.createPacket(trimmed);
        } else {
            if (secureChatRequired) {
                throw new IllegalStateException("이 서버는 서명 채팅을 요구하지만 Microsoft 인증서가 없습니다.");
            }
            packet = new ServerboundChatPacket(
                    trimmed,
                    Instant.now().toEpochMilli(),
                    0L,
                    null,
                    0,
                    new BitSet(20),
                    1);
        }
        active.send(packet);
    }

    @Override
    public void requestCommandSuggestions(String input) {
        ClientNetworkSession active = session;
        String normalized = ChatInput.normalize(input);
        if (!ready.get() || active == null || !active.isConnected()
                || !normalized.startsWith("/")) {
            return;
        }
        if (normalized.length() > 32500) {
            return;
        }
        int transactionId = suggestionSequence.updateAndGet(
                value -> value == Integer.MAX_VALUE ? 1 : value + 1);
        latestSuggestionId = transactionId;
        suggestionRequests.clear();
        suggestionRequests.put(transactionId, normalized);
        active.send(new ServerboundCommandSuggestionPacket(transactionId, normalized));
    }

    @Override
    public boolean isConnected() {
        ClientNetworkSession active = session;
        return ready.get() && active != null && active.isConnected();
    }

    private MinecraftProtocol createProtocol() {
        if (identity.isOnline()) {
            OnlineIdentity online = identity.getOnlineIdentity();
            return new MinecraftProtocol(
                    new GameProfile(online.getProfileId(), online.getProfileName()),
                    online.getAccessToken());
        }

        String username = identity.getOfflineNickname();
        UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username)
                .getBytes(StandardCharsets.UTF_8));
        return new MinecraftProtocol(new GameProfile(offlineId, username), null);
    }

    private ClientNetworkSession createSession(MinecraftProtocol protocol) {
        if (protocolSpec.getProtocolNumber()
                == ProtocolRegistry.JAVA_26_2.getProtocolNumber()) {
            return ClientNetworkSessionFactory.factory()
                    .setAddress(server.getHost(), server.getPort())
                    .setProtocol(protocol)
                    .create();
        }

        ViaTranslationRuntime.awaitReady();
        ViaTranslationRuntime.useCommandSignatures(commandSignatures);
        return new TranslatedClientNetworkSession(
                InetSocketAddress.createUnresolved(server.getHost(), server.getPort()),
                protocol,
                DefaultPacketHandlerExecutor.createExecutor(),
                null,
                null,
                protocolSpec);
    }

    private void sendCommand(ClientNetworkSession active, String command) throws Exception {
        if (command.isEmpty()) {
            return;
        }
        if (command.length() > 32500) {
            throw new IllegalArgumentException("명령어는 32,500자 이하여야 합니다.");
        }
        SignedChatState signer = signedChat;
        if (signer == null) {
            active.send(new ServerboundChatCommandPacket(command));
            return;
        }
        List<CommandArgument> arguments = commandSignatures.signableArguments(command);
        active.send(signer.createCommandPacket(command, arguments));
    }

    private void handlePacket(Session activeSession, Packet packet) {
        if (packet instanceof ClientboundLoginFinishedPacket loginFinished) {
            serverProfileId = loginFinished.getProfile().getId();
            if (identity.isOnline()) {
                UUID authenticatedId = identity.getOnlineIdentity().getProfileId();
                LOGGER.info("Login profile UUID match="
                        + ProfileKeyPolicy.canUseCertificates(
                                authenticatedId, serverProfileId));
            }
            activeSession.send(clientInformation());
        } else if (packet instanceof ClientboundLoginPacket login) {
            secureChatRequired = login.isEnforcesSecureChat();
            // ViaVersion synthesizes the modern configuration phase for old
            // servers. Repeat settings in PLAY so legacy servers always get
            // chat visibility and skin-part preferences.
            activeSession.send(clientInformation());
            if (identity.isOnline()) {
                OnlineIdentity online = identity.getOnlineIdentity();
                if (ProfileKeyPolicy.canUseCertificates(
                        online.getProfileId(), serverProfileId)) {
                    SignedChatState state = new SignedChatState(
                            online.getProfileId(), online.getCertificates());
                    LOGGER.info("Sending 26.2 profile key: v2="
                            + signatureLength(online.getCertificates().getPublicKeySignature())
                            + ", legacy="
                            + signatureLength(online.getCertificates()
                                    .getLegacyPublicKeySignature()));
                    signedChat = state;
                    activeSession.send(state.sessionUpdatePacket());
                } else {
                    // Offline-mode/proxy servers can assign a UUID different
                    // from the authenticated Microsoft profile. That profile
                    // key cannot validate for the server UUID, so the official
                    // client deliberately omits the chat session update too.
                    signedChat = null;
                    LOGGER.info("Skipping profile key for server-assigned UUID");
                }
            }
        } else if (packet instanceof ClientboundPlayerPositionPacket position) {
            activeSession.send(new ServerboundAcceptTeleportationPacket(position.getId()));
            if (ready.compareAndSet(false, true)) {
                listener.onStateChanged(ConnectionState.CONNECTED,
                        secureChatRequired
                                ? "서명 채팅 사용 · " + protocolSpec.getDisplayName()
                                : protocolSpec.getDisplayName());
            }
        } else if (packet instanceof ClientboundPingPacket ping) {
            activeSession.send(new ServerboundPongPacket(ping.getId()));
        } else if (packet instanceof ClientboundCommandsPacket commands) {
            commandSignatures.update(commands);
        } else if (packet instanceof ClientboundCommandSuggestionsPacket suggestions) {
            String requestText = suggestionRequests.remove(suggestions.getTransactionId());
            if (requestText != null && suggestions.getTransactionId() == latestSuggestionId) {
                listener.onCommandSuggestions(new CommandSuggestions(
                        requestText,
                        suggestions.getStart(),
                        suggestions.getLength(),
                        Arrays.asList(suggestions.getMatches())));
            }
        } else if (packet instanceof ClientboundPlayerInfoUpdatePacket update) {
            listener.onPlayersChanged(playerTracker.apply(update));
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket remove) {
            listener.onPlayersChanged(playerTracker.apply(remove));
        } else if (packet instanceof ClientboundPlayerChatPacket chat) {
            SignedChatState signer = signedChat;
            if (signer != null) {
                signer.observe(chat.getMessageSignature());
                int count = signer.takePendingAckCountIfRequired();
                if (count > 0) {
                    activeSession.send(new ServerboundChatAckPacket(count));
                }
            }
            String content = chat.getUnsignedContent() != null
                    ? ComponentText.plain(chat.getUnsignedContent()) : chat.getContent();
            listener.onChat(new ChatLine(
                    chat.getTimeStamp(),
                    ChatLine.Kind.PLAYER,
                    ComponentText.plain(chat.getName()),
                    content));
        } else if (packet instanceof ClientboundDisguisedChatPacket chat) {
            listener.onChat(new ChatLine(
                    System.currentTimeMillis(),
                    ChatLine.Kind.PLAYER,
                    ComponentText.plain(chat.getName()),
                    ComponentText.plain(chat.getMessage())));
        } else if (packet instanceof ClientboundSystemChatPacket chat
                && !chat.isOverlay()) {
            String message = SystemChatFilter.displayText(chat.getContent());
            if (message != null) {
                listener.onChat(new ChatLine(
                        System.currentTimeMillis(),
                        SystemChatFilter.isPlayerPresence(chat.getContent())
                                ? ChatLine.Kind.PRESENCE : ChatLine.Kind.SYSTEM,
                        "서버",
                        message));
            }
        }
    }

    private static int signatureLength(byte[] signature) {
        return signature == null ? 0 : signature.length;
    }

    private static ServerboundClientInformationPacket clientInformation() {
        return new ServerboundClientInformationPacket(
                "ko_kr",
                2,
                ChatVisibility.FULL,
                true,
                Arrays.asList(SkinPart.values()),
                HandPreference.RIGHT_HAND,
                false,
                true,
                ParticleStatus.MINIMAL);
    }

}
