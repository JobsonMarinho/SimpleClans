package net.sacredlabyrinth.phaed.simpleclans.hooks.discord.webhook;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.ClanRequest;
import net.sacredlabyrinth.phaed.simpleclans.Request;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.events.*;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates SimpleClans' own Bukkit events into webhook events. All embed
 * content lives in discord.yml as templates; this listener only fills the
 * {placeholder} variables. Handlers run at MONITOR priority after cancellation
 * checks, so only actions that really happened are logged.
 */
public class DiscordWebhookListener implements Listener {

    private final SimpleClans plugin;

    public DiscordWebhookListener(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
    }

    private @Nullable DiscordWebhookService service() {
        return plugin.getDiscordWebhookService();
    }

    private void clanVars(@NotNull Map<String, String> vars, @NotNull Clan clan) {
        vars.put("clan", clan.getName());
        vars.put("tag", clan.getTag().toUpperCase());
    }

    private void actorVars(@NotNull DiscordWebhookService service, @NotNull Map<String, String> vars,
                           @NotNull ClanPlayer cp) {
        vars.put("player", cp.getName());
        UUID uuid = cp.getUniqueId();
        if (uuid != null) {
            vars.put("uuid", uuid.toString());
            vars.put("player_avatar_url", service.avatarUrl(uuid, cp.getName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanCreate(CreateClanEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Clan clan = event.getClan();
        Map<String, String> vars = service.newVars();
        clanVars(vars, clan);
        UUID founderUuid = null;
        List<ClanPlayer> leaders = clan.getLeaders();
        if (!leaders.isEmpty()) {
            ClanPlayer founder = leaders.get(0);
            founderUuid = founder.getUniqueId();
            actorVars(service, vars, founder);
        }
        service.fire("clan-created", founderUuid, vars);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanDisband(DisbandClanEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Clan clan = event.getClan();
        CommandSender sender = event.getSender();
        Map<String, String> vars = service.newVars();
        clanVars(vars, clan);
        boolean byMember = sender instanceof Player && clan.isMember(((Player) sender).getUniqueId());
        if (byMember) {
            UUID uuid = ((Player) sender).getUniqueId();
            vars.put("player", sender.getName());
            vars.put("uuid", uuid.toString());
            vars.put("player_avatar_url", service.avatarUrl(uuid, sender.getName()));
            service.fire("clan-disbanded", uuid, vars);
        } else {
            // console or a non-member (staff) disbanding: audit channel
            vars.put("staff", sender != null ? sender.getName() : "console");
            if (sender instanceof Player) {
                UUID uuid = ((Player) sender).getUniqueId();
                vars.put("staff_uuid", uuid.toString());
                vars.put("staff_avatar_url", service.avatarUrl(uuid, sender.getName()));
            }
            service.fire("clan-disbanded-staff", null, vars);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoined(PlayerJoinedClanEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Map<String, String> vars = service.newVars();
        actorVars(service, vars, event.getClanPlayer());
        clanVars(vars, event.getClan());
        service.fire("player-joined", event.getClanPlayer().getUniqueId(), vars);
    }

    /**
     * Fired by SimpleClans for any removal from a clan (resign, kick or move)
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLeft(PlayerKickedClanEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Map<String, String> vars = service.newVars();
        actorVars(service, vars, event.getClanPlayer());
        clanVars(vars, event.getClan());
        service.fire("player-left", event.getClanPlayer().getUniqueId(), vars);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPromote(PlayerPromoteEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Map<String, String> vars = service.newVars();
        actorVars(service, vars, event.getClanPlayer());
        clanVars(vars, event.getClan());
        service.fire("promote", event.getClanPlayer().getUniqueId(), vars);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDemote(PlayerDemoteEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Map<String, String> vars = service.newVars();
        actorVars(service, vars, event.getClanPlayer());
        clanVars(vars, event.getClan());
        service.fire("demote", event.getClanPlayer().getUniqueId(), vars);
    }

    private void twoClanVars(@NotNull DiscordWebhookService service, @NotNull String eventKey,
                             @NotNull Clan first, @NotNull Clan second) {
        Map<String, String> vars = service.newVars();
        clanVars(vars, first);
        vars.put("clan2", second.getName());
        vars.put("tag2", second.getTag().toUpperCase());
        service.fire(eventKey, null, vars);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAllyAdd(AllyClanAddEvent event) {
        DiscordWebhookService service = service();
        if (service != null) {
            twoClanVars(service, "ally-added", event.getClanFirst(), event.getClanSecond());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAllyRemove(AllyClanRemoveEvent event) {
        DiscordWebhookService service = service();
        if (service != null) {
            twoClanVars(service, "ally-removed", event.getClanFirst(), event.getClanSecond());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRivalAdd(RivalClanAddEvent event) {
        DiscordWebhookService service = service();
        if (service != null) {
            twoClanVars(service, "rival-added", event.getClanFirst(), event.getClanSecond());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRivalRemove(RivalClanRemoveEvent event) {
        DiscordWebhookService service = service();
        if (service != null) {
            twoClanVars(service, "rival-removed", event.getClanFirst(), event.getClanSecond());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTagChange(TagChangeEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Clan clan = event.getClan();
        Map<String, String> vars = service.newVars();
        clanVars(vars, clan);
        vars.put("old_tag", ChatUtils.stripColors(clan.getColorTag()));
        vars.put("new_tag", ChatUtils.stripColors(event.getNewTag()));
        vars.put("player", event.getPlayer().getName());
        vars.put("uuid", event.getPlayer().getUniqueId().toString());
        vars.put("player_avatar_url", service.avatarUrl(event.getPlayer().getUniqueId(), event.getPlayer().getName()));
        service.fire("tag-changed", event.getPlayer().getUniqueId(), vars);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHomeSet(PlayerHomeSetEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Map<String, String> vars = service.newVars();
        clanVars(vars, event.getClan());
        UUID uuid = null;
        if (event.getClanPlayer() != null) {
            uuid = event.getClanPlayer().getUniqueId();
            actorVars(service, vars, event.getClanPlayer());
        }
        service.fire("home-set", uuid, vars);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRequest(RequestEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Request request = event.getRequest();
        if (request == null || request.getClan() == null) {
            return;
        }
        ClanPlayer requester = request.getRequester();
        if (request.getType() != ClanRequest.INVITE && request.getType() != ClanRequest.CREATE_ALLY) {
            return;
        }
        Map<String, String> vars = service.newVars();
        clanVars(vars, request.getClan());
        vars.put("target", request.getTarget());
        UUID requesterUuid = null;
        if (requester != null) {
            requesterUuid = requester.getUniqueId();
            actorVars(service, vars, requester);
        }
        service.fire(request.getType() == ClanRequest.INVITE ? "invite-sent" : "ally-requested",
                requesterUuid, vars);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRequestFinished(RequestFinishedEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Request request = event.getRequest();
        if (request == null || request.getClan() == null) {
            return;
        }
        if (request.getType() == ClanRequest.INVITE) {
            boolean accepted = !request.getAccepts().isEmpty();
            Map<String, String> vars = service.newVars();
            clanVars(vars, request.getClan());
            vars.put("target", request.getTarget());
            service.fire(accepted ? "invite-accepted" : "invite-denied", null, vars);
        } else if (request.getType() == ClanRequest.RENAME && request.getDenies().isEmpty()) {
            // leaders approved the rename; the clan already carries the new name
            Map<String, String> vars = service.newVars();
            clanVars(vars, request.getClan());
            vars.put("new_value", request.getTarget());
            service.fire("name-changed", null, vars);
        }
        // joining/ally results already produce their own dedicated events
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWarStart(WarStartEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Map<String, String> vars = service.newVars();
        vars.put("clans", event.getWar().getClans().stream()
                .map(Clan::getName)
                .collect(Collectors.joining(" x ")));
        service.fire("war-started", null, vars);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWarEnd(WarEndEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Map<String, String> vars = service.newVars();
        vars.put("clans", event.getWar().getClans().stream()
                .map(Clan::getName)
                .collect(Collectors.joining(" x ")));
        if (event.getReason() != null) {
            vars.put("reason", event.getReason().name());
        }
        service.fire("war-ended", null, vars);
    }
}
