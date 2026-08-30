package net.sacredlabyrinth.phaed.simpleclans.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.VoteResult;
import net.sacredlabyrinth.phaed.simpleclans.utils.ObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-server sync copies fields by reflection over a JSON snapshot, so two
 * rules have to hold or the caches corrupt each other:
 * <ul>
 *     <li>a {@link NoNetworkSync} field never leaves this server, and</li>
 *     <li>an incoming snapshot never overwrites one.</li>
 * </ul>
 */
class NetworkCodecTest {

    private NetworkCodec codec;

    @BeforeEach
    void setUp() {
        // The adapters only keep the reference; they dereference it when reading a
        // ClanPlayer, which these tests deliberately avoid. Mocking a JavaPlugin is
        // not an option here - the inline mock maker cannot instrument it.
        codec = new NetworkCodec(NO_PLUGIN);
    }

    private static final SimpleClans NO_PLUGIN = null;

    @Test
    void clanPayloadLeavesTheBannerBehind() {
        Clan clan = new Clan();
        clan.setTag("abc");
        clan.setName("Test Clan");

        JsonObject json = codec.toTree(clan).getAsJsonObject();

        assertTrue(json.has("tag"), "the clan tag must travel");
        assertFalse(json.has("banner"),
                "the banner is a serialized ItemStack and cannot cross a version gap");
    }

    @Test
    void clanPlayerPayloadLeavesTheVoteBehind() {
        ClanPlayer cp = newClanPlayer(UUID.randomUUID(), "Someone");
        cp.setVote(VoteResult.ACCEPT);

        JsonObject json = codec.toTree(cp).getAsJsonObject();

        assertTrue(json.has("displayName"), "the player name must travel");
        assertFalse(json.has("vote"), "a vote belongs to a request, not to the player record");
    }

    @Test
    void clanSurvivesARoundTrip() {
        Clan origin = new Clan();
        origin.setTag("abc");
        origin.setName("Test Clan");
        origin.setDescription("hello");
        origin.setFriendlyFire(true);
        origin.setFounded(1234L);

        JsonElement tree = codec.toTree(origin);
        Clan decoded = codec.fromTree(tree, Clan.class);

        assertNotNull(decoded);
        assertEquals("abc", decoded.getTag());
        assertEquals("Test Clan", decoded.getName());
        assertEquals("hello", decoded.getDescription());
        assertTrue(decoded.isFriendlyFire());
        assertEquals(1234L, decoded.getFounded());
    }

    /**
     * Reading a ClanPlayer back needs a live ClanManager to resolve its clan, so
     * this covers the outgoing half - which is where a field that Gson cannot
     * reflect into (a JDK type such as Locale) would blow up.
     */
    @Test
    void clanPlayerPayloadCarriesEveryOrdinaryField() {
        UUID uuid = UUID.randomUUID();
        ClanPlayer origin = newClanPlayer(uuid, "Someone");
        origin.setLeader(true);
        origin.setDeaths(7);
        origin.setLocale(new Locale("pt", "BR"));

        JsonObject json = codec.toTree(origin).getAsJsonObject();

        assertEquals(uuid.toString(), json.get("uniqueId").getAsString());
        assertEquals("Someone", json.get("displayName").getAsString());
        assertTrue(json.get("leader").getAsBoolean());
        assertEquals(7, json.get("deaths").getAsInt());
        assertTrue(json.has("clan"), "the adapter flattens the clan down to its tag");
        assertTrue(json.get("locale").getAsString().startsWith("pt"),
                "Gson must render a Locale as a plain string");
    }

    @Test
    void envelopeSurvivesARoundTrip() {
        NetworkMessage origin = new NetworkMessage("SFullPvP", "SFullPvPGarden",
                MessageType.UPDATE_CLAN, codec.toTree("payload"));

        NetworkMessage decoded = codec.decode(codec.encode(origin));

        assertNotNull(decoded);
        assertEquals(NetworkMessage.PROTOCOL, decoded.getProtocol());
        assertEquals("SFullPvP", decoded.getSource());
        assertEquals("SFullPvPGarden", decoded.getTarget());
        assertEquals(MessageType.UPDATE_CLAN, decoded.getType());
        assertEquals("payload", codec.fromTree(decoded.getPayload(), String.class));
    }

    @Test
    void broadcastEnvelopeHasNoTarget() {
        NetworkMessage decoded = codec.decode(codec.encode(
                new NetworkMessage("SFullPvP", null, MessageType.CHAT, null)));

        assertNotNull(decoded);
        assertNull(decoded.getTarget());
        assertNull(decoded.getPayload());
    }

    @Test
    void garbageIsRejectedInsteadOfThrowing() {
        assertNull(codec.decode("this is not json {{{"));
    }

    @Test
    void fieldCopyKeepsTheLocalVote() {
        ClanPlayer incoming = newClanPlayer(UUID.randomUUID(), "Someone");
        // what a decoded snapshot looks like: the excluded field is left at default
        assertNull(incoming.getVote());

        ClanPlayer live = newClanPlayer(UUID.randomUUID(), "Other");
        live.setVote(VoteResult.ACCEPT);

        assertDoesNotThrowUpdate(incoming, live);

        assertEquals(VoteResult.ACCEPT, live.getVote(),
                "an unrelated player update must not clear a vote on a live request");
        assertEquals("Someone", live.getName(), "the ordinary fields still get copied");
    }

    /**
     * The {@code ClanPlayer(UUID)} constructor reaches for the running server to
     * resolve a display name, so tests build the object field by field instead.
     */
    private static ClanPlayer newClanPlayer(UUID uniqueId, String name) {
        ClanPlayer cp = new ClanPlayer();
        cp.setUniqueId(uniqueId);
        cp.setName(name);
        return cp;
    }

    private void assertDoesNotThrowUpdate(Object origin, Object destination) {
        try {
            ObjectUtils.updateFields(origin, destination);
        } catch (IllegalAccessException ex) {
            throw new AssertionError(ex);
        }
    }
}
