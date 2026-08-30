package net.sacredlabyrinth.phaed.simpleclans.commands.conditions;

import co.aikar.commands.BukkitCommandIssuer;
import co.aikar.commands.ConditionContext;
import co.aikar.commands.ConditionFailedException;
import co.aikar.commands.InvalidCommandArgument;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.network.ClanFeature;
import org.jetbrains.annotations.NotNull;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;

/**
 * Blocks a command whose feature is switched off on this server.
 * <p>
 * Usage: {@code @Conditions("feature:name=BANNER")}.
 *
 * @see net.sacredlabyrinth.phaed.simpleclans.managers.FeatureManager
 * @since 2.19.4
 */
@SuppressWarnings("unused")
public class FeatureCondition extends AbstractCommandCondition {

    public FeatureCondition(@NotNull SimpleClans plugin) {
        super(plugin);
    }

    @Override
    public void validateCondition(ConditionContext<BukkitCommandIssuer> context) throws InvalidCommandArgument {
        String name = context.getConfigValue("name", (String) null);
        if (name == null) {
            return;
        }
        ClanFeature feature = ClanFeature.parse(name);
        if (feature == null) {
            plugin.getLogger().warning("Unknown feature in a command condition: " + name);
            return;
        }
        if (plugin.getFeatureManager().isEnabled(feature)) {
            return;
        }
        throw new ConditionFailedException(lang(feature.getDeniedKey(), context.getIssuer()));
    }

    @Override
    public @NotNull String getId() {
        return "feature";
    }
}
