package net.sacredlabyrinth.phaed.simpleclans.managers;

import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.network.ClanFeature;
import net.sacredlabyrinth.phaed.simpleclans.network.ServerMode;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_DISABLED_FEATURES;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.NETWORK_MODE;

/**
 * Decides which parts of SimpleClans this particular server is allowed to run.
 * <p>
 * When several servers share one clan database but not the same Minecraft
 * version, some features simply cannot be written from both sides. A clan has a
 * single home and a single banner; the banner does not even survive the trip
 * between a modern and a legacy server. Rather than letting the newer server
 * corrupt what the older one reads, {@link ServerMode#GARDEN} switches those
 * features off there.
 *
 * @since 2.19.4
 */
public final class FeatureManager {

    /**
     * What a Garden server gives up unless the operator says otherwise.
     */
    private static final Set<ClanFeature> GARDEN_DEFAULTS = Collections.unmodifiableSet(EnumSet.of(
            ClanFeature.BANNER,
            ClanFeature.CAPE,
            ClanFeature.HOME,
            ClanFeature.REGROUP,
            ClanFeature.WAR,
            ClanFeature.LAND,
            ClanFeature.BANK));

    private final ServerMode mode;
    private final Set<ClanFeature> disabled = EnumSet.noneOf(ClanFeature.class);

    public FeatureManager(@NotNull SimpleClans plugin) {
        SettingsManager settings = plugin.getSettingsManager();
        this.mode = ServerMode.parse(settings.getString(NETWORK_MODE));

        List<String> configured = settings.getStringList(NETWORK_DISABLED_FEATURES);
        if (configured.isEmpty()) {
            // no explicit list: fall back to what the mode implies
            if (mode == ServerMode.GARDEN) {
                disabled.addAll(GARDEN_DEFAULTS);
            }
        } else {
            for (String raw : configured) {
                ClanFeature feature = ClanFeature.parse(raw);
                if (feature == null) {
                    plugin.getLogger().warning(String.format(
                            "Unknown feature '%s' in network.disabled-features, ignoring it", raw));
                    continue;
                }
                disabled.add(feature);
            }
        }
    }

    public @NotNull ServerMode getMode() {
        return mode;
    }

    /**
     * @return true when the feature may be used on this server
     */
    public boolean isEnabled(@NotNull ClanFeature feature) {
        return !disabled.contains(feature);
    }

    public boolean isDisabled(@NotNull ClanFeature feature) {
        return disabled.contains(feature);
    }

    public @NotNull Set<ClanFeature> getDisabledFeatures() {
        return Collections.unmodifiableSet(disabled);
    }

    /**
     * @return a human readable summary for the startup log
     */
    public @NotNull String describe() {
        if (disabled.isEmpty()) {
            return mode + " (every feature enabled)";
        }
        return mode + " (disabled: " + disabled.stream()
                .map(ClanFeature::getConfigName)
                .sorted()
                .collect(Collectors.joining(", ")) + ")";
    }
}
