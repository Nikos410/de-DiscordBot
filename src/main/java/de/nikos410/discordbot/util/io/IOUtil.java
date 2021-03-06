package de.nikos410.discordbot.util.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contains helpers to read and write to files.
 */
public class IOUtil {

    private static final Logger LOG = LoggerFactory.getLogger(IOUtil.class);

    private IOUtil() {
    }

    /**
     * Read the contents of a plaintext file.
     *
     * @param path The path to the file containing the text.
     * @return The contents of the file.
     */
    public static String readFile(final Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        }
        catch (IOException | NullPointerException e){
            LOG.error(String.format("Could not read file from Path \"%s\"", path), e);
            return null;
        }
    }

    /**
     * Write a string to a file.
     *
     * @param path The path to the file which the text will be written to.
     * @param text The string that will be written to the file.
     * @return The path.
     */
    public static Path writeToFile(final Path path, final String text) {
        try {
            final Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            return Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            LOG.error(String.format("Could not write to Path \"%s\"", path), e);
            return null;
        }
    }

}