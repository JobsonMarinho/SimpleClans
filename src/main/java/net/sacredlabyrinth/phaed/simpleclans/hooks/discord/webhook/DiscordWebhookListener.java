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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates SimpleClans' own Bukkit events into Discord embeds on the
 * {@code alerts} webhook. Every handler runs at MONITOR priority after
 * cancellation checks, so only actions that really happened are logged.
 */
public class DiscordWebhookListener implements Listener {

    private final SimpleClans plugin;

    public DiscordWebhookListener(@NotNull SimpleClans plugin) {
        this.plugin = plugin;
    }

    private @Nullable DiscordWebhookService service() {
        return plugin.getDiscordWebhookService();
    }

    private @NotNull String tagOf(@NotNull Clan clan) {
        return clan.getTag().toUpperCase();
    }

    private void playerFields(@NotNull DiscordEmbed embed, @NotNull ClanPlayer cp) {
        embed.field("Jogador", cp.getName());
        UUID uuid = cp.getUniqueId();
        if (uuid != null) {
            embed.field("UUID", uuid.toString());
        }
        DiscordWebhookService service = service();
        if (service != null) {
            embed.thumbnail(service.getAvatarProvider().avatarUrl(uuid, cp.getName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanCreate(CreateClanEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Clan clan = event.getClan();
        DiscordEmbed embed = DiscordEmbed.of("🏰 Clã criado", DiscordEmbed.COLOR_GREEN)
                .field("Clã", clan.getName())
                .field("TAG", tagOf(clan))
                .footer("SimpleClans • Clãs");
        List<ClanPlayer> leaders = clan.getLeaders();
        UUID founderUuid = null;
        if (!leaders.isEmpty()) {
            ClanPlayer founder = leaders.get(0);
            founderUuid = founder.getUniqueId();
            embed.field("Líder", founder.getName());
            if (founderUuid != null) {
                embed.field("UUID", founderUuid.toString());
            }
            embed.thumbnail(service.getAvatarProvider().avatarUrl(founderUuid, founder.getName()));
        }
        service.alert("clan-created", founderUuid, embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClanDisband(DisbandClanEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Clan clan = event.getClan();
        CommandSender sender = event.getSender();
        DiscordEmbed embed = DiscordEmbed.of("💥 Clã deletado", DiscordEmbed.COLOR_RED)
                .field("Clã", clan.getName())
                .field("TAG", tagOf(clan))
                .field("Deletado por", sender != null ? sender.getName() : null)
                .footer("SimpleClans • Clãs");
        boolean byMember = sender instanceof Player && clan.isMember(((Player) sender).getUniqueId());
        if (byMember) {
            UUID uuid = ((Player) sender).getUniqueId();
            embed.field("UUID", uuid.toString());
            embed.thumbnail(service.getAvatarProvider().avatarUrl(uuid, sender.getName()));
            service.alert("clan-disbanded", uuid, embed);
        } else {
            // console or a non-member (staff) disbanding: audit channel
            service.staff("clan-disbanded", embed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoined(PlayerJoinedClanEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        ClanPlayer cp = event.getClanPlayer();
        Clan clan = event.getClan();
        DiscordEmbed embed = DiscordEmbed.of("✅ Jogador entrou no clã", DiscordEmbed.COLOR_GREEN)
                .footer("SimpleClans • Membros");
        playerFields(embed, cp);
        embed.field("Clã", clan.getName());
        embed.field("TAG", tagOf(clan));
        service.alert("player-joined", cp.getUniqueId(), embed);
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
        ClanPlayer cp = event.getClanPlayer();
        Clan clan = event.getClan();
        DiscordEmbed embed = DiscordEmbed.of("🚪 Jogador saiu do clã", DiscordEmbed.COLOR_YELLOW)
                .footer("SimpleClans • Membros");
        playerFields(embed, cp);
        embed.field("Clã", clan.getName());
        embed.field("TAG", tagOf(clan));
        service.alert("player-left", cp.getUniqueId(), embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPromote(PlayerPromoteEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        ClanPlayer cp = event.getClanPlayer();
        DiscordEmbed embed = DiscordEmbed.of("⬆️ Jogador promovido a líder", DiscordEmbed.COLOR_BLUE)
                .footer("SimpleClans • Membros");
        playerFields(embed, cp);
        embed.field("Clã", event.getClan().getName());
        embed.field("TAG", tagOf(event.getClan()));
        service.alert("promote", cp.getUniqueId(), embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDemote(PlayerDemoteEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        ClanPlayer cp = event.getClanPlayer();
        DiscordEmbed embed = DiscordEmbed.of("⬇️ Líder rebaixado a membro", DiscordEmbed.COLOR_YELLOW)
                .footer("SimpleClans • Membros");
        playerFields(embed, cp);
        embed.field("Clã", event.getClan().getName());
        embed.field("TAG", tagOf(event.getClan()));
        service.alert("demote", cp.getUniqueId(), embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAllyAdd(AllyClanAddEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        DiscordEmbed embed = DiscordEmbed.of("🤝 Aliança criada", DiscordEmbed.COLOR_GREEN)
                .field("Clã", event.getClanFirst().getName())
                .field("TAG", tagOf(event.getClanFirst()))
                .field("Clã aliado", event.getClanSecond().getName())
                .field("TAG aliada", tagOf(event.getClanSecond()))
                .footer("SimpleClans • Alianças");
        service.alert("ally-added", null, embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAllyRemove(AllyClanRemoveEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        DiscordEmbed embed = DiscordEmbed.of("💔 Aliança desfeita", DiscordEmbed.COLOR_YELLOW)
                .field("Clã", event.getClanFirst().getName())
                .field("TAG", tagOf(event.getClanFirst()))
                .field("Ex-aliado", event.getClanSecond().getName())
                .field("TAG do ex-aliado", tagOf(event.getClanSecond()))
                .footer("SimpleClans • Alianças");
        service.alert("ally-removed", null, embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRivalAdd(RivalClanAddEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        DiscordEmbed embed = DiscordEmbed.of("⚔️ Rivalidade criada", DiscordEmbed.COLOR_RED)
                .field("Clã", event.getClanFirst().getName())
                .field("TAG", tagOf(event.getClanFirst()))
                .field("Rival", event.getClanSecond().getName())
                .field("TAG rival", tagOf(event.getClanSecond()))
                .footer("SimpleClans • Rivalidades");
        service.alert("rival-added", null, embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRivalRemove(RivalClanRemoveEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        DiscordEmbed embed = DiscordEmbed.of("🕊️ Rivalidade encerrada", DiscordEmbed.COLOR_GREEN)
                .field("Clã", event.getClanFirst().getName())
                .field("TAG", tagOf(event.getClanFirst()))
                .field("Ex-rival", event.getClanSecond().getName())
                .field("TAG do ex-rival", tagOf(event.getClanSecond()))
                .footer("SimpleClans • Rivalidades");
        service.alert("rival-removed", null, embed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTagChange(TagChangeEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        Clan clan = event.getClan();
        DiscordEmbed embed = DiscordEmbed.of("🏷️ TAG alterada", DiscordEmbed.COLOR_BLUE)
                .field("Clã", clan.getName())
                .field("TAG anterior", ChatUtils.stripColors(clan.getColorTag()))
                .field("Nova TAG", ChatUtils.stripColors(event.getNewTag()))
                .field("Alterada por", event.getPlayer().getName())
                .footer("SimpleClans • Clãs");
        service.alert("tag-changed", event.getPlayer().getUniqueId(), embed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHomeSet(PlayerHomeSetEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        DiscordEmbed embed = DiscordEmbed.of("🏠 Base do clã definida", DiscordEmbed.COLOR_BLUE)
                .field("Clã", event.getClan().getName())
                .field("TAG", tagOf(event.getClan()))
                .field("Definida por", event.getClanPlayer() != null ? event.getClanPlayer().getName() : null)
                .footer("SimpleClans • Configurações do clã");
        service.alert("home-set", event.getClanPlayer() != null ? event.getClanPlayer().getUniqueId() : null, embed);
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
        if (request.getType() == ClanRequest.INVITE) {
            DiscordEmbed embed = DiscordEmbed.of("✉️ Convite enviado", DiscordEmbed.COLOR_BLUE)
                    .field("Convidado", request.getTarget())
                    .field("Convidado por", requester != null ? requester.getName() : null)
                    .field("Clã", request.getClan().getName())
                    .field("TAG", tagOf(request.getClan()))
                    .footer("SimpleClans • Convites");
            service.alert("invite-sent", requester != null ? requester.getUniqueId() : null, embed);
        } else if (request.getType() == ClanRequest.CREATE_ALLY) {
            DiscordEmbed embed = DiscordEmbed.of("🤝 Proposta de aliança enviada", DiscordEmbed.COLOR_BLUE)
                    .field("De", request.getClan().getName())
                    .field("Para", request.getTarget())
                    .field("Proposta por", requester != null ? requester.getName() : null)
                    .footer("SimpleClans • Alianças");
            service.alert("ally-requested", requester != null ? requester.getUniqueId() : null, embed);
        }
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
            DiscordEmbed embed = DiscordEmbed.of(
                            accepted ? "📩 Convite aceito" : "📭 Convite recusado",
                            accepted ? DiscordEmbed.COLOR_GREEN : DiscordEmbed.COLOR_YELLOW)
                    .field("Convidado", request.getTarget())
                    .field("Clã", request.getClan().getName())
                    .field("TAG", tagOf(request.getClan()))
                    .footer("SimpleClans • Convites");
            service.alert(accepted ? "invite-accepted" : "invite-denied", null, embed);
        } else if (request.getType() == ClanRequest.RENAME && request.getDenies().isEmpty()) {
            // leaders approved the rename; the clan already carries the new name
            DiscordEmbed embed = DiscordEmbed.of("📛 Nome do clã alterado", DiscordEmbed.COLOR_BLUE)
                    .field("Clã", request.getClan().getName())
                    .field("TAG", tagOf(request.getClan()))
                    .field("Novo nome", request.getTarget())
                    .footer("SimpleClans • Clãs");
            service.alert("name-changed", null, embed);
        }
        // joining/ally results already produce their own dedicated events
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWarStart(WarStartEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        String clans = event.getWar().getClans().stream()
                .map(Clan::getName)
                .collect(Collectors.joining(" x "));
        DiscordEmbed embed = DiscordEmbed.of("🔥 Guerra iniciada", DiscordEmbed.COLOR_RED)
                .field("Clãs", clans, false)
                .footer("SimpleClans • Guerras");
        service.alert("war-started", null, embed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWarEnd(WarEndEvent event) {
        DiscordWebhookService service = service();
        if (service == null) {
            return;
        }
        String clans = event.getWar().getClans().stream()
                .map(Clan::getName)
                .collect(Collectors.joining(" x "));
        DiscordEmbed embed = DiscordEmbed.of("🏳️ Guerra encerrada", DiscordEmbed.COLOR_GREEN)
                .field("Clãs", clans, false)
                .field("Motivo", event.getReason() != null ? event.getReason().name() : null)
                .footer("SimpleClans • Guerras");
        service.alert("war-ended", null, embed);
    }
}
