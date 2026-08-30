package net.sacredlabyrinth.phaed.simpleclans.network.payload;

import net.sacredlabyrinth.phaed.simpleclans.VoteResult;
import org.jetbrains.annotations.NotNull;

/**
 * A vote cast on a server that does not own the request.
 *
 * @since 2.19.4
 */
public final class RequestVotePayload {

    private final String key;
    private final String voter;
    private final VoteResult result;

    public RequestVotePayload(@NotNull String key, @NotNull String voter, @NotNull VoteResult result) {
        this.key = key;
        this.voter = voter;
        this.result = result;
    }

    public String getKey() {
        return key;
    }

    public String getVoter() {
        return voter;
    }

    public VoteResult getResult() {
        return result;
    }
}
