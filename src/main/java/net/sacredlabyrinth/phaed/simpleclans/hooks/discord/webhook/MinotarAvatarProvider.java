package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Avatar provider backed by https://minotar.net/
 */
public final class MinotarAvatarProvider implements AvatarProvider {

    private static final String HELM_URL = "https://minotar.net/helm/%s/100.png";

    @Override
    public @NotNull String avatarUrl(@Nullable UUID uuid, @Nullable String name) {
        if (uuid != null) {
            // Minotar accepts dashless UUIDs, immune to nickname changes
            return String.format(HELM_URL, uuid.toString().replace("-", ""));
        }
        if (name != null && !name.isEmpty()) {
            return String.format(HELM_URL, name);
        }
        return String.format(HELM_URL, "MHF_Steve");
    }
}
