package com.dudal.javachat.protocol;

import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PlayerTracker {
    private static final EnumSet<PlayerListEntryAction> VISIBLE_ACTIONS = EnumSet.of(
            PlayerListEntryAction.ADD_PLAYER,
            PlayerListEntryAction.UPDATE_LISTED,
            PlayerListEntryAction.UPDATE_LATENCY,
            PlayerListEntryAction.UPDATE_DISPLAY_NAME,
            PlayerListEntryAction.UPDATE_HAT);

    private final Map<UUID, MutablePlayer> players = new HashMap<>();
    private List<PlayerView> cachedSnapshot = List.of();

    synchronized List<PlayerView> apply(ClientboundPlayerInfoUpdatePacket packet) {
        EnumSet<PlayerListEntryAction> actions = packet.getActions();
        if (java.util.Collections.disjoint(actions, VISIBLE_ACTIONS)) {
            return cachedSnapshot;
        }
        for (PlayerListEntry entry : packet.getEntries()) {
            MutablePlayer player = players.computeIfAbsent(entry.getProfileId(), MutablePlayer::new);
            if (actions.contains(PlayerListEntryAction.ADD_PLAYER) && entry.getProfile() != null) {
                player.name = entry.getProfile().getName();
                player.skinUrl = ProfileSkin.url(entry.getProfile());
            }
            if (actions.contains(PlayerListEntryAction.UPDATE_LISTED)) {
                player.listed = entry.isListed();
            }
            if (actions.contains(PlayerListEntryAction.UPDATE_LATENCY)) {
                player.latency = entry.getLatency();
            }
            if (actions.contains(PlayerListEntryAction.UPDATE_DISPLAY_NAME)) {
                player.displayName = ComponentText.plain(entry.getDisplayName());
            }
            if (actions.contains(PlayerListEntryAction.UPDATE_HAT)) {
                player.showHat = entry.isShowHat();
            }
        }
        return snapshot();
    }

    synchronized List<PlayerView> apply(ClientboundPlayerInfoRemovePacket packet) {
        for (UUID id : packet.getProfileIds()) {
            players.remove(id);
        }
        return snapshot();
    }

    synchronized void clear() {
        players.clear();
        cachedSnapshot = List.of();
    }

    private List<PlayerView> snapshot() {
        List<PlayerView> result = new ArrayList<>();
        for (MutablePlayer player : players.values()) {
            if (player.listed && player.name != null) {
                String label = player.displayName == null || player.displayName.isBlank()
                        ? player.name : player.displayName;
                result.add(new PlayerView(
                        player.id, label, player.latency, player.skinUrl, player.showHat,
                        player.name));
            }
        }
        result.sort(Comparator.comparing(PlayerView::getName, String.CASE_INSENSITIVE_ORDER));
        cachedSnapshot = List.copyOf(result);
        return cachedSnapshot;
    }

    private static final class MutablePlayer {
        private final UUID id;
        private String name;
        private String displayName;
        private boolean listed;
        private boolean showHat = true;
        private int latency;
        private String skinUrl;

        private MutablePlayer(UUID id) {
            this.id = id;
        }
    }
}
