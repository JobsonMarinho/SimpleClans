package net.sacredlabyrinth.phaed.simpleclans.network;

/**
 * Every kind of message that travels on the clan network channel.
 * <p>
 * The name is part of the wire format, so renaming a constant is a breaking
 * change: bump {@link NetworkMessage#PROTOCOL} when you do it.
 *
 * @since 2.19.4
 */
public enum MessageType {

    /** A full {@link net.sacredlabyrinth.phaed.simpleclans.Clan} snapshot. */
    UPDATE_CLAN,
    /** A full {@link net.sacredlabyrinth.phaed.simpleclans.ClanPlayer} snapshot. */
    UPDATE_CLAN_PLAYER,
    /** A clan tag that no longer exists. */
    DELETE_CLAN,
    /** A player UUID whose record was purged. */
    DELETE_CLAN_PLAYER,

    /** A clan/ally chat line to be delivered to the local members of that clan. */
    CHAT,
    /** A raw line for every player on the server. */
    BROADCAST,
    /** A raw line for one player, wherever they are. */
    PRIVATE_MESSAGE,

    /** The complete list of players online on the sending server. */
    PRESENCE_SYNC,
    /** One player joined the sending server. */
    PRESENCE_JOIN,
    /** One player left the sending server. */
    PRESENCE_QUIT,

    /** A pending request (invite, promotion, alliance...) was opened. */
    REQUEST_CREATE,
    /** Someone voted on a request owned by another server. */
    REQUEST_VOTE,
    /** A request is over and must disappear from every replica. */
    REQUEST_CANCEL,

    /** A server just booted and wants the changes still missing from the database. */
    SYNC_REQUEST,
    /** The answer to a {@link #SYNC_REQUEST}. */
    SYNC_RESPONSE
}
