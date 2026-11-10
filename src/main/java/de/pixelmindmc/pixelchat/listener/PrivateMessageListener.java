/*
 * This file is part of PixelChat Guardian.
 * Copyright (C) 2026 PixelMindMC
 */

package de.pixelmindmc.pixelchat.listener;

import de.pixelmindmc.pixelchat.PixelChat;
import de.pixelmindmc.pixelchat.constants.ConfigConstants;
import de.pixelmindmc.pixelchat.constants.LangConstants;
import de.pixelmindmc.pixelchat.constants.PermissionConstants;
import de.pixelmindmc.pixelchat.exceptions.MessageClassificationException;
import de.pixelmindmc.pixelchat.model.MessageClassification;
import de.pixelmindmc.pixelchat.utils.ChatGuardHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Listener for moderating private messages, which players send as commands
 * (/msg, /tell, /m, /w, /r and similar).
 *
 * <p>Private messages never pass through {@link AsyncPlayerChatEvent}, so the command
 * itself is intercepted before it runs. The command is cancelled, the message is
 * classified asynchronously (the AI request must not block the server thread) and the
 * command is replayed once it has been allowed or censored. The messages of one player
 * are processed in the order they were sent, so a later /r still replies to the same
 * player as before.</p>
 */
public class PrivateMessageListener implements Listener {
    /**
     * Set while the plugin replays a command itself, so that the replayed command is not moderated twice
     */
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final @NotNull PixelChat plugin;
    private final boolean enabled;

    /**
     * Commands of the form {@code /<command> <player> <message>}
     */
    private final @NotNull Set<String> recipientCommands = new HashSet<>();

    /**
     * Commands of the form {@code /<command> <message>} without a player in the command
     */
    private final @NotNull Set<String> messageOnlyCommands = new HashSet<>();

    /**
     * Messages waiting for their classification, one queue per player. Only ever touched by the server thread.
     */
    private final @NotNull Map<UUID, Deque<PendingMessage>> queues = new HashMap<>();

    /**
     * Players whose queue is being worked on right now. Only ever touched by the server thread.
     */
    private final @NotNull Set<UUID> processing = new HashSet<>();

    /**
     * A private message that has been held back until the AI has classified it
     *
     * @param rawCommand The command as the player typed it, without the leading slash
     * @param head       Everything in front of the message, including the trailing space
     * @param message    The message itself
     */
    private record PendingMessage(@NotNull String rawCommand, @NotNull String head, @NotNull String message) {

        /**
         * Rebuilds the command with the message replaced by asterisks
         *
         * @return The command to replay for a censored message
         */
        private @NotNull String censoredCommand() {
            return head + "*".repeat(message.length());
        }
    }

    /**
     * Constructs a PrivateMessageListener object
     *
     * @param plugin The plugin instance
     */
    public PrivateMessageListener(@NotNull PixelChat plugin) {
        this.plugin = plugin;

        // Without an API key there is no AI to classify the messages, so nothing is held back
        boolean active = plugin.getAPIHelper() != null &&
                plugin.getConfigHelper().getBoolean(ConfigConstants.ChatGuard.PrivateMessages.ENABLED);

        if (active) {
            for (String command : plugin.getConfigHelper().getStringList(ConfigConstants.ChatGuard.PrivateMessages.COMMANDS))
                recipientCommands.add(command.toLowerCase(Locale.ROOT));
            for (String command : plugin.getConfigHelper()
                    .getStringList(ConfigConstants.ChatGuard.PrivateMessages.MESSAGE_ONLY_COMMANDS))
                messageOnlyCommands.add(command.toLowerCase(Locale.ROOT));

            if (recipientCommands.isEmpty() && messageOnlyCommands.isEmpty()) {
                plugin.getLoggingHelper()
                        .warning(plugin.getConfigHelperLanguage().getString(LangConstants.ChatGuard.NO_PRIVATE_MESSAGE_COMMANDS));
                active = false;
            }
        }

        this.enabled = active;

        // Debug logger message
        if (active) plugin.getLoggingHelper().debug("Moderating private messages sent with " + recipientCommands + " " + messageOnlyCommands);
    }

    /**
     * Event handler for the PlayerCommandPreprocessEvent
     * Holds back a private message until it has been classified
     *
     * @param event The PlayerCommandPreprocessEvent
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onPlayerCommandPreprocess(@NotNull PlayerCommandPreprocessEvent event) {
        // The command is our own replay and has already been moderated
        if (!enabled || REPLAYING.get()) return;

        Player player = event.getPlayer();
        if (player.hasPermission(PermissionConstants.Moderation.BYPASS_CHAT_MODERATION)) return;

        PendingMessage message = parse(event.getMessage());
        if (message == null) return;

        // The command would be executed before the AI has answered, so it is cancelled here and replayed later
        event.setCancelled(true);

        // Debug logger message
        plugin.getLoggingHelper().debug("Check if the private message '" + message.message() + "' from " + player.getName() + " should be blocked");

        // Only one message per player is classified at a time, otherwise a burst of messages
        // would reach the recipient out of order and a later /r could reply to the wrong player
        queues.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayDeque<>()).addLast(message);
        if (processing.add(player.getUniqueId())) processNext(player);
    }

    /**
     * Takes the next message of the player out of the queue and classifies it
     *
     * @param player The player that sent the messages
     */
    private void processNext(@NotNull Player player) {
        UUID playerUUID = player.getUniqueId();
        Deque<PendingMessage> queue = queues.get(playerUUID);
        PendingMessage message = queue == null ? null : queue.pollFirst();

        if (message == null) {
            queues.remove(playerUUID);
            processing.remove(playerUUID);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> classify(player, message));
    }

