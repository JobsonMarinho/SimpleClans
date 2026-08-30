package net.sacredlabyrinth.phaed.simpleclans.network;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A block of functionality that can be switched off per server.
 * <p>
 * The point is not to save resources: it is to keep a server from writing state
 * that another server, on a different Minecraft version, cannot read back.
 *
 * @since 2.19.4
 */
public enum ClanFeature {

    /**
     * {@code /clan banner}. The banner is a serialized ItemStack; a banner written
     * by a modern server cannot be deserialized by a legacy one.
     */
    BANNER("banner"),

    /**
     * {@code /clan cape}.
     */
    CAPE("cape"),

    /**
     * {@code /clan home}, {@code sethome} and {@code clearhome}. A clan has a
     * single home, so setting it here would move it out of the other server's world.
     */
    HOME("home"),

    /**
     * {@code /clan regroup}, which teleports the whole clan to the clan home.
     */
    REGROUP("regroup"),

    /**
     * The war system.
     */
    WAR("war"),

    /**
     * Land claiming/sharing and the protection providers.
     */
    LAND("land"),

    /**
     * The clan bank ({@code /clan bank deposit|withdraw}). The balance is a
     * read-modify-write over an in-memory value, so two servers can interleave.
     */
    BANK("bank"),

    /**
     * Member fees.
     */
    FEE("fee");

    private final String configName;

    ClanFeature(String configName) {
        this.configName = configName;
    }

    public @NotNull String getConfigName() {
        return configName;
    }

    /**
     * @return the message key used to tell the player this feature is off here
     */
    public @NotNull String getDeniedKey() {
        return "feature.disabled." + configName;
    }

    public static @Nullable ClanFeature parse(@NotNull String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ClanFeature feature : values()) {
            if (feature.name().equals(normalized)) {
                return feature;
            }
        }
        return null;
    }
}
