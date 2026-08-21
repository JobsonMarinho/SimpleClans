package net.sacredlabyrinth.phaed.simpleclans.ui;

import net.hypedmc.network.shared.bedrock.HypedBedrockAPI;
import net.hypedmc.network.shared.bedrock.SimpleFormBuilder;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.RankPermission;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.managers.PermissionsManager;
import net.sacredlabyrinth.phaed.simpleclans.ui.frames.ConfirmationFrame;
import net.sacredlabyrinth.phaed.simpleclans.ui.frames.WarningFrame;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Desenha qualquer {@link SCFrame} como formulário nativo do Bedrock, para quem
 * entra pelo celular ou console.
 *
 * A conversão é genérica: como todo menu do plugin passa pelo
 * {@link InventoryDrawer} e todo botão é um {@link SCComponent} que já carrega o
 * próprio listener, permissão e exigência de confirmação, um único ponto atende
 * os 24 frames — nenhum deles precisou ser alterado.
 *
 * Componente com listener vira botão; componente sem listener é decoração ou
 * informação e vira linha de texto no corpo do formulário, para não se perder a
 * lore que o jogador leria passando o mouse.
 *
 * As regras de clique são as mesmas do {@link InventoryController}: clã
 * verificado, permissão do rank e confirmação obrigatória. Frames de aviso e de
 * confirmação também passam por aqui, então continuam funcionando no Bedrock.
 *
 * O HypedNetwork (que carrega o Floodgate) é <b>softdepend</b>: sem ele
 * {@link #isBedrock(Player)} devolve {@code false} e o inventário abre como
 * sempre.
 */
public final class BedrockFrames {

    /**
     * Resolvido uma vez. Enquanto for {@code false} nenhuma classe da API é
     * tocada — a JVM só resolve uma referência na primeira instrução que a usa,
     * então servidor sem o HypedNetwork nunca vê NoClassDefFoundError.
     */
    private static final boolean AVAILABLE = isApiOnClasspath();

    private BedrockFrames() {
    }

    private static boolean isApiOnClasspath() {
        try {
            Class.forName("net.hypedmc.network.shared.bedrock.HypedBedrockAPI");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code true} se dá para mandar formulário para este jogador agora. */
    public static boolean isBedrock(@NotNull Player player) {
        return AVAILABLE && HypedBedrockAPI.isAvailable() && HypedBedrockAPI.isBedrock(player.getUniqueId());
    }

    /**
     * @return {@code true} se o jogador recebeu o formulário e o inventário
     *         <b>não</b> deve abrir.
     */
    public static boolean open(@NotNull SCFrame frame) {
        Player viewer = frame.getViewer();
        if (!isBedrock(viewer)) {
            return false;
        }
        // Montar um frame le clã, permissões e às vezes o banco. O InventoryDrawer
        // faz isso fora da main thread e aqui não é diferente: assumimos o menu
        // (devolvendo true) e desenhamos em seguida.
        SimpleClans plugin = SimpleClans.getInstance();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!draw(frame, viewer)) {
                    plugin.getLogger().warning(
                            "[SimpleClans] não foi possível enviar o formulário de " + frame.getTitle());
                }
            } catch (Throwable t) {
                // Formulário quebrado nunca deixa o jogador sem menu.
                plugin.getLogger().warning("[SimpleClans] formulário Bedrock falhou: " + t.getMessage());
            }
        });
        return true;
    }

    private static boolean draw(@NotNull SCFrame frame, @NotNull Player viewer) {
        frame.clear();
        frame.createComponents();

        List<SCComponent> components = new ArrayList<>(frame.getComponents());
        components.sort(Comparator.comparingInt(SCComponent::getSlot));

        List<String> info = new ArrayList<>();
        List<SCComponent> buttons = new ArrayList<>();
        for (SCComponent c : components) {
            if (c.getSlot() >= frame.getSize()) {
                continue;
            }
            if (hasAnyListener(c)) {
                buttons.add(c);
            } else {
                String line = describe(c);
                if (!line.isEmpty()) {
                    info.add(line);
                }
            }
        }

        if (buttons.isEmpty() && info.isEmpty()) {
            return false;
        }

        SimpleFormBuilder form = HypedBedrockAPI.simple(viewer.getUniqueId())
                .title(frame.getTitle())
                .content(String.join("\n", info));

        for (SCComponent c : buttons) {
            addButton(form, frame, viewer, c);
        }

        SCFrame parent = frame.getParent();
        if (parent != null) {
            form.button("§aVoltar", () -> InventoryDrawer.open(parent));
            form.onClose(() -> InventoryDrawer.open(parent));
        }
        return form.send();
    }

    /**
     * Um botão por tipo de clique com listener. O formulário tem um toque só,
     * então clique direito e shift viram botões próprios, rotulados — senão a
     * ação simplesmente não existiria no Bedrock.
     */
    private static void addButton(
            @NotNull SimpleFormBuilder form,
            @NotNull SCFrame frame,
            @NotNull Player viewer,
            @NotNull SCComponent c
    ) {
        String label = displayName(c.getItem());
        String lore = loreOf(c.getItem());
        // Todo ClickType que tenha listener, e não uma lista fixa: o "Desfazer
        // clã", por exemplo, está em DROP (tecla Q) e sumia do formulário.
        for (ClickType click : ClickType.values()) {
            if (c.getListener(click) == null) {
                continue;
            }
            String suffix = click == ClickType.LEFT ? "" : " §8(" + clickName(click) + ")";
            String text = label + suffix + (lore.isEmpty() ? "" : "\n§7" + lore);
            form.button(text, () -> run(frame, viewer, c, click));
        }
    }

    /**
     * Mesmas checagens do {@link InventoryController}, na mesma ordem: clã
     * verificado, permissão e confirmação. Cada recusa abre o frame de aviso
     * correspondente, que por sua vez também vira formulário.
     */
    private static void run(
            @NotNull SCFrame frame,
            @NotNull Player viewer,
            @NotNull SCComponent c,
            @NotNull ClickType click
    ) {
        Runnable listener = c.getListener(click);
        if (listener == null) {
            return;
        }
        if (c.isVerifiedOnly(click) && !isClanVerified(viewer)) {
            InventoryDrawer.open(new WarningFrame(frame, viewer, null));
            return;
        }
        Object permission = c.getPermission(click);
        if (permission != null && !hasPermission(viewer, permission)) {
            InventoryDrawer.open(new WarningFrame(frame, viewer, permission));
            return;
        }
        if (c.isConfirmationRequired(click)) {
            InventoryDrawer.open(new ConfirmationFrame(frame, viewer, listener));
            return;
        }
        listener.run();
    }

    private static boolean hasAnyListener(@NotNull SCComponent c) {
        for (ClickType click : ClickType.values()) {
            if (c.getListener(click) != null) {
                return true;
            }
        }
        return false;
    }

    /** Item sem ação: vira "Nome — primeira linha da lore" no corpo. */
    private static String describe(@NotNull SCComponent c) {
        String name = displayName(c.getItem());
        String lore = loreOf(c.getItem());
        if (name.isEmpty() && lore.isEmpty()) {
            return "";
        }
        if (lore.isEmpty()) {
            return name;
        }
        return name.isEmpty() ? "§7" + lore : name + " §7— " + lore;
    }

    @NotNull
    private static String displayName(@Nullable ItemStack item) {
        if (item == null) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return "";
        }
        return meta.getDisplayName();
    }

    /** Junta a lore numa linha só: o botão do Bedrock não comporta um bloco. */
    @NotNull
    private static String loreOf(@Nullable ItemStack item) {
        if (item == null) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getLore() == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : meta.getLore()) {
            if (line != null && !line.trim().isEmpty()) {
                lines.add(line.trim());
            }
        }
        return String.join(" · ", lines);
    }

    /** Rotulo do clique alternativo, para o jogador saber que e outra acao. */
    @NotNull
    private static String clickName(@NotNull ClickType click) {
        switch (click) {
            case RIGHT:
            case SHIFT_RIGHT:
                return "alternativo";
            case DROP:
            case CONTROL_DROP:
                return "descartar";
            case MIDDLE:
                return "do meio";
            default:
                return "extra";
        }
    }

    private static boolean isClanVerified(@NotNull Player player) {
        SimpleClans plugin = SimpleClans.getInstance();
        ClanPlayer cp = plugin.getClanManager().getAnyClanPlayer(player.getUniqueId());
        return cp != null && cp.getClan() != null && cp.getClan().isVerified();
    }

    private static boolean hasPermission(@NotNull Player player, @NotNull Object permission) {
        SimpleClans plugin = SimpleClans.getInstance();
        PermissionsManager pm = plugin.getPermissionsManager();
        if (permission instanceof String) {
            String perms = (String) permission;
            boolean leaderPerm = perms.contains("simpleclans.leader")
                    && !perms.equalsIgnoreCase("simpleclans.leader.create");
            ClanPlayer cp = plugin.getClanManager().getAnyClanPlayer(player.getUniqueId());
            return pm.has(player, perms) && (!leaderPerm || (cp != null && cp.isLeader()));
        }
        return pm.has(player, (RankPermission) permission, false);
    }
}
