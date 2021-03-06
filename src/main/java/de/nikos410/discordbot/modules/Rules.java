package de.nikos410.discordbot.modules;


import de.nikos410.discordbot.framework.CommandModule;
import de.nikos410.discordbot.framework.PermissionLevel;
import de.nikos410.discordbot.framework.annotations.CommandParameter;
import de.nikos410.discordbot.framework.annotations.CommandSubscriber;
import de.nikos410.discordbot.util.discord.UserUtils;
import de.nikos410.discordbot.util.io.IOUtil;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.handle.impl.events.guild.member.UserJoinEvent;
import sx.blah.discord.handle.obj.IGuild;
import sx.blah.discord.handle.obj.IMessage;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Rules extends CommandModule {
    private static final Logger LOG = LoggerFactory.getLogger(Rules.class);
    
    private static final Path RULES_PATH = Paths.get("data/rules.json");
    private final JSONObject rulesJSON;

    public Rules () {
        // Read configuration
        final String welcomeFileContent = IOUtil.readFile(RULES_PATH);
        this.rulesJSON = new JSONObject(welcomeFileContent);
    }

    @Override
    public String getDisplayName() {
        return "Regeln";
    }

    @Override
    public String getDescription() {
        return "Einfaches verwalten und Bereitstellen von Regeln für einen Server.";
    }

    @Override
    public boolean hasEvents() {
        return true;
    }

    @EventSubscriber
    public void onUserJoin(UserJoinEvent event) {
        final IGuild guild = event.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        if (guildJSON.has("welcome") && guildJSON.has("rulesDE") && guildJSON.has("footer") && !event.getUser().isBot()) {

            messageService.sendMessage(event.getUser().getOrCreatePMChannel(), guildJSON.getString("welcome") +
                    "\n\n" + guildJSON.getString("rulesDE") + "\n\n\n" + guildJSON.getString("footer"));
        }
    }

    @CommandSubscriber(command = "regeln", help = "Die Regeln dieses Servers", pmAllowed = false)
    public void command_regeln(final IMessage message) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        if (guildJSON.has("rulesDE")) {
            messageService.sendMessage(message.getAuthor().getOrCreatePMChannel(), guildJSON.getString("rulesDE"));

            if (!message.getChannel().isPrivate()) {
                messageService.sendMessage(message.getChannel(), ":mailbox_with_mail:");
            }
        }
        else {
            messageService.sendMessage(message.getChannel(), "Keine Regeln für diesen Server hinterlegt.");
        }
    }

    @CommandSubscriber(command = "rules", help = "The rules of this server", pmAllowed = false)
    public void command_rules(final IMessage message) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        if (guildJSON.has("rulesEN")) {
            messageService.sendMessage(message.getAuthor().getOrCreatePMChannel(), guildJSON.getString("rulesEN"));

            if (!message.getChannel().isPrivate()) {
                messageService.sendMessage(message.getChannel(), ":mailbox_with_mail:");
            }
        }
        else {
            messageService.sendMessage(message.getChannel(), "No rules found for this server.");
        }
    }

    @CommandSubscriber(command = "welcomeTest", help = "Begrüßungsnachricht testen", permissionLevel = PermissionLevel.ADMIN,
            pmAllowed = false)
    public void command_welcomeTest(final IMessage message) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        if (guildJSON.has("welcome") && guildJSON.has("rulesDE") && guildJSON.has("footer")) {

            messageService.sendMessage(message.getChannel(), guildJSON.getString("welcome") +
                    "\n\n" + guildJSON.getString("rulesDE") + "\n\n\n" + guildJSON.getString("footer"));
        }
    }

    @CommandSubscriber(command = "enableWelcome", help = "Begrüßungsnachricht aktivieren", permissionLevel = PermissionLevel.ADMIN, pmAllowed = false)
    public void command_enableWelcome(final IMessage message) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        guildJSON.put("on", true);
        saveJSON();

        messageService.sendMessage(message.getChannel(), ":white_check_mark: Aktiviert!");
        LOG.info(String.format("%s enabled welcome messages for server %s (ID: %s)", UserUtils.makeUserString(message.getAuthor(), message.getGuild()),
                guild.getName(), guild.getStringID()));
    }

    @CommandSubscriber(command = "disableWelcome", help = "Begrüßungsnachricht deaktivieren", permissionLevel = PermissionLevel.ADMIN, pmAllowed = false)
    public void command_disableWelcome(final IMessage message) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        guildJSON.put("on", false);
        saveJSON();

        messageService.sendMessage(message.getChannel(), ":white_check_mark: Deaktiviert!");
        LOG.info(String.format("%s disabled welcome messages for server %s (ID: %s)", UserUtils.makeUserString(message.getAuthor(), message.getGuild()),
                guild.getName(), guild.getStringID()));
    }

    @CommandSubscriber(command = "setWelcome", help = "Begrüßungsnachricht ändern", permissionLevel = PermissionLevel.ADMIN, pmAllowed = false)
    public void command_setWelcome(final IMessage message,
                                   @CommandParameter(name = "Nachricht", help = "Die Begrüßungsnachricht.")
                                   final String welcomeMessage) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        guildJSON.put("welcome", welcomeMessage);
        saveJSON();

        messageService.sendMessage(message.getChannel(), ":white_check_mark: Begrüßungs-Nachricht geändert:");
        messageService.sendMessage(message.getChannel(), welcomeMessage);
    }

    @CommandSubscriber(command = "setRegeln", help = "Regeln (deutsch) ändern", permissionLevel = PermissionLevel.ADMIN, pmAllowed = false)
    public void command_setRegeln(final IMessage message,
                                  @CommandParameter(name = "Regeln", help = "Die Regeln für den Server. (Auf Deutsch)")
                                  final String rulesDE) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        guildJSON.put("rulesDE", rulesDE);
        saveJSON();

        messageService.sendMessage(message.getChannel(), ":white_check_mark: Regeln (DE) geändert:");
        messageService.sendMessage(message.getChannel(), rulesDE);
        LOG.info(String.format("%s changed rules. (DE)", UserUtils.makeUserString(message.getAuthor(), message.getGuild())));
    }

    @CommandSubscriber(command = "setRules", help = "Regeln (englisch) ändern", permissionLevel = PermissionLevel.ADMIN, pmAllowed = false)
    public void command_setRules(final IMessage message,
                                 @CommandParameter(name = "Regeln", help = "Die Regeln für den Server. (Auf Englisch)")
                                 final String rulesEN) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        guildJSON.put("rulesEN", rulesEN);
        saveJSON();

        messageService.sendMessage(message.getChannel(), ":white_check_mark: Regeln (EN) geändert:");
        messageService.sendMessage(message.getChannel(), rulesEN);
        LOG.info(String.format("%s changed rules. (EN)", UserUtils.makeUserString(message.getAuthor(), message.getGuild())));
    }

    @CommandSubscriber(command = "setFooter", help = "Footer der Begüßungsnachricht ändern.",
            permissionLevel = PermissionLevel.ADMIN, pmAllowed = false)
    public void command_setFooter(final IMessage message,
                                  @CommandParameter(name = "Footer", help = "Der Text, der am Ende der Begrüßung angezeigt werden soll.")
                                  final String footer) {
        final IGuild guild = message.getGuild();
        final JSONObject guildJSON = getJSONForGuild(guild);

        guildJSON.put("footer", footer);
        saveJSON();

        messageService.sendMessage(message.getChannel(), ":white_check_mark: Begrüßungs-Footer geändert:");
        messageService.sendMessage(message.getChannel(), footer);
        LOG.info(String.format("%s changed rules. (DE)", UserUtils.makeUserString(message.getAuthor(), message.getGuild())));
    }

    private JSONObject getJSONForGuild (final IGuild guild) {
        if (rulesJSON.has(guild.getStringID())) {
            return rulesJSON.getJSONObject(guild.getStringID());
        }
        else {
            final JSONObject guildJSON = new JSONObject();
            rulesJSON.put(guild.getStringID(), guildJSON);
            return guildJSON;
        }
    }

    private void saveJSON() {
        LOG.debug("Saving rules file.");
        final String jsonOutput = rulesJSON.toString(4);
        IOUtil.writeToFile(RULES_PATH, jsonOutput);
    }
}
