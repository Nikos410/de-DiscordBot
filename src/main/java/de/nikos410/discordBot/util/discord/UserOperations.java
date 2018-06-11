package de.nikos410.discordBot.util.discord;

import sx.blah.discord.handle.obj.IGuild;
import sx.blah.discord.handle.obj.IRole;
import sx.blah.discord.handle.obj.IUser;

import java.util.List;

public class UserOperations {

    public static IRole getTopRole(final IUser user, final IGuild guild) {
        final List<IRole> roles = user.getRolesForGuild(guild);
        IRole topRole = guild.getEveryoneRole();

        for (IRole role : roles) {
            if (role.getPosition() > topRole.getPosition()) {
                topRole = role;
            }
        }
        return topRole;
    }

    public static boolean hasRole(final IUser user, final IRole role, final IGuild guild) {
        return hasRoleByID(user, role.getLongID(), guild);
    }

    public static boolean hasRoleByID(final IUser user, final long roleID, final IGuild guild) {
        final List<IRole> roles = user.getRolesForGuild(guild);

        for (IRole role : roles) {
            if (roleID == role.getLongID()) {
                return true;
            }
        }
        return false;
    }

    public static String makeUserString(final IUser user, final IGuild guild) {
        final String name = user.getName();
        final String displayName = user.getDisplayName(guild);

        if (name.equals(displayName)) {
            return name;
        }
        else {
            return String.format("%s (%s#%s)", displayName, name, user.getDiscriminator());
        }

    }
}