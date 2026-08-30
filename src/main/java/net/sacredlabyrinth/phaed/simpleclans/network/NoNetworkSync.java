package net.sacredlabyrinth.phaed.simpleclans.network;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field that must never travel over the network, nor be overwritten by
 * an incoming network update.
 * <p>
 * Two very different mechanisms have to agree on this, and they both read this
 * annotation so the rule lives in a single place:
 * <ul>
 *     <li>{@link NetworkCodec} skips the field when serializing/deserializing;</li>
 *     <li>{@link net.sacredlabyrinth.phaed.simpleclans.utils.ObjectUtils#updateFields}
 *     skips the field when copying a received snapshot over the live object.</li>
 * </ul>
 * Skipping only the first one would be actively harmful: the deserialized
 * snapshot would carry a {@code null}/default value and the field copy would
 * happily wipe the local value with it.
 *
 * @since 2.19.4
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface NoNetworkSync {
}
