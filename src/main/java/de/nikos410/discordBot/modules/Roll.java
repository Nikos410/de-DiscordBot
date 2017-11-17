package de.nikos410.discordBot.modules;

import de.nikos410.discordBot.util.general.Util;
import de.nikos410.discordBot.util.modular.annotations.CommandModule;
import de.nikos410.discordBot.util.modular.CommandPermissions;
import de.nikos410.discordBot.util.modular.annotations.CommandSubscriber;
import sx.blah.discord.api.internal.json.objects.EmbedObject;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.util.EmbedBuilder;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.stream.IntStream;

@CommandModule(moduleName = "Würfel", commandOnly = true)
public class Roll {
    private final static int DEFAULT_DOT_COUNT = 6;

    private final static SecureRandom rng;
    static {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(System.currentTimeMillis());
        rng = new SecureRandom(buffer.array());
    }

    @CommandSubscriber(command = "roll",help = "Würfeln. Syntax: `roll AnzahlWuerfel;[AugenJeWuerfel=6]`",
            pmAllowed = true, permissionLevel = CommandPermissions.EVERYONE)
    public void command_Roll(final IMessage commandMessage) throws InterruptedException {
        final String messageContent = commandMessage.getContent();
        final IChannel channel = commandMessage.getChannel();
        final String context = Util.getContext(messageContent);

        final EmbedBuilder outputBuilder = new EmbedBuilder();
        if (context.matches("^[0-9]+;?[0-9]*")) {
            try {
                final String[] args = context.split(";");
                final int diceCount = Integer.parseInt(args[0]);
                final int dotCount = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_DOT_COUNT;
                if (diceCount < 1 || dotCount < 1) {
                    throw new NumberFormatException("Würfelanzahl und maximale Augenzahl muss größer als 0 sein!");
                }
                final StringBuilder resultBuilder = new StringBuilder();
                final int sum = IntStream.generate(() -> (rng.nextInt(dotCount) + 1))
                        .limit(diceCount)
                        .reduce(0, (int acc, int number) -> {
                            resultBuilder.append(number);
                            resultBuilder.append("\n");
                            return acc + number;
                        });
                resultBuilder.append(MessageFormat.format("Sum: {0}", sum));
                outputBuilder.appendField(
                        MessageFormat.format("Würfelt {0} Würfel mit einer maximalen Augenzahl von {1}!", diceCount, dotCount),
                        resultBuilder.toString(),
                        false
                );
                EmbedObject rollObject = outputBuilder.build();
                Util.sendBufferedEmbed(channel, rollObject);
            } catch (NumberFormatException ex) {
                Util.sendMessage(channel, MessageFormat.format("Konnte Eingabe '{0}' nicht verarbeiten." +
                        "Bitte sicherstellen, dass sowohl die Würfelanzahl als auch die maximale Augenzahl Integer-Zahlen > 0 sind!", context));
            }
        } else {
            Util.sendMessage(channel, "Syntax: `roll AnzahlWürfel;[AugenJeWürfel=6]`");
        }
    }
}
