package net.sacredlabyrinth.phaed.simpleclans.conversation;

import net.hypedmc.network.shared.bedrock.HypedBedrockAPI;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.ui.BedrockFrames;
import org.bukkit.Bukkit;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.Prompt;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static net.sacredlabyrinth.phaed.simpleclans.SimpleClans.lang;
import static net.sacredlabyrinth.phaed.simpleclans.conversation.CreateClanNamePrompt.NAME_KEY;
import static net.sacredlabyrinth.phaed.simpleclans.conversation.CreateClanTagPrompt.TAG_KEY;

/**
 * Troca a conversa por chat por um formulário nativo, para quem entra pelo
 * Bedrock.
 *
 * Digitar no chat é o pior caminho no celular: o teclado cobre a tela, a
 * pergunta rola para cima e não existe campo — o jogador precisa lembrar o que
 * foi perguntado. Aqui a pergunta vira campo de texto.
 *
 * Nenhuma validação foi duplicada. São dois caminhos, os dois reusando a
 * conversa original:
 *
 * <ul>
 *   <li><b>Criar clã</b>: as duas perguntas (tag e nome) cabem numa tela só. O
 *   formulário pré-preenche os dados da sessão e deixa a mesma conversa rodar —
 *   os prompts já tratam isso e, com {@code getSessionData} preenchido, vão
 *   direto para a validação. É o caminho de quem digita
 *   {@code /clan create <tag> <nome>}.</li>
 *   <li><b>Demais perguntas</b>: o formulário funciona como teclado. A resposta
 *   entra na conversa por {@link org.bukkit.conversations.Conversation#acceptInput},
 *   exatamente como se tivesse sido digitada.</li>
 * </ul>
 *
 * Fechar o formulário sem responder abandona a conversa, senão ela ficaria
 * pendurada esperando um chat que não vem.
 */
public final class BedrockPrompts {

    private BedrockPrompts() {
    }

    /**
     * @return {@code true} se o jogador recebeu o formulário e a conversa por
     *         chat <b>não</b> deve pedir nada no chat.
     */
    public static boolean intercept(
            @NotNull SCConversation conversation,
            @NotNull Player player,
            @Nullable Prompt first,
            @Nullable ConversationContext context
    ) {
        if (first == null || context == null || !BedrockFrames.isBedrock(player)) {
            return false;
        }
        SimpleClans plugin = SimpleClans.getInstance();

        try {
            if (first instanceof CreateClanTagPrompt) {
                return createClan(plugin, player, context);
            }
            if (first instanceof ConfirmationPrompt) {
                return confirmation(conversation, player, first, context);
            }
            if (first.blocksForInput(context)) {
                return ask(conversation, player, first, context);
            }
            return false;
        } catch (Throwable t) {
            // Falha aqui nunca deixa o jogador sem caminho: cai na conversa por chat.
            plugin.getLogger().warning("[SimpleClans] formulário Bedrock falhou: " + t.getMessage());
            return false;
        }
    }

    /** Tag e nome do clã na mesma tela, em vez de duas perguntas no chat. */
    private static boolean createClan(
            @NotNull SimpleClans plugin,
            @NotNull Player player,
            @NotNull ConversationContext context
    ) {
        if (context.getSessionData(TAG_KEY) != null && context.getSessionData(NAME_KEY) != null) {
            // Já veio preenchido (/clan create <tag> <nome> ou reentrada): deixa seguir.
            return false;
        }

        return HypedBedrockAPI.custom(player.getUniqueId())
                .title("Criar clã")
                .label("§7A tag é a abreviação que aparece no chat; o nome é o do clã.")
                .input("Tag do clã", "Ex: PVP")
                .input("Nome do clã", "Ex: Os Guerreiros")
                .onSubmit(result -> {
                    String tag = result.input(1, "").trim();
                    String name = result.input(2, "").trim();
                    if (tag.isEmpty() || name.isEmpty()) {
                        player.sendMessage("§cPreencha a tag e o nome do clã.");
                        return;
                    }
                    // Mesma conversa, agora com os dados prontos: toda a validação
                    // (tag em uso, tag bloqueada, tamanho, palavrão) continua valendo.
                    Map<Object, Object> data = new HashMap<>();
                    data.put(TAG_KEY, tag);
                    data.put(NAME_KEY, name);
                    new SCConversation(plugin, player, new CreateClanTagPrompt(), data).begin();
                })
                .send();
    }

    /** Pergunta de sim/não vira modal; a resposta entra como se fosse digitada. */
    private static boolean confirmation(
            @NotNull SCConversation conversation,
            @NotNull Player player,
            @NotNull Prompt prompt,
            @NotNull ConversationContext context
    ) {
        String yes = lang("yes", player);
        String question = prompt.getPromptText(context);

        return HypedBedrockAPI.modal(player.getUniqueId())
                .title("Confirmar")
                .content(question)
                .buttons(yes, lang("cancel", player))
                .onConfirm(() -> Bukkit.getScheduler().runTask(SimpleClans.getInstance(),
                        () -> conversation.acceptInput(yes)))
                .send();
    }

    /** Pergunta aberta vira um campo de texto; o formulário e o teclado. */
    private static boolean ask(
            @NotNull SCConversation conversation,
            @NotNull Player player,
            @NotNull Prompt prompt,
            @NotNull ConversationContext context
    ) {
        String question = stripColors(prompt.getPromptText(context));
        if (question.isEmpty()) {
            return false;
        }

        return HypedBedrockAPI.custom(player.getUniqueId())
                .title("SimpleClans")
                .input(question, "")
                .onSubmit(result -> {
                    String answer = result.input(0, "").trim();
                    if (answer.isEmpty()) {
                        player.abandonConversation(conversation);
                        return;
                    }
                    Bukkit.getScheduler().runTask(SimpleClans.getInstance(),
                            () -> conversation.acceptInput(answer));
                })
                .send();
    }

    @NotNull
    private static String stripColors(@Nullable String text) {
        return text == null ? "" : text.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim();
    }
}
