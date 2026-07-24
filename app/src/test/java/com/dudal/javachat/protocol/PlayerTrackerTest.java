package com.dudal.javachat.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class PlayerTrackerTest {
    @Test
    public void carriesSkinAndHatPreferenceIntoPlayerView() {
        String hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        String payload = "{\"textures\":{\"SKIN\":{\"url\":"
                + "\"https://textures.minecraft.net/texture/" + hash + "\"}}}";
        UUID id = UUID.randomUUID();
        GameProfile profile = new GameProfile(id, "SkinPlayer");
        profile.setProperties(List.of(new GameProfile.Property(
                "textures",
                Base64.getEncoder().encodeToString(
                        payload.getBytes(StandardCharsets.UTF_8)))));

        PlayerListEntry entry = new PlayerListEntry(id);
        entry.setProfile(profile);
        entry.setListed(true);
        entry.setLatency(37);
        entry.setShowHat(false);
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        PlayerListEntryAction.ADD_PLAYER,
                        PlayerListEntryAction.UPDATE_LISTED,
                        PlayerListEntryAction.UPDATE_LATENCY,
                        PlayerListEntryAction.UPDATE_HAT),
                new PlayerListEntry[] {entry});

        List<PlayerView> players = new PlayerTracker().apply(packet);

        assertEquals(1, players.size());
        assertEquals("SkinPlayer", players.get(0).getName());
        assertEquals("SkinPlayer", players.get(0).getProfileName());
        assertEquals(37, players.get(0).getLatency());
        assertEquals("https://textures.minecraft.net/texture/" + hash,
                players.get(0).getSkinUrl());
        assertFalse(players.get(0).isShowHat());
    }

    @Test
    public void reusesSnapshotWhenPacketDoesNotChangeVisiblePlayerData() {
        UUID id = UUID.randomUUID();
        GameProfile profile = new GameProfile(id, "StablePlayer");
        PlayerListEntry entry = new PlayerListEntry(id);
        entry.setProfile(profile);
        entry.setListed(true);

        PlayerTracker tracker = new PlayerTracker();
        List<PlayerView> initial = tracker.apply(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        PlayerListEntryAction.ADD_PLAYER,
                        PlayerListEntryAction.UPDATE_LISTED),
                new PlayerListEntry[] {entry}));
        List<PlayerView> unchanged = tracker.apply(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(PlayerListEntryAction.UPDATE_GAME_MODE),
                new PlayerListEntry[] {entry}));

        assertSame(initial, unchanged);
    }
}
