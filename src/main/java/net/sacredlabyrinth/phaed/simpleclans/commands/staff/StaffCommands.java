package net.sacredlabyrinth.phaed.simpleclans.commands.staff;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import net.sacredlabyrinth.phaed.simpleclans.*;
import net.sacredlabyrinth.phaed.simpleclans.commands.ClanInput;
import net.sacredlabyrinth.phaed.simpleclans.commands.ClanPlayerInput;
import net.sacredlabyrinth.phaed.simpleclans.events.PlayerHomeSetEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.PlayerResetKdrEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.ReloadEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.TagChangeEvent;
import net.sacredlabyrinth.phaed.simpleclans.language.LanguageResource;
import net.sacredlabyrinth.phaed.simpleclans.managers.ClanManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.PermissionsManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager;
import net.sacredlabyrinth.phaed.simpleclans.managers.StorageManager;
import net.sacredlabyrinth.phaed.simpleclans.ui.InventoryController;
import net.sacredlabyrinth.phaed.simpleclans.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.managers.SettingsManager.ConfigField.GLOBAL_FRIENDLY_FIRE;
import static org.bukkit.ChatColor.AQUA;
import static org.bukkit.ChatColor.RED;

@CommandAlias("%clan")
@Conditions("%basic_conditions")
public class StaffCommands extends BaseCommand {

    @Dependency
    private SimpleClans plugin;
    @Dependency
    private ClanManager cm;
    @Dependency
    private PermissionsManager permissions;
    @Dependency
    private SettingsManager settings;
    @Dependency
    private StorageManager storage;

    @Subcommand("%mod %place")
    @CommandPermission("simpleclans.mod.place")
    @CommandCompletion("@players @clans")
    @HelpSearchTags("move put")
    @Description("{@@command.description.place}")
    public void place(CommandSender sender, @Name("player") ClanPlayerInput cpInput, @Name("clan") ClanInput clanInput) {
        UUID uuid = cpInput.getClanPlayer().getUniqueId();
        ClanPlayer oldCp = cm.getClanPlayer(uuid);
        Clan newClan = clanInput.getClan();

        if (oldCp != null) {
            Clan oldClan = Objects.requireNonNull(oldCp.getClan());

            if (oldClan.equals(newClan)) {
                ChatBlock.sendMessage(sender, lang("player.already.in.this.clan", sender));
                return;
            }
            if (!oldClan.isPermanent() && oldClan.isLeader(uuid) && oldClan.getLeaders().size() <= 1) {
                ChatBlock.sendMessage(sender, RED + lang("you.cannot.move.the.last.leader", sender));
                return;
            } else {
                oldClan.addBb(oldCp.getName(), lang("0.has.resigned", oldCp.getName()));
                oldClan.removePlayerFromClan(uuid);
            }
        }

        ClanPlayer cp = cm.getCreateClanPlayer(uuid);

        newClan.addBb(lang("joined.the.clan", cp.getName()));
        cm.serverAnnounce(lang("has.joined", cp.getName(), newClan.getName()));
        newClan.addPlayerToClan(cp);
        plugin.getDiscordWebhookService().onStaffAction(sender, "Jogador movido para clã", newClan,
                cp.getName(), null, newClan.getName());
    }

    @Subcommand("%mod %modtag")
    @CommandPermission("simpleclans.mod.modtag")
    @Description("{@@command.description.modtag.other}")
    public void modtag(Player player, @Name("clan") ClanInput clanInput, @Single @Name("tag") String tag) {
        Clan clan = clanInput.getClan();
        TagChangeEvent event = new TagChangeEvent(player, clan, tag);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        tag = event.getNewTag();
        String cleanTag = Helper.cleanTag(tag);

        Optional<String> validationError = plugin.getTagValidator().validate(player, tag);
        if (validationError.isPresent()) {
            ChatBlock.sendMessage(player, validationError.get());
            return;
        }

        if (!cleanTag.equals(clan.getTag())) {
            ChatBlock.sendMessage(player, RED + lang("you.can.only.modify.the.color.and.case.of.the.tag",
                    player));
            return;
        }

        String oldColorTag = clan.getColorTag();
        clan.addBb(player.getName(), lang("tag.changed.to.0", ChatUtils.parseColors(tag)));
        clan.changeClanTag(tag);
        player.sendMessage(lang("0.tag.changed.to.1", player, clan.getTag(), tag));
        plugin.getDiscordWebhookService().onStaffAction(player, "Cor/formato da TAG alterado", clan,
                null, oldColorTag, tag);
    }