    /**
     * Classifies the message off the server thread and acts on the result on the server thread
     *
     * @param player  The player that sent the message
     * @param message The message to classify
     */
    private void classify(@NotNull Player player, @NotNull PendingMessage message) {
        MessageClassification classification;
        try {
            classification = plugin.getAPIHelper().classifyMessage(message.message());
        } catch (MessageClassificationException e) {
            plugin.getLoggingHelper().error(e.getMessage());
            //Don't block message if there was an error while classifying it
            Bukkit.getScheduler().runTask(plugin, () -> {
                replay(player, message.rawCommand());
                processNext(player);
            });
            return;
        }

        if (ChatGuardHelper.messageMatchesEnabledRule(plugin, classification))
            Bukkit.getScheduler().runTask(plugin, () -> moderate(player, message, classification));
        else
            Bukkit.getScheduler().runTask(plugin, () -> {
                replay(player, message.rawCommand());
                processNext(player);
            });
    }

    /**
     * Applies the configured message handling to a message that violates a blocking rule
     *
     * @param player         The player that sent the message
     * @param message        The message that was sent
     * @param classification The classification of the message
     */
    private void moderate(@NotNull Player player, @NotNull PendingMessage message, @NotNull MessageClassification classification) {
        String messageHandling = plugin.getConfigHelper().getString(ConfigConstants.ChatGuard.MESSAGE_HANDLING);

        // Same three modes as in the public chat, where anything but BLOCK and SILENT censors.
        // SILENT cannot fake the delivery here, because the format of a private message belongs to
        // whichever plugin handles it, so the message is dropped just like in BLOCK.
        switch (messageHandling) {
            case "BLOCK", "SILENT" -> {
            }
            default -> replay(player, message.censoredCommand());
        }

        ChatGuardHelper.notifyAndStrikePlayer(plugin, player, message.message(), classification, messageHandling, "private message");

        processNext(player);
    }

    /**
     * Runs a command as the player, unless they have left the server in the meantime
     * The command is marked as a replay, so that it is not moderated a second time
     *
     * @param player  The player to run the command for
     * @param command The command to run, without the leading slash
     */
    private void replay(@NotNull Player player, @NotNull String command) {
        if (!player.isOnline()) return;

        REPLAYING.set(Boolean.TRUE);
        try {
            player.performCommand(command);
        } finally {
            REPLAYING.remove();
        }
    }

    /**
     * Splits a private message command into the part in front of the message and the message itself
     *
     * @param command The command as the player typed it, including the leading slash
     * @return The parsed message, or {@code null} if the command is no private message or has no message in it
     */
    private @Nullable PendingMessage parse(@NotNull String command) {
        String body = command.startsWith("/") ? command.substring(1) : command;

        // The command name, e.g. "essentials:msg"
        int labelEnd = firstArgumentEnd(body);
        String label = stripNamespace(body.substring(0, labelEnd)).toLowerCase(Locale.ROOT);

        boolean recipientCommand = recipientCommands.contains(label);
        if (!recipientCommand && !messageOnlyCommands.contains(label)) return null;

        // The whitespace between the command name and the following argument, if any
        String afterLabel = body.substring(labelEnd);
        String arguments = afterLabel.stripLeading();
        if (arguments.isEmpty()) return null;

        int headEnd;
        String message;

        if (recipientCommand) {
            // Skip the recipient, e.g. "Player" or a selector like "@p[distance=..5]"
            int recipientEnd = firstArgumentEnd(arguments);
            String afterRecipient = arguments.substring(recipientEnd);
            message = afterRecipient.stripLeading();
            if (recipientEnd == arguments.length() || message.isEmpty()) return null;
            headEnd = labelEnd + (afterLabel.length() - arguments.length()) + recipientEnd + (afterRecipient.length() - message.length());
        } else {
            message = arguments;
            headEnd = labelEnd + (afterLabel.length() - arguments.length());
        }

        return new PendingMessage(command.startsWith("/") ? command.substring(1) : command, body.substring(0, headEnd), message);
    }

    /**
     * Finds the end of the first whitespace-separated argument of a command
     * Selectors like {@code @p[tag=test]} contain spaces themselves, so brackets are not split at
     *
     * @param input The text to search
     * @return The index of the first whitespace outside of brackets, or the length of the input
     */
    private static int firstArgumentEnd(@NotNull String input) {
        int depth = 0;

        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);

            if (character == '[' || character == '{') depth++;
            else if ((character == ']' || character == '}') && depth > 0) depth--;
            else if (depth == 0 && Character.isWhitespace(character)) return i;
        }

        return input.length();
    }

    /**
     * Removes the plugin namespace from a command name, e.g. "essentials:msg" becomes "msg"
     *
     * @param label The command name
     * @return The command name without its namespace
     */
    private static @NotNull String stripNamespace(@NotNull String label) {
        int colon = label.indexOf(':');
        return colon < 0 ? label : label.substring(colon + 1);
    }
}
