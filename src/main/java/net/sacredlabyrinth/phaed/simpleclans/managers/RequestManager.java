package net.sacredlabyrinth.phaed.simpleclans.managers;

import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.events.RequestEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.RequestFinishedEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.WarEndEvent;
import net.sacredlabyrinth.phaed.simpleclans.network.payload.RequestPayload;
import net.sacredlabyrinth.phaed.simpleclans.proxy.ProxyManager;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.*;
import static org.bukkit.ChatColor.RED;

/**
 * @author phaed
 */
public final class RequestManager {
    private final SimpleClans plugin;
    /**
     * Concurrent because the asker task runs off the main thread while network
     * messages mutate the map from the main one.
     */
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    /**
     *
     */
    public RequestManager() {
        plugin = SimpleClans.getInstance();
        askerTask();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean hasRequest(String tag) {
        return requests.containsKey(tag);
    }

    public void addDemoteRequest(ClanPlayer requester, String demotedName, Clan clan) {
        if (requests.containsKey(clan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("asking.for.the.demotion"), requester.getName(), demotedName);

        ClanPlayer demotedTp = plugin.getClanManager().getAnyClanPlayer(demotedName);

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayers(clan.getLeaders());
        acceptors.remove(demotedTp);

        Request req = new Request(ClanRequest.DEMOTE, acceptors, requester, demotedName, clan, msg);
        req.vote(requester.getName(), VoteResult.ACCEPT);
        register(req.getClan().getTag(), req);
        ask(req);
    }

    /**
     * This method asks <i>all</i> leaders about
     * some action <i>inside</i> their clan.
     * <p>
     * Example of possible requests:
     * </p>
     * <ul>
     *     <li>Disband request can be asked from all leaders</li>
     *     <li>Rename request can be asked from all leaders</li>
     *     <li>Promote request can be asked from all leaders</li>
     * </ul>
     *
     * <p>
     * Examples of incompatible requests:
     * </p>
     * <ul>
     *      <li>Demote request can be asked from all leaders,
     *      <b>except the demoted one.</b></li>
     *      <li>Invite request has to ask someone <b>outside</b> of leaders clan</li>
     * </ul>
     *
     * @param requester the clan player, who sent the request
     * @param request   the type of request, see: {@link ClanRequest}
     * @param target    the target which will be used in request processing
     * @param key       the language key that would be translated and send the message to all leaders
     * @param args      the language objects, requires in some language strings.
     * @throws IllegalArgumentException if passed incompatible request
     */
    public void requestAllLeaders(@NotNull ClanPlayer requester, @NotNull ClanRequest request,
                                  @NotNull String target, @NotNull String key, @Nullable Object... args) {
        if (request.equals(ClanRequest.INVITE) || request.equals(ClanRequest.DEMOTE)) {
            throw new IllegalArgumentException("Unsupported request: " + request.name());
        }

        Clan clan = requester.getClan();
        if (clan == null || requests.containsKey(clan.getTag())) {
            return;
        }

        String msg = lang(key, args);
        List<ClanPlayer> acceptors = Helper.stripOffLinePlayers(clan.getLeaders());

        Request req = new Request(request, acceptors, requester, target, clan, msg);
        register(clan.getTag(), req);
        req.vote(requester.getName(), VoteResult.ACCEPT);

        ask(req);
    }

    /**
     * Add a member invite request
     *
     * @param requester   the requester
     * @param invitedName the invited Player
     * @param clan        the Clan
     */
    public void addInviteRequest(ClanPlayer requester, String invitedName, Clan clan) {
        addInviteRequest(requester, invitedName, clan, null);
    }

    /**
     * Add a member invite request for a player who may be on another server.
     *
     * @param requester        the requester
     * @param invitedName      the invited player's name
     * @param clan             the Clan
     * @param invitedUniqueId  the invited player's UUID, when the caller already
     *                         resolved it; required to invite someone who is not
     *                         connected to this server
     * @since 2.19.4
     */
    public void addInviteRequest(ClanPlayer requester, String invitedName, Clan clan,
                                 @Nullable UUID invitedUniqueId) {
        String key = invitedName.toLowerCase(Locale.ROOT);
        if (requests.containsKey(key)) {
            return;
        }
        UUID uniqueId = invitedUniqueId != null ? invitedUniqueId : resolveUniqueId(invitedName);
        if (uniqueId == null) {
            // without a UUID the invite could never be applied, here or anywhere
            return;
        }

        Player player = Bukkit.getPlayerExact(invitedName);
        String msg = lang("inviting.you.to.join", player, requester.getName(), clan.getName());
        Request req = new Request(ClanRequest.INVITE, null, requester, invitedName, clan, msg);
        req.setTargetUniqueId(uniqueId);
        register(key, req);
        ask(req);
    }

    private @Nullable UUID resolveUniqueId(@NotNull String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null) {
            return player.getUniqueId();
        }
        ClanPlayer cp = plugin.getClanManager().getAnyClanPlayer(playerName);
        if (cp != null && cp.getUniqueId() != null) {
            return cp.getUniqueId();
        }
        return plugin.getProxyManager().getRemoteUniqueId(playerName);
    }

    /**
     * Files a request under its key and replicates it, so the players sitting on
     * the other servers can answer it too.
     */
    private void register(@NotNull String key, @NotNull Request req) {
        req.setKey(key);
        requests.put(key, req);
        publish(req);
    }

    public void addWarStartRequest(ClanPlayer requester, Clan warClan, Clan requestingClan) {
        if (requests.containsKey(warClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.war"), requestingClan.getName(), ChatUtils.stripColors(warClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayers(warClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.START_WAR, acceptors, requester, warClan.getTag(), requestingClan, msg);
        register(req.getTarget(), req);
        ask(req);
    }

    public void addWarEndRequest(ClanPlayer requester, Clan warClan, Clan requestingClan) {
        if (requests.containsKey(warClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.to.end.the.war"), requestingClan.getName(), ChatUtils.stripColors(warClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayers(warClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.END_WAR, acceptors, requester, warClan.getTag(), requestingClan, msg);
        register(req.getTarget(), req);
        ask(req);
    }

    public void addAllyRequest(ClanPlayer requester, Clan allyClan, Clan requestingClan) {
        if (requests.containsKey(allyClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.an.alliance"), requestingClan.getName(), ChatUtils.stripColors(allyClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayers(allyClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.CREATE_ALLY, acceptors, requester, allyClan.getTag(), requestingClan, msg);
        register(req.getTarget(), req);
        ask(req);
    }

    public void addRivalryBreakRequest(ClanPlayer requester, Clan rivalClan, Clan requestingClan) {
        if (requests.containsKey(rivalClan.getTag())) {
            return;
        }
        String msg = MessageFormat.format(lang("proposing.to.end.the.rivalry"), requestingClan.getName(), ChatUtils.stripColors(rivalClan.getColorTag()));

        List<ClanPlayer> acceptors = Helper.stripOffLinePlayers(rivalClan.getLeaders());
        acceptors.remove(requester);

        Request req = new Request(ClanRequest.BREAK_RIVALRY, acceptors, requester, rivalClan.getTag(), requestingClan, msg);
        register(req.getTarget(), req);
        ask(req);
    }

    public void accept(ClanPlayer cp) {
        castVote(cp, VoteResult.ACCEPT);
    }

    public void deny(ClanPlayer cp) {
        castVote(cp, VoteResult.DENY);
    }

    /**
     * Records the player's answer.
     * <p>
     * If the request belongs to another server, the vote is relayed instead of
     * applied: the outcome always runs on a single server, which is what keeps
     * two servers from both adding the same member or both disbanding the clan.
     */
    private void castVote(@NotNull ClanPlayer cp, @NotNull VoteResult vote) {
        Request req = requests.get(cp.getTag());
        if (req != null) {
            if (req.isReplica()) {
                relayVote(req, cp.getName(), vote);
                return;
            }
            req.vote(cp.getName(), vote);
            processResults(req);
            return;
        }

        req = requests.get(cp.getCleanName());
        if (req == null) {
            return;
        }
        if (req.isReplica()) {
            relayVote(req, cp.getName(), vote);
            // the answer is given; drop our copy and let the owner announce the result
            requests.remove(cp.getCleanName());
            return;
        }
        processInvite(req, vote);
    }

    private void relayVote(@NotNull Request req, @NotNull String voter, @NotNull VoteResult vote) {
        String key = req.getKey();
        if (key != null) {
            plugin.getProxyManager().sendRequestVote(key, voter, vote);
        }
    }

    public void processInvite(Request req, VoteResult vote) {
        removeByKey(req);

        Clan clan = req.getClan();
        String invitedName = req.getTarget();
        // the invited player may be on another server, so we work off the UUID
        // recorded when the invite was opened rather than a local Player
        UUID invitedUniqueId = req.getTargetUniqueId();
        if (invitedUniqueId == null) {
            Player local = Bukkit.getPlayerExact(invitedName);
            if (local == null) {
                return;
            }
            invitedUniqueId = local.getUniqueId();
        }

        if (vote.equals(VoteResult.ACCEPT)) {
            ClanPlayer cp = plugin.getClanManager().getCreateClanPlayer(invitedUniqueId);
            int maxMembers = !clan.isVerified() ? plugin.getSettingsManager().getInt(CLAN_UNVERIFIED_MAX_MEMBERS) : plugin.getSettingsManager().getInt(CLAN_MAX_MEMBERS);

            if (maxMembers > 0 && maxMembers > clan.getSize()) {
                tell(invitedName, lang("accepted.invitation", clan.getName()));
                clan.addBb(lang("joined.the.clan", invitedName));
                plugin.getClanManager().serverAnnounce(lang("has.joined", invitedName, clan.getName()));
                clan.addPlayerToClan(cp);
            } else {
                tell(invitedName, lang("this.clan.has.reached.the.member.limit"));
            }
        } else {
            tell(invitedName, lang("denied.invitation", clan.getName()));
            clan.leaderAnnounce(RED + lang("membership.invitation", invitedName));
        }
    }

    /**
     * Delivers a line to a player wherever they are connected.
     */
    private void tell(@NotNull String playerName, @NotNull String message) {
        plugin.getProxyManager().sendMessage(playerName, ChatUtils.parseColors(message));
    }


    public void processResults(Request req) {
        Clan requestClan = req.getClan();
        ClanPlayer requester = req.getRequester();

        String target = req.getTarget();

        @Nullable
        Clan targetClan = plugin.getClanManager().getClan(target);

        ClanPlayer targetCp = plugin.getClanManager().getAnyClanPlayer(target);
        @Nullable
        UUID targetUuid = targetCp != null ? targetCp.getUniqueId() : null;

        List<String> accepts = req.getAccepts();
        List<String> denies = req.getDenies();

        switch (req.getType()) {
            case START_WAR:
                processStartWar(requester, requestClan, targetClan, accepts, denies);
                break;
            case END_WAR:
                processEndWar(requester, requestClan, targetClan, accepts, denies);
                break;
            case CREATE_ALLY:
                processCreateAlly(requester, requestClan, targetClan, accepts, denies);
                break;
            case BREAK_RIVALRY:
                processBreakRivalry(requester, requestClan, targetClan, accepts, denies);
                break;
            case DEMOTE:
            case PROMOTE:
                if (!req.votingFinished() || targetUuid == null) {
                    return;
                }
                target = requestClan.getTag();

                if (req.getType() == ClanRequest.DEMOTE) {
                    processDemote(req, requestClan, targetUuid, denies);
                }
                if (req.getType() == ClanRequest.PROMOTE) {
                    processPromote(req, requestClan, targetUuid, denies);
                }
                break;
            case DISBAND:
                if (!req.votingFinished()) {
                    return;
                }
                processDisband(requester, requestClan, denies);
                break;
            case RENAME:
                if (!req.votingFinished()) {
                    return;
                }

                processRename(req);
                break;
            default:
                return;
        }

        removeByKey(req);
        SimpleClans.getInstance().getServer().getPluginManager().callEvent(new RequestFinishedEvent(req));
        req.cleanVotes();
    }

    private void processRename(Request request) {
        if (request.getDenies().isEmpty()) {
            request.getClan().setName(request.getTarget());
        } else {
            String deniers = String.join(", ", request.getDenies());
            request.getClan().leaderAnnounce(RED + lang("rename.refused", deniers));
        }
    }

    private void processDisband(ClanPlayer requester, Clan requestClan, List<String> denies) {
        if (denies.isEmpty()) {
            requestClan.disband(requester.toPlayer(), true, false);
        } else {
            String deniers = String.join(", ", denies);
            requestClan.leaderAnnounce(RED + lang("clan.deletion", deniers));
        }
    }

    private void processPromote(Request req, Clan requestClan, UUID targetPlayer, List<String> denies) {
        String promotedName = req.getTarget();
        if (denies.isEmpty()) {
            requestClan.addBb(lang("leaders"), lang("promoted.to.leader", promotedName));
            requestClan.promote(targetPlayer);
        } else {
            String deniers = String.join(", ", denies);
            requestClan.leaderAnnounce(RED + lang("denied.the.promotion", deniers, promotedName));
        }
    }

    private void processDemote(Request req, Clan requestClan, UUID targetPlayer, List<String> denies) {
        String demotedName = req.getTarget();
        if (denies.isEmpty()) {
            requestClan.addBb(lang("leaders"), lang("demoted.back.to.member", demotedName));
            requestClan.demote(targetPlayer);
        } else {
            String deniers = String.join(", ", denies);
            requestClan.leaderAnnounce(
                    RED + lang("denied.demotion", deniers, demotedName));
        }
    }

    private void processBreakRivalry(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan,
                                     List<String> accepts, List<String> denies) {
        if (targetClan != null && requestClan != null) {
            if (!accepts.isEmpty()) {
                requestClan.removeRival(targetClan);
                targetClan.addBb(requester.getName(), lang("broken.the.rivalry", accepts.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("broken.the.rivalry.with", requester.getName(), targetClan.getName()));
            } else {
                targetClan.addBb(requester.getName(), lang("denied.to.make.peace", denies.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("peace.agreement.denied", targetClan.getName()));
            }
        }
    }

    private void processCreateAlly(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan,
                                   List<String> accepts, List<String> denies) {
        if (targetClan != null && requestClan != null) {
            if (!accepts.isEmpty()) {
                requestClan.addAlly(targetClan);

                targetClan.addBb(requester.getName(), lang("accepted.an.alliance", accepts.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("created.an.alliance", requester.getName(), targetClan.getName()));
            } else {
                targetClan.addBb(requester.getName(), lang("denied.an.alliance", denies.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("the.alliance.was.denied", targetClan.getName()));
            }
        }
    }

    private void processEndWar(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan, List<String> accepts,
                               List<String> denies) {
        if (requestClan != null && targetClan != null) {
            if (!accepts.isEmpty()) {
                War war = plugin.getProtectionManager().getWar(requestClan, targetClan);
                plugin.getProtectionManager().removeWar(war, WarEndEvent.Reason.REQUEST);
                requestClan.removeWarringClan(targetClan);
                targetClan.removeWarringClan(requestClan);

                targetClan.addBb(requester.getName(), lang("you.are.no.longer.at.war", accepts.get(0), requestClan.getColorTag()));
                requestClan.addBb(requester.getName(), lang("you.are.no.longer.at.war", requestClan.getName(), targetClan.getColorTag()));
            } else {
                targetClan.addBb(requester.getName(), lang("denied.war.end", denies.get(0), requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("end.war.denied", targetClan.getName()));
            }
        }
    }

    private void processStartWar(ClanPlayer requester, Clan requestClan, @Nullable Clan targetClan,
                                 List<String> accepts, List<String> denies) {
        if (requestClan != null && targetClan != null) {
            if (!accepts.isEmpty()) {
                plugin.getProtectionManager().addWar(requester, requestClan, targetClan);
            } else {
                targetClan.addBb(requester.getName(), lang("denied.war.req", denies.get(0),
                        requestClan.getName()));
                requestClan.addBb(requester.getName(), lang("end.war.denied",
                        targetClan.getName()));
            }
        }
    }

    /**
     * End a pending request prematurely
     *
     * @param playerName the Player signing off
     */
    public void endPendingRequest(String playerName) {
        for (Request req : new LinkedList<>(requests.values())) {
            for (ClanPlayer cp : req.getAcceptors()) {
                if (cp.getName().equalsIgnoreCase(playerName)) {
                    req.getClan().leaderAnnounce(lang("signed.off.request.cancelled", RED + playerName, req.getType()));
                    requests.remove(req.getClan().getTag());
                    cancelOnNetwork(req);
                    break;
                }
            }
        }

    }

    public void removeRequest(@NotNull String keyOrTarget) {
        Iterator<Map.Entry<String, Request>> iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Request> entry = iterator.next();
            final String requester = entry.getKey();
            final String target = entry.getValue().getTarget();
            if (keyOrTarget.equals(requester) || keyOrTarget.equals(target)) {
                entry.getValue().cleanVotes();
                iterator.remove();
                cancelOnNetwork(entry.getValue());
            }
        }
    }

    /**
     * Starts the task that asks for the votes of all requests
     */
    public void askerTask() {
        new BukkitRunnable() {

            @Override
            public void run() {
                for (Iterator<Map.Entry<String, Request>> iter = requests.entrySet().iterator(); iter.hasNext(); ) {
                    Request req = iter.next().getValue();

                    if (req == null) {
                        continue;
                    }

                    if (req.isReplica()) {
                        // the owner drives the lifecycle and will tell us when it ends
                        continue;
                    }

                    if (req.reachedRequestLimit()) {
                        iter.remove();
                        cancelOnNetwork(req);
                        continue;
                    }

                    ask(req);
                    req.incrementAskCount();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0, plugin.getSettingsManager().getSeconds(REQUEST_FREQUENCY));
    }

    // ------------------------------------------------------------------
    // network
    // ------------------------------------------------------------------

    /**
     * Sends a request we own to the other servers, so their players can answer it.
     */
    private void publish(@NotNull Request req) {
        ProxyManager proxy = plugin.getProxyManager();
        if (!proxy.isEnabled() || req.isReplica()) {
            return;
        }
        String key = req.getKey();
        Clan clan = req.getClan();
        ClanPlayer requester = req.getRequester();
        if (key == null || clan == null || requester == null || requester.getUniqueId() == null) {
            return;
        }
        List<UUID> acceptors = new ArrayList<>();
        for (ClanPlayer cp : req.getAcceptors()) {
            if (cp.getUniqueId() != null) {
                acceptors.add(cp.getUniqueId());
            }
        }
        proxy.sendRequest(new RequestPayload(key, req.getType(), clan.getTag(), requester.getUniqueId(),
                req.getTarget(), req.getTargetUniqueId(), req.getMsg(), acceptors));
    }

    /**
     * Rebuilds a request opened on another server.
     * <p>
     * The replica never runs the outcome and never prompts anybody: it exists so
     * that a player typing {@code /clan accept} here can have their answer
     * validated and relayed to the owner.
     *
     * @since 2.19.4
     */
    public void importRemoteRequest(@NotNull String source, @NotNull RequestPayload payload) {
        Request existing = requests.get(payload.getKey());
        if (existing != null && !existing.isReplica()) {
            // both servers opened a request under the same key at almost the same
            // time. Ours wins here; theirs expires on its own asker timeout, and
            // overwriting would strand the votes we are already collecting.
            SimpleClans.debug("Ignoring a remote request colliding with our own: " + payload.getKey());
            return;
        }

        Clan clan = plugin.getClanManager().getClan(payload.getClanTag());
        UUID requesterUniqueId = payload.parseRequesterUniqueId();
        if (clan == null || requesterUniqueId == null) {
            SimpleClans.debug("Dropping a remote request for an unknown clan: " + payload.getClanTag());
            return;
        }
        ClanPlayer requester = plugin.getClanManager().getAnyClanPlayer(requesterUniqueId);
        if (requester == null) {
            SimpleClans.debug("Dropping a remote request from an unknown player");
            return;
        }

        List<ClanPlayer> acceptors = new ArrayList<>();
        for (UUID uuid : payload.parseAcceptorUniqueIds()) {
            ClanPlayer cp = plugin.getClanManager().getAnyClanPlayer(uuid);
            if (cp != null) {
                acceptors.add(cp);
            }
        }

        Request req = new Request(payload.getType(), acceptors.isEmpty() ? null : acceptors, requester,
                payload.getTarget(), clan, payload.getMessage());
        req.setKey(payload.getKey());
        req.setTargetUniqueId(payload.parseTargetUniqueId());
        req.setOwner(source);
        requests.put(payload.getKey(), req);
        SimpleClans.debug(String.format("Imported %s request %s from %s",
                payload.getType(), payload.getKey(), source));
    }

    /**
     * Applies a vote cast on another server. Only meaningful on the owner.
     *
     * @since 2.19.4
     */
    public void applyRemoteVote(@NotNull String key, @NotNull String voter, @NotNull VoteResult vote) {
        Request req = requests.get(key);
        if (req == null || req.isReplica()) {
            return;
        }
        if (req.getType() == ClanRequest.INVITE) {
            processInvite(req, vote);
            return;
        }
        req.vote(voter, vote);
        processResults(req);
    }

    /**
     * Drops a replica because the owner finished with it.
     *
     * @since 2.19.4
     */
    public void cancelReplica(@NotNull String key) {
        Request req = requests.get(key);
        if (req == null || !req.isReplica()) {
            return;
        }
        requests.remove(key);
        req.cleanVotes();
        SimpleClans.debug("Cancelled replica request " + key);
    }

    /**
     * Removes a finished request and tells the replicas to drop theirs.
     */
    private void removeByKey(@NotNull Request req) {
        String key = req.getKey();
        if (key != null) {
            requests.remove(key);
        } else {
            // requests built before this server learned about keys
            requests.remove(req.getTarget().toLowerCase(Locale.ROOT));
        }
        cancelOnNetwork(req);
    }

    private void cancelOnNetwork(@NotNull Request req) {
        String key = req.getKey();
        if (key != null && !req.isReplica()) {
            plugin.getProxyManager().sendRequestCancel(key);
        }
    }

    /**
     * Asks a request to players for votes
     *
     * @param req the Request
     */
    public void ask(final Request req) {
        if (req.isReplica()) {
            // the owner prompts everyone, on every server; a replica that also
            // asked would show the same question twice
            return;
        }
        String message = lang("request.message", req.getClan().getColorTag(), req.getMsg());
        List<String> recipients = new ArrayList<>();
        if (req.getType() == ClanRequest.INVITE) {
            recipients.add(req.getTarget());
        } else {
            for (ClanPlayer cp : req.getAcceptors()) {
                if (cp.getVote() == null) {
                    recipients.add(cp.getName());
                }
            }
        }

        String plainText = null;
        for (String name : recipients) {
            if (name == null) {
                continue;
            }
            Player recipient = Bukkit.getPlayerExact(name);
            if (recipient != null) {
                recipient.spigot().sendMessage(ChatUtils.toBaseComponents(recipient, message));
                continue;
            }
            // on another server: click and hover events cannot travel as plain
            // text, so flatten the very same message into legacy colours
            if (plainText == null) {
                plainText = TextComponent.toLegacyText(ChatUtils.toBaseComponents(null, message));
            }
            plugin.getProxyManager().sendMessage(name, plainText);
        }

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new RequestEvent(req)));
    }
}