    @Subcommand("%admin %tag")
    @CommandPermission("simpleclans.admin.tag")
    @CommandCompletion("@clans @nothing")
    @Description("{@@command.description.admin.tag}")
    public void changeTag(Player player, @Name("clan") ClanInput clanInput, @Single @Name("tag") String newTag) {
        Clan clan = clanInput.getClan();
        String oldTag = clan.getTag();
        String oldColorTag = clan.getColorTag();

        TagChangeEvent event = new TagChangeEvent(player, clan, newTag);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        String inputTag = event.getNewTag();
        String cleanNew = Helper.cleanTag(inputTag);

        if (cleanNew.equals(oldTag)) {
            // same clean tag: color/case changes belong to /clan mod modtag
            ChatBlock.sendMessage(player, RED + lang("admin.tag.same.tag", player));
            return;
        }
        Optional<String> validationError = plugin.getTagValidator().validate(player, inputTag);
        if (validationError.isPresent()) {
            ChatBlock.sendMessage(player, validationError.get());
            return;
        }
        if (cm.isClan(cleanNew)) {
            ChatBlock.sendMessage(player, RED + lang("clan.with.this.tag.already.exists", player));
            return;
        }
        if (plugin.getTagReservationManager().isReservedForOther(cleanNew, null)) {
            ChatBlock.sendMessage(player, RED + lang("tag.reservation.reserved", player));
            return;
        }
        // lock the new tag so no concurrent creation/change can take it while the
        // database transaction runs; creation paths check this same lock
        if (!cm.lockTag(cleanNew)) {
            ChatBlock.sendMessage(player, RED + lang("admin.tag.change.in.progress", player));
            return;
        }

        // snapshot, on the main thread, of how every referencing clan must end up
        List<StorageManager.TagReferenceUpdate> referenceUpdates = new ArrayList<>();
        for (Clan other : cm.getClans()) {
            if (other.getTag().equals(oldTag)) {
                continue;
            }
            StorageManager.TagReferenceUpdate update = storage.buildTagReferenceUpdate(other, oldTag, cleanNew);
            if (update != null) {
                referenceUpdates.add(update);
            }
        }

        String newColorTag = ChatUtils.parseColors(inputTag);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = storage.changeClanTag(oldTag, cleanNew, newColorTag, referenceUpdates);
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!success) {
                        ChatBlock.sendMessage(player, RED + lang("admin.tag.change.failed", player));
                        return;
                    }
                    applyTagChangeInMemory(clan, oldTag, cleanNew, inputTag, referenceUpdates);
                    ChatBlock.sendMessage(player, AQUA + lang("admin.tag.changed", player, clan.getName(),
                            ChatUtils.stripColors(oldColorTag), ChatUtils.stripColors(newColorTag)));
                    plugin.getDiscordWebhookService().onStaffTagChange(player, clan,
                            ChatUtils.stripColors(oldColorTag), ChatUtils.stripColors(newColorTag));
                } finally {
                    cm.unlockTag(cleanNew);
                }
            });
        });
    }

    private void applyTagChangeInMemory(Clan clan, String oldTag, String cleanNew, String inputTag,
                                        List<StorageManager.TagReferenceUpdate> referenceUpdates) {
        // let other servers drop the entry stored under the old tag before it changes
        plugin.getProxyManager().sendDelete(clan);

        cm.removeClan(oldTag);
        clan.setTag(cleanNew);
        clan.setColorTag(inputTag);
        cm.importClan(clan);

        // apply exactly the same reference values the transaction persisted
        for (StorageManager.TagReferenceUpdate update : referenceUpdates) {
            Clan other = update.getClan();
            other.setPackedAllies(update.getPackedAllies());
            other.setPackedRivals(update.getPackedRivals());
            other.setFlags(update.getFlags());
        }

        // every cached member must point at the renamed clan (setClan refreshes cp.tag)
        for (ClanPlayer cp : cm.getAllClanPlayers()) {
            if (oldTag.equals(cp.getTag())) {
                cp.setClan(clan);
            }
        }

        // pending requests may reference the old tag as key or target - drop them
        plugin.getRequestManager().removeRequest(oldTag);

        // move clan-specific permissions to the new tag and reapply them
        permissions.renameClanPermissions(oldTag, cleanNew);
        permissions.updateClanPermissions(clan);

        // refresh display names / chat tags of online members immediately
        for (ClanPlayer cp : clan.getOnlineMembers()) {
            cm.updateDisplayName(cp.toPlayer());
        }

        // re-buffer the clan so any state pending in the periodic save is written
        // under the new tag, and broadcast the update to the proxy network
        storage.updateClan(clan, false);
    }

    @Subcommand("%admin %reload")
    @CommandPermission("simpleclans.admin.reload")
    @Description("{@@command.description.reload}")
    public void reload(CommandSender sender) {
        storage.saveModified();
        plugin.reloadConfig();
        LanguageResource.clearCache();
        settings.loadAndSave();
        storage.importFromDatabase();
        permissions.loadPermissions();

        for (Clan clan : cm.getClans()) {
            permissions.updateClanPermissions(clan);
        }
        plugin.getDiscordWebhookService().reload();
        Bukkit.getPluginManager().callEvent(new ReloadEvent(sender));

        ChatBlock.sendMessage(sender, AQUA + lang("configuration.reloaded", sender));
        plugin.getDiscordWebhookService().onStaffAction(sender, "Configuração recarregada", null, null, null, null);
    }

    @Subcommand("%mod %home %set")
    @CommandPermission("simpleclans.mod.home")
    @CommandCompletion("@clans")
    @Description("{@@command.description.mod.home.set}")
    public void homeSet(Player player, ClanPlayer cp, @Name("clan") ClanInput clanInput) {
        Location loc = player.getLocation();
        Clan clan = clanInput.getClan();

        PlayerHomeSetEvent event = new PlayerHomeSetEvent(clan, cp, loc);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        clan.setHomeLocation(loc);
        ChatBlock.sendMessage(player, AQUA + lang("hombase.mod.set", player, clan.getName()) + " " +
                ChatColor.YELLOW + Helper.toLocationString(loc));
        plugin.getDiscordWebhookService().onStaffAction(player, "Base do clã definida (staff)", clan,
                null, null, Helper.toLocationString(loc));
    }

    @Subcommand("%mod %home %tp")
    @CommandCompletion("@clans:has_home")
    @CommandPermission("simpleclans.mod.hometp")
    @Description("{@@command.description.mod.home.tp}")
    public void homeTp(Player player, @Name("clan") @Conditions("can_teleport") ClanInput clan) {
        plugin.getTeleportManager().teleportToHome(player, clan.getClan());
    }

    @Subcommand("%mod %ban")
    @CommandPermission("simpleclans.mod.ban")
    @CommandCompletion("@players")
    @Description("{@@command.description.ban}")
    public void ban(CommandSender sender, @Name("player") ClanPlayerInput player) {
        UUID uuid = player.getClanPlayer().getUniqueId();
        if (settings.isBanned(uuid)) {
            ChatBlock.sendMessage(sender, RED + lang("this.player.is.already.banned", sender));
            return;
        }

        cm.ban(uuid);
        ChatBlock.sendMessage(sender, AQUA + lang("player.added.to.banned.list", sender));

        Player pl = sender.getServer().getPlayer(uuid);
        if (pl != null) {
            ChatBlock.sendMessage(pl, AQUA + lang("you.banned", sender));
        }
        plugin.getDiscordWebhookService().onStaffAction(sender, "Jogador banido dos comandos de clã", null,
                player.getClanPlayer().getName(), null, null);
    }

    @Subcommand("%mod %unban")
    @CommandPermission("simpleclans.mod.ban")
    @CommandCompletion("@players")
    @Description("{@@command.description.unban}")
    public void unban(CommandSender sender, @Name("player") ClanPlayerInput player) {
        UUID uuid = player.getClanPlayer().getUniqueId();
        if (!settings.isBanned(uuid)) {
            ChatBlock.sendMessage(sender, RED + lang("this.player.is.not.banned", sender));
            return;
        }

        Player pl = Bukkit.getPlayer(uuid);
        if (pl != null) {
            ChatBlock.sendMessage(pl, AQUA + lang("you.have.been.unbanned.from.clan.commands", sender));
        }

        settings.removeBanned(uuid);
        ChatBlock.sendMessage(sender, AQUA + lang("player.removed.from.the.banned.list", sender));
        plugin.getDiscordWebhookService().onStaffAction(sender, "Jogador desbanido dos comandos de clã", null,
                player.getClanPlayer().getName(), null, null);
    }

    @Subcommand("%mod %globalff %allow")
    @CommandPermission("simpleclans.mod.globalff")
    @Description("{@@command.description.globalff.allow}")
    public void allowGlobalFf(CommandSender sender) {
        if (settings.is(GLOBAL_FRIENDLY_FIRE)) {
            ChatBlock.sendMessage(sender, AQUA + lang("global.friendly.fire.is.already.being.allowed", sender));
        } else {
            settings.set(GLOBAL_FRIENDLY_FIRE, true);
            ChatBlock.sendMessage(sender, AQUA + lang("global.friendly.fire.is.set.to.allowed", sender));
        }
    }

    @Subcommand("%mod %globalff %auto")
    @CommandPermission("simpleclans.mod.globalff")
    @Description("{@@command.description.globalff.auto}")
    public void autoGlobalFf(CommandSender sender) {
        if (!settings.is(GLOBAL_FRIENDLY_FIRE)) {
            ChatBlock.sendMessage(sender, AQUA +
                    lang("global.friendy.fire.is.already.being.managed.by.each.clan", sender));
        } else {
            settings.set(GLOBAL_FRIENDLY_FIRE, false);
            ChatBlock.sendMessage(sender, AQUA + lang("global.friendy.fire.is.now.managed.by.each.clan",
                    sender));
        }
    }

    @Subcommand("%mod %verify")
    @CommandPermission("simpleclans.mod.verify")
    @CommandCompletion("@clans:unverified")
    @Description("{@@command.description.mod.verify}")
    public void verify(CommandSender sender, @Name("clan") ClanInput clan) {
        Clan clanInput = clan.getClan();

        if (!clanInput.isVerified()) {
            clanInput.verifyClan();
            clanInput.addBb(sender.getName(), lang("clan.0.has.been.verified", clanInput.getName()));
            ChatBlock.sendMessage(sender, AQUA + lang("the.clan.has.been.verified", sender));
            plugin.getDiscordWebhookService().onStaffAction(sender, "Clã verificado", clanInput, null, null, null);
        } else {
            ChatBlock.sendMessage(sender, RED + lang("the.clan.is.already.verified", sender));
        }
    }

    @Subcommand("%admin %purge")
    @CommandPermission("simpleclans.admin.purge")
    @CommandCompletion("@players")
    @Description("{@@command.description.purge}")
    public void purge(CommandSender sender, @Name("player") ClanPlayerInput player) {
        Player onlinePlayer = player.getClanPlayer().toPlayer();
        if (onlinePlayer != null && InventoryController.isRegistered(onlinePlayer)) {
            onlinePlayer.closeInventory();
        }

        Clan clan = player.getClanPlayer().getClan();
        if (clan != null && clan.getMembers().size() == 1) {
            clan.disband(sender, false, false);
        }
        cm.deleteClanPlayer(player.getClanPlayer());
        ChatBlock.sendMessage(sender, AQUA + lang("player.purged", sender));
        plugin.getDiscordWebhookService().onStaffAction(sender, "Dados de jogador purgados", clan,
                player.getClanPlayer().getName(), null, null);
    }

    @Subcommand("%mod %kick")
    @Description("{@@command.description.mod.kick}")
    @CommandPermission("simpleclans.mod.kick")
    @CommandCompletion("@all_non_leaders|@all_leaders")
    public void kick(CommandSender sender, @Conditions("clan_member") @Name("player") ClanPlayerInput cp) {
        ClanPlayer clanPlayer = cp.getClanPlayer();
        Clan clan = Objects.requireNonNull(clanPlayer.getClan());
        if (clanPlayer.isLeader() && clan.getLeaders().size() == 1) {
            ChatBlock.sendMessageKey(sender, "cannot.kick.last.leader");
            return;
        }

        clan.addBb(sender.getName(), lang("has.been.kicked.by", clanPlayer.getName(),
                sender.getName(), sender));
        clan.removePlayerFromClan(clanPlayer.getUniqueId());
        plugin.getDiscordWebhookService().onStaffAction(sender, "Jogador expulso do clã (staff)", clan,
                clanPlayer.getName(), null, null);
    }

    @Subcommand("%mod %disband")
    @CommandCompletion("@clans")
    @CommandPermission("simpleclans.mod.disband")
    @Description("{@@command.description.mod.disband}")
    public void disband(CommandSender sender, @Name("clan") ClanInput clan) {
        clan.getClan().disband(sender, true, true);
    }

    @Subcommand("%admin %promote")
    @CommandCompletion("@all_non_leaders")
    @CommandPermission("simpleclans.admin.promote")
    @Description("{@@command.description.admin.promote}")
    public void promote(CommandSender sender, @Conditions("online|clan_member") @Name("player") ClanPlayerInput promote) {
        ClanPlayer clanPlayer = promote.getClanPlayer();
        Player promotePl = Objects.requireNonNull(clanPlayer.toPlayer());
        if (!permissions.has(promotePl, "simpleclans.leader.promotable")) {
            ChatBlock.sendMessage(sender, RED + lang("the.player.does.not.have.the.permissions.to.lead.a.clan",
                    sender));
            return;
        }
        Clan clan = Objects.requireNonNull(clanPlayer.getClan());
        if (clan.isLeader(promotePl)) {
            ChatBlock.sendMessage(sender, RED + lang("the.player.is.already.a.leader", sender));
            return;
        }

        clan.addBb(sender.getName(), lang("promoted.to.leader", promotePl.getName()));
        clan.promote(promotePl.getUniqueId());
        ChatBlock.sendMessage(sender, AQUA + lang("player.successfully.promoted", sender));
        plugin.getDiscordWebhookService().onStaffAction(sender, "Jogador promovido a líder (staff)", clan,
                promotePl.getName(), null, null);
    }

    @Subcommand("%admin %demote")
    @CommandCompletion("@all_leaders")
    @CommandPermission("simpleclans.admin.demote")
    @Description("{@@command.description.admin.demote}")
    public void demote(CommandSender sender, @Conditions("clan_member") @Name("leader") ClanPlayerInput other) {
        ClanPlayer otherCp = other.getClanPlayer();
        Clan clan = Objects.requireNonNull(otherCp.getClan());

        if (!otherCp.isLeader()) {
            ChatBlock.sendMessage(sender, RED + lang("player.is.not.a.leader", sender));
            return;
        }

        if (clan.getLeaders().size() == 1 && !clan.isPermanent()) {
            ChatBlock.sendMessage(sender, RED + lang("you.cannot.demote.the.last.leader", sender));
            return;
        }
        clan.demote(otherCp.getUniqueId());
        clan.addBb(sender.getName(), lang("demoted.back.to.member", otherCp.getName()));
        ChatBlock.sendMessage(sender, AQUA + lang("player.successfully.demoted", sender));
        plugin.getDiscordWebhookService().onStaffAction(sender, "Líder rebaixado a membro (staff)", clan,
                otherCp.getName(), null, null);
    }

    @Subcommand("%admin %resetkdr %everyone")
    @CommandPermission("simpleclans.admin.resetkdr")
    @Description("{@@command.description.resetkdr.everyone}")
    public void resetKdr(CommandSender sender) {
        for (ClanPlayer cp : cm.getAllClanPlayers()) {
            PlayerResetKdrEvent event = new PlayerResetKdrEvent(cp);
            Bukkit.getServer().getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                cm.resetKdr(cp);
            }
        }
        ChatBlock.sendMessage(sender, RED + lang("you.have.reseted.kdr.of.all.players", sender));
        plugin.getDiscordWebhookService().onStaffAction(sender, "KDR de todos os jogadores resetado", null,
                null, null, null);
    }

    @Subcommand("%admin %resetkdr")
    @CommandCompletion("@players")
    @CommandPermission("simpleclans.admin.resetkdr")
    @Description("{@@command.description.resetkdr.player}")
    public void resetKdr(CommandSender sender, @Name("player") ClanPlayerInput clanPlayer) {
        ClanPlayer cp = clanPlayer.getClanPlayer();
        PlayerResetKdrEvent event = new PlayerResetKdrEvent(cp);
        Bukkit.getServer().getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            cm.resetKdr(cp);
            ChatBlock.sendMessage(sender, RED + lang("you.have.reseted.0.kdr", sender, cp.getName()));
            plugin.getDiscordWebhookService().onStaffAction(sender, "KDR de jogador resetado", cp.getClan(),
                    cp.getName(), null, null);
        }
    }

    @Subcommand("%admin %permanent")
    @CommandCompletion("@clans")
    @CommandPermission("simpleclans.admin.permanent")
    @Description("{@@command.description.admin.permanent}")
    public void togglePermanent(CommandSender sender, @Name("clan") ClanInput clanInput) {
        Clan clan = clanInput.getClan();
        boolean permanent = !clan.isPermanent();
        clan.setPermanent(permanent);
        clan.addBb(sender.getName(), lang((permanent) ? "permanent.status.enabled" : "permanent.status.disabled", sender.getName()));
        ChatBlock.sendMessage(sender, AQUA + lang("you.have.toggled.permanent.status", sender, clan.getName()));
        plugin.getDiscordWebhookService().onStaffAction(sender, "Status permanente alterado", clan,
                null, String.valueOf(!permanent), String.valueOf(permanent));
    }

    @Subcommand("%mod %rename")
    @CommandCompletion("@clans @nothing")
    @CommandPermission("simpleclans.mod.rename")
    @Description("{@@command.description.mod.rename}")
    public void rename(CommandSender sender, @Name("clan") ClanInput clanInput, @Name("name") String clanName) {
        Clan clan = clanInput.getClan();
        String oldName = clan.getName();
        clan.setName(clanName);
        storage.updateClan(clan);

        ChatBlock.sendMessageKey(sender, "you.have.successfully.renamed.the.clan", clanName);
        plugin.getDiscordWebhookService().onStaffAction(sender, "Nome do clã alterado (staff)", clan,
                null, oldName, clanName);
    }

    @Subcommand("%mod %locale")
    @CommandPermission("simpleclans.mod.locale")
    @Description("{@@command.description.mod.locale}")
    @CommandCompletion("@locales")
    public void locale(CommandSender sender, @Name("player") ClanPlayerInput input, @Values("@locales") @Name("locale") @Single String locale) {
        ClanPlayer cp = input.getClanPlayer();
        cp.setLocale(Helper.forLanguageTag(locale.replace("_", "-")));
        plugin.getStorageManager().updateClanPlayer(cp);

        ChatBlock.sendMessage(sender, lang("locale.has.been.changed"));
    }
}
