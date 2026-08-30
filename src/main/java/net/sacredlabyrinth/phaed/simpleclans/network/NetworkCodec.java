package net.sacredlabyrinth.phaed.simpleclans.network;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.proxy.adapters.ClanPlayerListAdapter;
import net.sacredlabyrinth.phaed.simpleclans.proxy.adapters.ClanPlayerTypeAdapterFactory;
import net.sacredlabyrinth.phaed.simpleclans.proxy.adapters.ConfigurationSerializableAdapter;
import net.sacredlabyrinth.phaed.simpleclans.proxy.adapters.SCMessageAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;

/**
 * Turns clan objects into the JSON that travels on the network channel, and back.
 *
 * @since 2.19.4
 */
public final class NetworkCodec {

    private final Gson gson;

    public NetworkCodec(@NotNull SimpleClans plugin) {
        this.gson = new GsonBuilder()
                .registerTypeAdapterFactory(new ClanPlayerTypeAdapterFactory(plugin))
                .registerTypeAdapterFactory(new ConfigurationSerializableAdapter())
                .registerTypeAdapter(ClanPlayerListAdapter.getType(), new ClanPlayerListAdapter(plugin))
                .registerTypeAdapter(SCMessage.class, new SCMessageAdapter(plugin))
                .setExclusionStrategies(new NoSyncExclusionStrategy())
                .create();
    }

    public @NotNull Gson getGson() {
        return gson;
    }

    public @NotNull String encode(@NotNull NetworkMessage message) {
        return gson.toJson(message);
    }

    /**
     * @return the decoded envelope, or null if the payload is not readable
     */
    public @Nullable NetworkMessage decode(@NotNull String raw) {
        try {
            return gson.fromJson(raw, NetworkMessage.class);
        } catch (JsonParseException ex) {
            return null;
        }
    }

    public @Nullable JsonElement toTree(@Nullable Object value) {
        return value == null ? null : gson.toJsonTree(value);
    }

    public <T> @Nullable T fromTree(@Nullable JsonElement element, @NotNull Class<T> type) {
        return element == null ? null : gson.fromJson(element, type);
    }

    public <T> @Nullable T fromTree(@Nullable JsonElement element, @NotNull Type type) {
        return element == null ? null : gson.fromJson(element, type);
    }

    /**
     * Keeps {@link NoNetworkSync} fields out of the wire. See the annotation for
     * why {@code ObjectUtils} has to honour the very same rule.
     */
    private static final class NoSyncExclusionStrategy implements ExclusionStrategy {

        @Override
        public boolean shouldSkipField(FieldAttributes f) {
            return f.getAnnotation(NoNetworkSync.class) != null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    }
}
