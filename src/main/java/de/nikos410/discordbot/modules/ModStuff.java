package de.nikos410.discordbot.modules;

import de.nikos410.discordbot.exception.InitializationException;
import de.nikos410.discordbot.framework.CommandModule;
import de.nikos410.discordbot.framework.PermissionLevel;
import de.nikos410.discordbot.framework.annotations.CommandParameter;
import de.nikos410.discordbot.framework.annotations.CommandSubscriber;
import de.nikos410.discordbot.util.CommandUtils;
import de.nikos410.discordbot.util.discord.ChannelUtils;
import de.nikos410.discordbot.util.discord.GuildUtils;
import de.nikos410.discordbot.util.discord.UserUtils;
import de.nikos410.discordbot.util.io.IOUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.handle.impl.events.guild.member.UserBanEvent;
import sx.blah.discord.handle.impl.events.guild.member.UserJoinEvent;
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelJoinEvent;
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelLeaveEvent;
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelMoveEvent;
import sx.blah.discord.handle.impl.obj.ReactionEmoji;
import sx.blah.discord.handle.obj.*;
import sx.blah.discord.util.EmbedBuilder;
import sx.blah.discord.util.PermissionUtils;
import sx.blah.discord.util.cache.LongMap;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ModStuff extends CommandModule {
    private static final Logger LOG = LoggerFactory.getLogger(ModStuff.class);

    private static final Path MODSTUFF_PATH = Paths.get("data/modstuff.json");
    private JSONObject modstuffJSON;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Map<IGuild, Map<IUser, ScheduledFuture>> userMuteFutures = new HashMap<>();
    private final Map<IGuild, Map<IChannel, Map<IUser, ScheduledFuture>>> channelMuteFutures = new HashMap<>();

    private final Map<IGuild, List<String>> voiceLog = new HashMap<>();

    @Override
    public String getDisplayName() {
        return "Moderationswerkzeuge";
    }

    @Override
    public String getDescription() {
        return "Diverse Tools, die die Moderation eines Servers erleichtern.";
    }

    @Override
    public boolean hasEvents() {
        return true;
    }

    @Override
    public void init() {
        final String rolesFileContent = IOUtil.readFile(MODSTUFF_PATH);
        if (rolesFileContent == null) {
            LOG.error("Could not read modstuff file.");
            throw new InitializationException("Could not read modstuff file.", ModStuff.class) ;
        }
        this.modstuffJSON = new JSONObject(rolesFileContent);
        LOG.info("Loaded modstuff file for {} guilds.", modstuffJSON.keySet().size());
    }

    @Override
    public void initWhenReady() {
        // Restore all mutes that can be found in the JSON file
        LOG.info("Restoring muted users.");

        for (final String guildStringID : modstuffJSON.keySet()) {
            LOG.debug("Processing JSON for guild with ID '{}'.", guildStringID);

            final long guildLongID = Long.parseLong(guildStringID);
            final IGuild guild = bot.getClient().getGuildByID(guildLongID);
            LOG.debug("Found guild '{}'.", guild.getName());

            restoreGuildUserMutes(guild);
            restoreGuildChannelMutes(guild);
        }

        LOG.info("Restored all mutes.");
    }

    @Override
    public void shutdown() {
        // Usermutes
        userMuteFutures.values()
                .forEach(guildUserMutes -> guildUserMutes.values()
                        .forEach(future -> future.cancel(false)));

        // Channel mutes
        channelMuteFutures.values()
                .forEach(guildChannelMutes -> guildChannelMutes.values()
                        .forEach(channelMutes -> channelMutes.values()
                            .forEach(future -> future.cancel(false))));

        scheduler.shutdown();
    }

    @CommandSubscriber(command = "kick", help = "Kickt den angegebenen Nutzer mit der angegeben Nachricht vom Server",
            pmAllowed = false, ignoreParameterCount = true)
    public void command_kick(final IMessage message,
                             @CommandParameter(name = "Nutzer", help = "Nutzer der gekickt werden soll als ID oder @mention.")
                             final String userString,
                             @CommandParameter(name = "Nachricht", help = "Nachricht, die an den Nutzer und in den Modlog geschickt wird.")
                             String customMessage) {
        // Only Moderators and upwards are allowed to use the command.
        // If a user is not a moderator they will be kicked instead.
        if (this.bot.getUserPermissionLevel(message.getAuthor(), message.getGuild()).getLevel() >=
                PermissionLevel.MODERATOR.getLevel()) {

            // Find the user to kick
            final IUser kickUser = UserUtils.getUserFromMessage(message, userString);
            if (kickUser == null) {
                messageService.sendMessage(message.getChannel(), ":x: Fehler: Kein gültiger Nutzer angegeben!");
                return;
            }

            // Check if the bot has the permissions to kick the user
            final IGuild guild = message.getGuild();

            if (!PermissionUtils.hasHierarchicalPermissions(guild, message.getClient().getOurUser(), kickUser, Permissions.KICK)) {
                messageService.sendMessage(message.getChannel(), ":x: Nutzer kann nicht gekickt werden! (Unzureichende Berechtigungen)");
                return;
            }

            // Set a default message if no message was specified.
            if (customMessage == null || customMessage.isEmpty()) {
                customMessage = "kein";
            }

            // Do not notify a bot user
            if (!kickUser.isBot()) {
                final List<String> kickMessage = Arrays.asList(
                        String.format("**Du wurdest vom Server %s gekickt!** (Du kannst dem Server jedoch erneut beitreten.)", guild.getName()),
                        String.format("Hinweis: _%s_", customMessage));
                messageService.sendMessage(kickUser.getOrCreatePMChannel(), kickMessage);
            }

            guild.kickUser(kickUser, customMessage);

            message.addReaction(ReactionEmoji.of("\uD83D\uDEAA")); // :door:

            // Modlog
            LOG.info("Guild '{}': User {} was kicked. Message: {}",
                    guild.getName(),
                    UserUtils.makeUserString(kickUser, message.getGuild()),
                    customMessage);

            final IChannel modLogChannel = getModlogChannelForGuild(guild);

            if (modLogChannel != null) {
                final List<String> modLogMessage = new ArrayList<>();
                modLogMessage.add(String.format("Nutzer **%s** wurde im Kanal %s vom Server **gekickt**.",
                        UserUtils.makeUserString(kickUser, guild),
                        message.getChannel().mention()));
                modLogMessage.add(String.format("Hinweis: _%s_", customMessage));

                messageService.sendMessage(modLogChannel, modLogMessage);
            }
        }
        else {
            message.getGuild().kickUser(message.getAuthor());
            messageService.sendMessage(message.getChannel(), "¯\\_(ツ)_/¯");
        }
    }

    @CommandSubscriber(command = "ban", help = "Bannt den angegebenen Nutzer mit der angegeben Nachricht vom Server",
            pmAllowed = false, ignoreParameterCount = true)
    public void command_ban(final IMessage message,
                            @CommandParameter(name = "Nutzer", help = "Nutzer der gebannt werden soll als ID oder @mention.")
                            final String userString,
                            @CommandParameter(name = "Nachricht", help = "Nachricht, die an den Nutzer und in den Modlog geschickt wird.")
                            String customMessage) {
        // Only Moderators and upwards are allowed to use the command.
        // If a user is not a moderator they will be kicked instead.
        if (this.bot.getUserPermissionLevel(message.getAuthor(), message.getGuild()).getLevel() >=
                PermissionLevel.MODERATOR.getLevel()) {

            // Find the user to ban
            final IUser banUser = UserUtils.getUserFromMessage(message, userString);
            if (banUser == null) {
                messageService.sendMessage(message.getChannel(), ":x: Fehler: Kein gültiger Nutzer angegeben!");
                return;
            }

            // Check if the bot has the permissions to ban the user
            final IGuild guild = message.getGuild();

            if (!PermissionUtils.hasHierarchicalPermissions(guild, message.getClient().getOurUser(), banUser, Permissions.BAN)) {
                messageService.sendMessage(message.getChannel(), ":x: Nutzer kann nicht gebannt werden! (Unzureichende Berechtigungen)");
                return;
            }

            // Set a default message if no message was specified.
            if (customMessage == null || customMessage.isEmpty()) {
                customMessage = "kein";
            }

            // Do not notify a bot user
            if (!banUser.isBot()) {
                final List<String> banMessage = Arrays.asList(String.format("**Du wurdest vom Server %s gebannt!**", guild.getName()),
                        String.format("Hinweis: _%s_", customMessage));

                messageService.sendMessage(banUser.getOrCreatePMChannel(), banMessage);
            }

            guild.banUser(banUser, customMessage, 0);

            message.addReaction(ReactionEmoji.of("\uD83D\uDD28")); // :hammer:

            // Modlog
            LOG.info("Guild '{}': User {} was banned. Message: {}",
                    guild.getName(),
                    UserUtils.makeUserString(banUser, guild),
                    customMessage);

            final IChannel modLogChannel = getModlogChannelForGuild(guild);

            if (modLogChannel != null) {
                final List<String> modLogMessage = new ArrayList<>();
                modLogMessage.add(String.format("Nutzer **%s** wurde im Kanal %s vom Server **gebannt**.",
                        UserUtils.makeUserString(banUser, guild),
                        message.getChannel().mention()));
                modLogMessage.add(String.format("Hinweis: _%s_", customMessage));

                messageService.sendMessage(modLogChannel, modLogMessage);
            }
        }
        else {
            message.getGuild().kickUser(message.getAuthor(), customMessage);
            messageService.sendMessage(message.getChannel(), "¯\\_(ツ)_/¯");
        }
    }

    @EventSubscriber
    public void onUserBanned (final UserBanEvent event) {
        final IGuild guild = event.getGuild();
        final IChannel modLogChannel = getModlogChannelForGuild(guild);

        if (modLogChannel != null) {
            final String modLogMessage = String.format("**%s** wurde vom Server **gebannt**.",
                    UserUtils.makeUserString(event.getUser(), event.getGuild()));
            messageService.sendMessage(modLogChannel, modLogMessage);
        }
    }

    @CommandSubscriber(command = "mute", help = "Einen Nutzer für eine bestimmte Zeit muten", pmAllowed = false,
            permissionLevel = PermissionLevel.MODERATOR, ignoreParameterCount = true)
    public void command_mute(final IMessage message,
                             @CommandParameter(name = "Nutzer", help = "Nutzer der gemuted werden soll als ID oder @mention.")
                             final String userString,
                             @CommandParameter(name = "Dauer", help = "Die Dauer, für den der Mute anhalten soll. Mögliche Einheiten: s, m, h, d")
                             final String muteDurationInput) {
        // Find the user to mute
        final IUser muteUser = UserUtils.getUserFromMessage(message, userString);
        if (muteUser == null) {
            messageService.sendMessage(message.getChannel(), ":x: Fehler: Nutzer nicht gefunden!");
            return;
        }

        // Check if mute duration was specified
        if (muteDurationInput == null) {
            messageService.sendMessage(message.getChannel(), "Fehler! Es muss eine Mute-Dauer angegeben werden.");
            return;
        }

        // Parse mute duration
        final CommandUtils.DurationParameters durationParameters = CommandUtils.parseDurationParameters(muteDurationInput);
        if (durationParameters == null) {
            messageService.sendMessage(message.getChannel(),
                    "Ungültige Dauer angegeben. Mögliche Einheiten sind: s, m, h, d");
            return;
        }

        final int muteDuration = durationParameters.getDuration();
        final ChronoUnit muteDurationUnit = durationParameters.getDurationUnit();
        String customMessage = durationParameters.getMessage();
        if (customMessage == null || customMessage.isEmpty()) {
            customMessage = "kein";
        }

        final IGuild guild = message.getGuild();

        // Mute the user and schedule unmute
        muteUserForGuild(muteUser, guild, muteDuration, muteDurationUnit, message.getChannel());
        message.addReaction(ReactionEmoji.of("\uD83D\uDD07")); // :mute:

        // Do not notify a bot user
        if (!muteUser.isBot()) {
            final List<String> muteMessage = Arrays.asList(String.format("**Du wurdest auf dem Server %s für %s %s gemuted!**",
                    guild.getName(),
                    muteDuration,
                    muteDurationUnit.name()),
                    String.format("Hinweis: _%s_", customMessage));

            messageService.sendMessage(muteUser.getOrCreatePMChannel(), muteMessage);
        }

        // Modlog
        LOG.info("Guild '{}': User {} was muted for {} {}. Message: {}",
                guild.getName(),
                UserUtils.makeUserString(muteUser, message.getGuild()),
                muteDuration,
                muteDurationUnit.name(),
                customMessage);

        final IChannel modLogChannel = getModlogChannelForGuild(guild);

        if (modLogChannel != null) {
            final List<String> modLogMessage = new ArrayList<>();
            modLogMessage.add(String.format("Nutzer **%s** wurde im Kanal %s für %s %s **gemuted**.",
                    UserUtils.makeUserString(muteUser, guild),
                    message.getChannel().mention(),
                    muteDuration, muteDurationUnit.name()));
            modLogMessage.add(String.format("Hinweis: _%s_", customMessage));

            messageService.sendMessage(modLogChannel, modLogMessage);
        }

        saveUserMutes();
    }

    @CommandSubscriber(command = "selfmute", help = "Schalte dich selber für die angegebene Zeit stumm",
            pmAllowed = false)
    public void command_selfmute(final IMessage message,
                                 @CommandParameter(name = "Dauer", help = "Die Dauer, für den der Mute anhalten soll. Mögliche Einheiten: s, m, h, d")
                                 final String muteDurationInput) {
        // The author of the message will be muted
        final IUser muteUser = message.getAuthor();

        // Parse mute duration
        final CommandUtils.DurationParameters durationParameters = CommandUtils.parseDurationParameters(muteDurationInput);
        if (durationParameters == null) {
            messageService.sendMessage(message.getChannel(),
                    "Ungültige Dauer angegeben. Mögliche Einheiten sind: s, m, h, d");
            return;
        }

        // Users can only mute themself for a maximum of one day
        final LocalDateTime muteEnd = LocalDateTime.now().plus(durationParameters.getDuration(), durationParameters.getDurationUnit());
        if (muteEnd.isAfter(LocalDateTime.now().plusDays(1))) {
            messageService.sendMessage(message.getChannel(), "Du kannst dich für maximal einen Tag selbst muten!");
            return;
        }

        final IGuild guild = message.getGuild();

        // Mute the user and schedule unmute
        muteUserForGuild(muteUser, guild, durationParameters.getDuration(), durationParameters.getDurationUnit(), message.getChannel());

        message.addReaction(ReactionEmoji.of("\uD83D\uDD07")); // :mute:
    }

    @CommandSubscriber(command = "unmute", help = "Nutzer entmuten", pmAllowed = false,
            permissionLevel = PermissionLevel.MODERATOR)
    public void command_unmute(final IMessage message,
                               @CommandParameter(name = "Nutzer", help = "Nutzer der entmuted werden soll als ID oder @mention.")
                               final String userString) {
        final IUser muteUser = UserUtils.getUserFromMessage(message, userString);

        unmuteUserForGuild(muteUser, message.getGuild(), message.getChannel());

        message.addReaction(ReactionEmoji.of("✅")); // :white_check_mark:
    }

    /**
     * Mute a user for a specific duration on a guild by adding the mute role that is configured for the given guild.
     * An existing mute will be overridden, if the new mute lasts longer than the existing one. You can not override an
     * existing mute with one that will end before the existing one does.
     *
     * @param user The user that will be muted.
     * @param guild The guild for which the user will me muted.
     * @param muteDuration The duration until the user will be unmuted.
     * @param muteDurationUnit The time unit for the mute duration.
     * @param channel The channel to send error messages to. Can be null.
     */
    private void muteUserForGuild(final IUser user, final IGuild guild, final int muteDuration, final ChronoUnit muteDurationUnit, final IChannel channel) {
        if (!guild.getUsers().contains(user)) {
            throw new IllegalArgumentException("Specified user is not a member of the specified guild!");
        }

        // Get mute role for this guild
        final IRole muteRole = getMuteRoleForGuild(guild);
        if (muteRole == null) {
            if (channel != null) {
                messageService.sendMessage(channel, ":x: Keine Mute-Rolle konfiguriert. Nutzer kann nicht gemuted werden.");
            }
            else {
                LOG.warn("No mute role configured for guild {} (ID: {}). Cannot mute user.",
                        guild.getName(), guild.getStringID());
            }
            return;
        }

        if (isUserMutedForGuild(user, guild)) {
            // User is already muted
            final ScheduledFuture oldFuture = userMuteFutures.get(guild).get(user);

            // Check which mute lasts longer
            final LocalDateTime oldDateTime = LocalDateTime.now().plusSeconds(oldFuture.getDelay(TimeUnit.SECONDS));
            final LocalDateTime newDateTime = LocalDateTime.now().plus(muteDuration, muteDurationUnit);

            if (newDateTime.isBefore(oldDateTime)) {
                // Existing mute lasts longer than the existing one -> Do nothing
                if (channel != null) {
                    messageService.sendMessage(channel, ":x: Nutzer ist bereits für einen längeren Zeitraum gemuted.");
                }
                return;
            }
            else {
                // New mute lasts longer than the existing one -> override
                userMuteFutures.get(guild).remove(user, oldFuture);
                oldFuture.cancel(false);
            }
        }
        else {
            // User is not muted yet
            user.addRole(muteRole);
            LOG.info("Muted user {} for {} {}.", UserUtils.makeUserString(user, guild), muteDuration, muteDurationUnit.name());
        }

        // Task that will be run to unmute user
        final Runnable unmuteTask = () -> unmuteUserForGuild(user, guild, null);


        // Schedule the unmute task
        final ScheduledFuture newFuture = scheduler.schedule(unmuteTask, muteDuration, CommandUtils.toTimeUnit(muteDurationUnit));

        // Save the mute, so it can be restored after a restart of the bot
        if (userMuteFutures.containsKey(guild)) {
            userMuteFutures.get(guild).put(user, newFuture);
        }
        else {
            final Map<IUser, ScheduledFuture> guildMap = new HashMap<>();
            guildMap.put(user, newFuture);
            userMuteFutures.put(guild, guildMap);
        }
    }

    /**
     * Unmutes a user for a guild, by removing the configured mute role.
     *
     * @param user The user to unmute.
     * @param guild The guild the user should be unmuted on.
     * @param channel The channel to send error messages to. Can be null.
     */
    private void unmuteUserForGuild (final IUser user, final IGuild guild, final IChannel channel) {
        // Get mute role for this guild
        final IRole muteRole = getMuteRoleForGuild(guild);
        if (muteRole == null) {
            if (channel != null) {
                messageService.sendMessage(channel, ":x: Keine Mute-Rolle konfiguriert. Nutzer kann nicht entmuted werden.");
            }
            else {
                LOG.warn("No mute role configured for guild {} (ID: {}). Cannot unmute user.",
                        guild.getName(), guild.getStringID());
            }
            return;
        }

        // Only remove the mute role if the user is still a member of the guild
        if (guild.getUsers().contains(user) && user.hasRole(muteRole)) {
            user.removeRole(muteRole);
        }

        if (isUserMutedForGuild(user, guild)) {
            userMuteFutures.get(guild).remove(user);
        }

        LOG.info("User {} was unmuted.", UserUtils.makeUserString(user, guild));
        saveUserMutes();
    }

    /**
     * Check if a user is muted on a guild by checking if the user is in the {@link #userMuteFutures} map for the given guild.
     *
     * @param user The user.
     * @param guild The guild.
     * @return True if the user is muted on the given guild, otherwise false.
     */
    private boolean isUserMutedForGuild (final IUser user, final IGuild guild) {
        return (userMuteFutures.containsKey(guild) && userMuteFutures.get(guild).containsKey(user));
    }

    @CommandSubscriber(command = "channelMute", help = "Nutzer in einem Channel für eine bestimmte Zeit stummschalten",
            pmAllowed = false, permissionLevel = PermissionLevel.MODERATOR)
    public void command_channelMute(final IMessage message,
                                    @CommandParameter(name = "Nutzer", help = "Nutzer der gemuted werden soll als ID oder @mention.")
                                    final String userInput,
                                    @CommandParameter(name = "Kanal", help = "Der Kanal für den der Mute gelten soll als ID oder #mention.")
                                    final String channelInput,
                                    @CommandParameter(name = "Dauer", help = "Die Dauer, für den der Mute anhalten soll. Mögliche Einheiten: s, m, h, d")
                                    final String muteDurationInput) {
        // Parse user
        final IUser muteUser = UserUtils.getUserFromMessage(message, userInput);
        if (muteUser == null) {
            messageService.sendMessage(message.getChannel(), ":x: Fehler: Kein gültiger Nutzer angegeben!");
            return;
        }

        // Parse channel
        final IChannel muteChannel = ChannelUtils.getChannelFromMessage(message, channelInput);
        if (muteChannel == null) {
            messageService.sendMessage(message.getChannel(), ":x: Fehler: Kein gültiger Kanal angegeben!");
            return;
        }

        // Parse mute duration and message
        final CommandUtils.DurationParameters durationParameters = CommandUtils.parseDurationParameters(muteDurationInput);
        if (durationParameters == null) {
            messageService.sendMessage(message.getChannel(),
                    "Ungültige Dauer angegeben. Mögliche Einheiten sind: s, m, h, d");
            return;
        }

        final int muteDuration = durationParameters.getDuration();
        final ChronoUnit muteDurationUnit = durationParameters.getDurationUnit();
        String customMessage = durationParameters.getMessage();

        if (customMessage == null || customMessage.isEmpty()) {
            customMessage = "kein";
        }

        // Mute user and schedule unmute
        final String output = muteUserForChannel(muteUser, muteChannel, muteDuration, muteDurationUnit);
        if (output.isEmpty()) {
            message.addReaction(ReactionEmoji.of("\uD83D\uDD07")); // :mute:
        }
        else {
            messageService.sendMessage(message.getChannel(), output);
            return;
        }

        final IGuild guild = message.getGuild();

        // Do not notify a bot user
        if (!muteUser.isBot()) {
            final List<String> muteMessage = new ArrayList<>();
            muteMessage.add(String.format("**Du wurdest für %s %s für den Kanal %s auf dem Server %s gemuted!**",
                    muteDuration, muteDurationUnit.name(),
                    muteChannel.getName(),
                    guild.getName()));
            muteMessage.add(String.format("Hinweis: _%s_", customMessage));

            messageService.sendMessage(muteUser.getOrCreatePMChannel(), muteMessage);
        }

        // Modlog
        LOG.info("Guild '{}': User {} was muted for {} {} for the channel {}. Message: {}",
                guild.getName(),
                UserUtils.makeUserString(muteUser, guild),
                muteDuration,
                muteDurationUnit.name(),
                muteChannel.getName(),
                customMessage);

        final IChannel modLogChannel = getModlogChannelForGuild(guild);

        if (modLogChannel != null) {
            final List<String> modLogMessage = new ArrayList<>();
            modLogMessage.add(String.format("Nutzer **%s** wurde im Kanal %s für %s %s für den Kanal %s **gemuted**.",
                    UserUtils.makeUserString(muteUser, message.getGuild()), message.getChannel().mention(),
                    muteDuration, muteDurationUnit.name(), muteChannel.mention()));
            modLogMessage.add(String.format("Hinweis: _%s _", customMessage));

            messageService.sendMessage(modLogChannel, modLogMessage);
        }
    }

    private String muteUserForChannel (final IUser user, final IChannel channel, final int muteDuration, final ChronoUnit muteDurationUnit) {
        final LongMap<PermissionOverride> oldOverrides = channel.getUserOverrides();

        if (oldOverrides.containsKey(user.getLongID())) {
            // Permissions fo this user are already overridden
            final PermissionOverride oldUserOverrides = oldOverrides.get(user.getLongID());

            // Copy previous permissions
            final EnumSet<Permissions> userAllow = oldUserOverrides.allow().clone();
            final EnumSet<Permissions> userDeny = oldUserOverrides.deny().clone();

            // Remove permission to send messages
            userAllow.remove(Permissions.SEND_MESSAGES);
            userDeny.add(Permissions.SEND_MESSAGES);

            // Update permissions
            channel.overrideUserPermissions(user, userAllow, userDeny);
        }
        else {
            // Permissions are not overridden for this user -> Just remove permission to send messages
            channel.overrideUserPermissions(user, EnumSet.noneOf(Permissions.class), EnumSet.of(Permissions.SEND_MESSAGES));
        }

        final IGuild guild = channel.getGuild();

        if (isUserMutedForChannel(user, channel)) {
            // User is already muted for this channel
            final ScheduledFuture oldFuture = channelMuteFutures.get(guild).get(channel).get(user);

            // Check whether the existing or the new mute lasts longer
            final LocalDateTime oldDateTime = LocalDateTime.now().plusSeconds(oldFuture.getDelay(TimeUnit.SECONDS));
            final LocalDateTime newDateTime = LocalDateTime.now().plus(muteDuration, muteDurationUnit);

            if (newDateTime.isBefore(oldDateTime)) {
                // Existing mute lasts longer -> Keep existing mute
                return "Nutzer ist bereits für einen längeren Zeitraum gemuted!";
            }
            else {
                // New mute lasts longer -> Cancel existing mute (New mute will be scheduled)
                channelMuteFutures.get(guild).get(channel).remove(user, oldFuture);
                oldFuture.cancel(false);
            }
        }
        else {
            // User is not muted for this channel yet
            LOG.info("Muted user {}.", UserUtils.makeUserString(user, guild));
        }

        // Schedule unmuting the user
        final Runnable unmuteTask = () -> unmuteUserForChannel(user, channel);
        final ScheduledFuture newFuture = scheduler.schedule(unmuteTask, muteDuration, CommandUtils.toTimeUnit(muteDurationUnit));

        final Map<IChannel, Map<IUser, ScheduledFuture>> guildMap;
        // Make sure an entry for this guild exists
        if (channelMuteFutures.containsKey(guild)) {
            guildMap = channelMuteFutures.get(guild);
        }
        else {
            guildMap = new HashMap<>();
            channelMuteFutures.put(guild, guildMap);
        }

        final Map<IUser, ScheduledFuture> channelMap;
        // Make sure an entry for this channel exists
        if (guildMap.containsKey(channel)) {
            channelMap = guildMap.get(channel);
        }
        else {
            channelMap = new HashMap<>();
            guildMap.put(channel, channelMap);
        }

        // Save the mute
        channelMap.put(user, newFuture);
        saveChannelMutes();

        return "";
    }

    private void unmuteUserForChannel(final IUser user, final IChannel channel) {
        final IGuild guild = channel.getGuild();

        final LongMap<PermissionOverride> currentOverrides = channel.getUserOverrides();

        if (currentOverrides.containsKey(user.getLongID())) {
            // Reset permission to send messages to neutral
            final PermissionOverride currentUserOverride = currentOverrides.get(user.getLongID());

            final EnumSet<Permissions> userAllowed = currentUserOverride.allow();
            final EnumSet<Permissions> userDenied = currentUserOverride.deny();

            userAllowed.remove(Permissions.SEND_MESSAGES);
            userDenied.remove(Permissions.SEND_MESSAGES);

            // Remove override if it is empty
            if (userAllowed.isEmpty() && userDenied.isEmpty()) {
                channel.removePermissionsOverride(user);
            }
            else {
                channel.overrideUserPermissions(user, userAllowed, userDenied);
            }
        }
        else {
            // Override does not exist anymore, don't do anything
            LOG.info("Can't unmute user {} for channel {}. Override does not exist.",
                    UserUtils.makeUserString(user, guild), channel.getName());
        }

        LOG.info("Nutzer {} wurde entmuted.", UserUtils.makeUserString(user, guild));

        channelMuteFutures.get(guild).get(channel).remove(user);
        saveChannelMutes();
    }

    private boolean isUserMutedForChannel (final IUser user, final IChannel channel) {
        final IGuild guild = channel.getGuild();
        if (channelMuteFutures.containsKey(guild)) {
            final Map<IChannel, Map<IUser, ScheduledFuture>> guildMap = channelMuteFutures.get(guild);

            if (guildMap.containsKey(channel)) {
                final Map<IUser, ScheduledFuture> channelMap = guildMap.get(channel);

                return channelMap.containsKey(user);
            }
        }

        return false;
    }

    @CommandSubscriber(command = "voicelog", help = "Die letzten Aktivitäten in Sprachkanälen auflisten",
    pmAllowed = false, permissionLevel = PermissionLevel.MODERATOR, ignoreParameterCount = true)
    public void command_voicelog(final IMessage message,
                                 @CommandParameter(name = "Anzahl", help = "Die Anzahl an Einträgen, die angezeigt werden sollen. (Standard 20)")
                                 final String listCountArg) {
        final int listCount;

        if (listCountArg == null) {
            listCount = 20;
        }
        else {
            try {
                listCount = Integer.parseInt(listCountArg);
            }
            catch (NumberFormatException e) {
                messageService.sendMessage(message.getChannel(), ":x: Die angegebene Anzahl ist keine gültige Zahl!");
                return;
            }
        }

        final List<String> guildVoiceLog = getVoiceLogForGuild(message.getGuild());

        final StringBuilder stringBuilder = new StringBuilder();
        boolean entriesSkipped = false;

        for (int i = guildVoiceLog.size()-1; i > (guildVoiceLog.size() - listCount - 1) && i >= 0; i--) {
            final String lineToAdd = guildVoiceLog.get(i);
            if (stringBuilder.length() + lineToAdd.length() <= 1024) {
                stringBuilder.append(guildVoiceLog.get(i));
                stringBuilder.append(String.format("%n"));
            }
            else {
                entriesSkipped = true;
            }
        }

        final EmbedBuilder responseBuilder = new EmbedBuilder();
        final String content = stringBuilder.length() > 0 ? stringBuilder.toString() : "_keine_";
        responseBuilder.appendField(String.format("__Die letzten %s Voice-Interaktionen (von neu nach alt)__", listCount), content, false);
        if (entriesSkipped) {
            responseBuilder.withFooterText("Einer oder mehrere Einträge wurden ignoriert, weil die maximale Textlänge erreicht wurde.");
        }

        messageService.sendEmbed(message.getChannel(), responseBuilder.build());
    }

    @EventSubscriber
    public void onUserMove (final UserVoiceChannelMoveEvent event) {
        LOG.debug("Logged voice move event.");
        final IUser user = event.getUser();
        final IVoiceChannel newChannel = event.getNewChannel();
        final IGuild guild = event.getGuild();
        final String eventString = String.format("**%s** :arrow_forward: ` %s`", UserUtils.makeUserString(user, guild), newChannel.getName());
        getVoiceLogForGuild(guild).add(eventString);
    }

    @EventSubscriber
    public void onUserConnect(final UserVoiceChannelJoinEvent event) {
        LOG.debug("Logged voice connect event.");
        final IUser user = event.getUser();
        final IVoiceChannel channel = event.getVoiceChannel();
        final IGuild guild = event.getGuild();
        final String eventString = String.format("**%s** :arrow_forward: `%s`", UserUtils.makeUserString(user, guild), channel.getName());
        getVoiceLogForGuild(guild).add(eventString);
    }

    @EventSubscriber
    public void onUserDisconnect(final UserVoiceChannelLeaveEvent event) {
        LOG.debug("Logged voice disconnect event.");
        final IUser user = event.getUser();
        final IVoiceChannel channel = event.getVoiceChannel();
        final IGuild guild = event.getGuild();
        final String eventString = String.format("**%s** :small_red_triangle_down: `%s`", UserUtils.makeUserString(user, guild), channel.getName());
        getVoiceLogForGuild(guild).add(eventString);
    }

    @CommandSubscriber(command = "setModlogChannel", help = "Kanal in dem die Modlog Nachrichten gesendet werden einstellen",
            pmAllowed = false, passContext = false, permissionLevel = PermissionLevel.ADMIN)
    public void command_setModlogChannel(final IMessage message,
                                         @CommandParameter(name = "Kanal", help = "Der Kanal in den die Nachrichten gesendet werden sollen.")
                                         final String channelParameter) {
        final IChannel modlogChannel = ChannelUtils.getChannelFromMessage(message, channelParameter);
        if (modlogChannel == null) {
            // No valid channel was specified
            messageService.sendMessage(message.getChannel(), "Kein gültiger Kanal angegeben!");
            return;
        }

        final IGuild guild = message.getGuild();
        final JSONObject guildJSON;
        if (modstuffJSON.has(guild.getStringID())) {
            guildJSON = modstuffJSON.getJSONObject(guild.getStringID());
        }
        else {
            guildJSON = new JSONObject();
            modstuffJSON.put(guild.getStringID(), guildJSON);
        }

        guildJSON.put("modlogChannel", modlogChannel.getLongID());
        saveJSON();

        message.addReaction(ReactionEmoji.of("✅")); // :white_check_mark:
    }

    @CommandSubscriber(command = "setMuteRole", help = "Mute Rolle einstellen",
            pmAllowed = false, passContext = false, permissionLevel = PermissionLevel.ADMIN)
    public void command_setMuteRole(final IMessage message,
                                    @CommandParameter(name = "Rolle", help = "Der Rolle die benutzt werden soll um Nutzer zu muten als ID oder @mention.")
                                    final String roleParameter) {
        final IRole muteRole = GuildUtils.getRoleFromMessage(message, roleParameter);
        if(muteRole == null) {
            // No valid role specified
            messageService.sendMessage(message.getChannel(), "Keine gültige Rolle angegeben!");
            return;
        }

        final IGuild guild = message.getGuild();
        final JSONObject guildJSON;
        if (modstuffJSON.has(guild.getStringID())) {
            guildJSON = modstuffJSON.getJSONObject(guild.getStringID());
        }
        else {
            guildJSON = new JSONObject();
            modstuffJSON.put(guild.getStringID(), guildJSON);
        }

        guildJSON.put("muteRole", muteRole.getLongID());
        saveJSON();

        message.addReaction(ReactionEmoji.of("✅")); // :white_check_mark:
    }

    @EventSubscriber
    public void onUserJoin(final UserJoinEvent event) {
        // Check if the user that joined should still be muted
        final IUser user = event.getUser();
        if (userMuteFutures.containsKey(event.getGuild()) && userMuteFutures.get(event.getGuild()).containsKey(user)) {
            final IRole muteRole = getMuteRoleForGuild(event.getGuild());
            if (muteRole == null) {
                return;
            }

            user.addRole(muteRole);
        }
    }

    /**
     * Restore all mutes for the specified guild that can be found in the JSON file.
     *
     * @param guild The guild for which to restore the mutes
     */
    private void restoreGuildUserMutes(final IGuild guild) {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        final JSONArray guildUserMutes = getUserMutesJSONForGuild(guild);
        LOG.debug("Found {} mutes for guild.", guildUserMutes.length());

        // First, obtain a list of all user mutes
        final List<Object> userMutes = new ArrayList<>();
        final Iterator<Object> muteIterator = guildUserMutes.iterator();
        muteIterator.forEachRemaining(userMutes::add);

        // Second, clear the array
        for (int i = 0; i < userMutes.size(); i++) {
            guildUserMutes.remove(0);
        }

        // Third, restore all mutes
        for (Object currentObject : userMutes) {
            final JSONObject currentUserMute = (JSONObject)currentObject;

            if (currentUserMute.has("user") && currentUserMute.has("mutedUntil")) {
                final long userLongID = currentUserMute.getLong("user");
                final IUser user = guild.getUserByID(userLongID);
                final String unmuteTimestampString = currentUserMute.getString("mutedUntil");
                final LocalDateTime unmuteTimestamp = LocalDateTime.parse(unmuteTimestampString, formatter);

                if (user == null) {
                    LOG.info("Mute for user with id {} on guild '{}' (ID: {}) could not be restored. User is not a member of the guild",
                            userLongID, guild.getName(), guild.getStringID());
                }
                else if (LocalDateTime.now().isBefore(unmuteTimestamp)) {
                    final int delaySeconds = (int)LocalDateTime.now().until(unmuteTimestamp, ChronoUnit.SECONDS);
                    muteUserForGuild(user, guild, delaySeconds, ChronoUnit.SECONDS, null);
                    LOG.info("Restored mute for user '{}' (ID: {}) on guild '{}' (ID: {}). Muted until {}",
                            UserUtils.makeUserString(user, guild), user.getStringID(),
                            guild.getName(), guild.getStringID(),
                            unmuteTimestampString);
                }
                else {
                    LOG.info("Mute for user '{}' (ID: {}) on guild '{}' (ID: {}) was found, but mute duration has elapsed. Removing from JSON.",
                            UserUtils.makeUserString(user, guild), user.getStringID(),
                            guild.getName(), guild.getStringID());
                }
            }
            else {
                LOG.warn(String.format("userMute doesn't contain necessary keys! Skipping. userMute: %s", currentUserMute.toString(4)));
            }
        }

        // Update JSON
        saveUserMutes();
    }

    private void restoreGuildChannelMutes(final IGuild guild) {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        final JSONObject guildChannels = getChannelMutesJSONForGuild(guild);
        for (String channelID : guildChannels.keySet()) {
            final IChannel channel = guild.getChannelByID(Long.parseLong(channelID));
            final JSONArray channelMutes = guildChannels.getJSONArray(channelID);

            final Iterator<Object> muteIterator = channelMutes.iterator();
            while (muteIterator.hasNext()) {
                final JSONObject currentMute = (JSONObject) muteIterator.next();

                if (currentMute.has("user") && currentMute.has("mutedUntil")) {
                    final long userLongID = currentMute.getLong("user");
                    final IUser user = guild.getUserByID(userLongID);
                    final String unmuteTimestampString = currentMute.getString("mutedUntil");
                    final LocalDateTime unmuteTimestamp = LocalDateTime.parse(unmuteTimestampString, formatter);

                    if (LocalDateTime.now().isBefore(unmuteTimestamp)) {
                        final int delaySeconds = (int)LocalDateTime.now().until(unmuteTimestamp, ChronoUnit.SECONDS);
                        muteUserForChannel(user, channel, delaySeconds, ChronoUnit.SECONDS);

                        LOG.info("Restored mute for user '{}' (ID: {}) in channel '{}' (ID:{}) on guild '{}' (ID: {}). Muted until {}",
                                UserUtils.makeUserString(user, guild), user.getStringID(),
                                channel.getName(), channel.getStringID(),
                                guild.getName(), guild.getStringID(),
                                unmuteTimestampString);
                    }
                    else {
                        LOG.info("Mute for user '{}' (ID: {}) in channel '{}' (ID:{}) on guild '{}' (ID: {}) was found, but mute duration has elapsed. Removing from JSON.",
                                UserUtils.makeUserString(user, guild), user.getStringID(),
                                channel.getName(), channel.getStringID(),
                                guild.getName(), guild.getStringID());
                    }
                }
                else {
                    LOG.warn(String.format("channelMute doesn't contain necessary keys! Skipping. userMute: %s", currentMute.toString(4)));
                }
            }
        }

        // Update JSON
        getJSONForGuild(guild).remove("channelMutes");
        saveChannelMutes();
    }

    private IRole getMuteRoleForGuild(final IGuild guild) {
        final JSONObject guildJSON = getJSONForGuild(guild);
        if (guildJSON.has("muteRole")) {
            final long muteRoleID = guildJSON.getLong("muteRole");
            final IRole muteRole = guild.getRoleByID(muteRoleID);
            if (muteRole != null) {
                return muteRole;
            }
            else {
                LOG.warn(String.format("Auf dem Server %s (ID: %s) wurde keine Rolle mit der ID %s gefunden!", guild.getName(), guild.getStringID(), muteRoleID));
                return null;
            }
        }
        else {
            LOG.warn(String.format("Keine Mute Rolle für Server %s (ID: %s) angegeben.",
                    guild.getName(), guild.getStringID()));
            return null;
        }
    }

    private IChannel getModlogChannelForGuild(final IGuild guild) {
        final JSONObject guildJSON = getJSONForGuild(guild);
        if (guildJSON.has("modlogChannel")) {
            final long modlogChannelID = guildJSON.getLong("modlogChannel");
            final IChannel modlogChannel = guild.getChannelByID(modlogChannelID);
            if (modlogChannel != null) {
                return modlogChannel;
            }
            else {
                LOG.warn(String.format("Auf dem Server %s (ID: %s) wurde kein Channel mit der ID %s gefunden!",
                        guild.getName(), guild.getStringID(), modlogChannelID));
                return null;
            }
        }
        else {
            LOG.warn(String.format("Kein Modlog Channel für Server %s (ID: %s) angegeben.",
                    guild.getName(), guild.getStringID()));
            return null;
        }
    }

    private List<String> getVoiceLogForGuild(final IGuild guild) {
        if (voiceLog.containsKey(guild)) {
            final List<String> log = voiceLog.get(guild);
            final int maxSize = 100;
            if (log.size() > maxSize) {
                final List<String> newLog = log.subList(log.size() - maxSize, log.size());
                voiceLog.put(guild, newLog);
                return newLog;
            }
            else {
                return log;
            }
        }
        else {
            final List<String> guildVoiceLog = new ArrayList<>();
            voiceLog.put(guild, guildVoiceLog);
            return guildVoiceLog;
        }
    }

    private JSONArray getUserMutesJSONForGuild(final IGuild guild) {
        final JSONObject guildJSON = getJSONForGuild(guild);
        if (guildJSON.has("userMutes")) {
            return guildJSON.getJSONArray("userMutes");
        }
        else {
            final JSONArray jsonArray = new JSONArray();
            guildJSON.put("userMutes", jsonArray);
            return jsonArray;
        }
    }

    private JSONObject getChannelMutesJSONForGuild(final IGuild guild) {
        final JSONObject guildJSON = getJSONForGuild(guild);
        if (guildJSON.has("channelMutes")) {
            return guildJSON.getJSONObject("channelMutes");
        }
        else {
            final JSONObject jsonObject = new JSONObject();
            guildJSON.put("channelMutes", jsonObject);
            return jsonObject;
        }
    }

    private JSONObject getJSONForGuild(final IGuild guild) {
        return getJSONForGuild(guild, true);
    }

    private JSONObject getJSONForGuild(final IGuild guild, final boolean createIfNull) {
        if (modstuffJSON.has(guild.getStringID())) {
            // JSON for guild exists
            return modstuffJSON.getJSONObject(guild.getStringID());

        }
        else {
            // JSON for guild doesn't exist
            if (createIfNull) {
                final JSONObject guildJSON = new JSONObject();
                modstuffJSON.put(guild.getStringID(), guildJSON);
                return guildJSON;
            }
            else {
                LOG.warn(String.format("No JSON Entry found for guild '%s' (ID: %s)",
                        guild.getName(), guild.getStringID()));
                return null;
            }
        }
    }


    private void saveUserMutes() {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (Entry<IGuild, Map<IUser, ScheduledFuture>> guildEntry : userMuteFutures.entrySet()) {
            final JSONArray guildUserMutesJSON = getUserMutesJSONForGuild(guildEntry.getKey());

            // Clear JSON
            final int arraySize = guildUserMutesJSON.length();
            for (int i = 0; i < arraySize; i++) {
                guildUserMutesJSON.remove(0);
            }

            final Map<IUser, ScheduledFuture> guildUserMutesMap = guildEntry.getValue();

            for (Entry<IUser, ScheduledFuture> userEntry : guildUserMutesMap.entrySet()) {
                final JSONObject entryObject = new JSONObject();
                entryObject.put("user", userEntry.getKey().getLongID());

                final ScheduledFuture unmutefuture = userEntry.getValue();
                final long delay = unmutefuture.getDelay(TimeUnit.SECONDS);
                final LocalDateTime unmuteTimestamp = LocalDateTime.now().plusSeconds(delay);
                entryObject.put("mutedUntil", unmuteTimestamp.format(formatter));
                guildUserMutesJSON.put(entryObject);
            }
        }

        saveJSON();
    }

    private void saveChannelMutes() {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // Iterate over all guilds
        for (Entry<IGuild, Map<IChannel, Map<IUser, ScheduledFuture>>> guildEntry : channelMuteFutures.entrySet()) {
            final IGuild guild = guildEntry.getKey();

            final Map<IChannel, Map<IUser, ScheduledFuture>> guildChannels = guildEntry.getValue();

            // Clear JSON
            final JSONObject guildChannelsJSON = new JSONObject();
            getJSONForGuild(guild).put("channelMutes", guildChannelsJSON);

            // Iterate over all channels for that guild
            for (Entry<IChannel, Map<IUser, ScheduledFuture>> channelEntry : guildChannels.entrySet()) {
                final Map<IUser, ScheduledFuture> channelMutes = channelEntry.getValue();

                final JSONArray channelMutesJSON = new JSONArray();
                guildChannelsJSON.put(channelEntry.getKey().getStringID(), channelMutesJSON);

                // Iterator over all mutes for that channel
                for (Entry<IUser, ScheduledFuture> userEntry : channelMutes.entrySet()) {
                    final JSONObject entryObject = new JSONObject();
                    entryObject.put("user", userEntry.getKey().getLongID());

                    final ScheduledFuture unmutefuture = userEntry.getValue();
                    final long delay = unmutefuture.getDelay(TimeUnit.SECONDS);
                    final LocalDateTime unmuteTimestamp = LocalDateTime.now().plusSeconds(delay);
                    entryObject.put("mutedUntil", unmuteTimestamp.format(formatter));
                    channelMutesJSON.put(entryObject);
                }
            }
        }

        saveJSON();
    }

    private synchronized void saveJSON() {
        LOG.debug("Saving modstuff file.");

        final String jsonOutput = this.modstuffJSON.toString(4);
        IOUtil.writeToFile(MODSTUFF_PATH, jsonOutput);
    }
}
