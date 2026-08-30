package net.sacredlabyrinth.phaed.simpleclans.chat.handlers;

import net.sacredlabyrinth.phaed.simpleclans.chat.ChatHandler;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage;
import net.sacredlabyrinth.phaed.simpleclans.chat.SCMessage.Source;

public class ProxyChatHandler implements ChatHandler {

    @Override
    public void sendMessage(SCMessage message) {
        plugin.getProxyManager().sendMessage(message);
    }

    @Override
    public boolean canHandle(Source source) {
        return source == Source.SPIGOT && plugin.getProxyManager().isEnabled();
    }
}
