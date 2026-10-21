package com.destroystokyo.paper.console;

import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import com.mohistmc.youer.api.ColorAPI;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.command.CraftConsoleCommandSender;

public class TerminalConsoleCommandSender extends CraftConsoleCommandSender {

    private static final Logger LOGGER = LogManager.getLogger(TerminalConsoleCommandSender.class);

    @Override
    public void sendRawMessage(String message) {
        final Component msg = LegacyComponentSerializer.legacySection().deserialize(message);
        this.sendMessage(Identity.nil(), msg, MessageType.SYSTEM);
    }

    @Override
    public void sendMessage(Identity identity, Component message, MessageType type) {
        LOGGER.info(ColorAPI.ansi(message));
    }

}
