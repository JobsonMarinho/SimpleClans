package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Resolves the avatar/head image URL shown in Discord embeds.
 * <p>
 * Centralized behind an interface so the skin provider can be swapped without
 * touching the webhook pipeline.
 * </p>
 */
public interface AvatarProvider {

    /**
     * Builds the avatar URL for a player. Prefers the UUID when available so name
     * changes never break the image; falls back to the name, then to a default skin.
     *
     * @param uuid the player's UUID, if known
     * @param name the player's name, if known
     * @return a fetchable image URL (Discord downloads it, the plugin does no HTTP here)
     */
    @NotNull
    String avatarUrl(@Nullable UUID uuid, @Nullable String name);
}
