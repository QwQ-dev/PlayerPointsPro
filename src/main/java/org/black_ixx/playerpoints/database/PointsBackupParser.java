package org.black_ixx.playerpoints.database;

import org.black_ixx.playerpoints.models.TemporaryPointGrant;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PointsBackupParser {

    public static final int CURRENT_VERSION = 1;

    private PointsBackupParser() {

    }

    public static PointsBackup load(File file, long now) throws IOException {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (InvalidConfigurationException e) {
            throw new IOException("storage.yml is not valid YAML", e);
        }
        return parse(configuration, now);
    }

    static PointsBackup parse(ConfigurationSection configuration, long now) throws IOException {
        boolean versioned = hasKey(configuration, "Backup-Version");
        if (versioned && readWholeNumber(configuration.get("Backup-Version"), "Backup-Version") != CURRENT_VERSION)
            throw new IOException("Unsupported storage.yml backup version");

        ConfigurationSection pointsSection;
        if (hasKey(configuration, "Points")) {
            pointsSection = requireSection(configuration, "Points");
        } else if (hasKey(configuration, "Players")) {
            pointsSection = requireSection(configuration, "Players");
        } else {
            throw new IOException("storage.yml does not contain a Points section");
        }

        ConfigurationSection usernamesSection = optionalSection(configuration, "UUIDs", versioned);
        ConfigurationSection temporarySection = optionalSection(configuration, "TemporaryPoints", versioned);
        Map<UUID, Integer> permanentPoints = parsePermanentPoints(pointsSection);
        Map<UUID, String> usernames = parseUsernames(usernamesSection);
        List<TemporaryPointGrant> grants = parseTemporaryGrants(temporarySection, permanentPoints.keySet(), now);
        return new PointsBackup(permanentPoints, usernames, grants);
    }

    private static Map<UUID, Integer> parsePermanentPoints(ConfigurationSection section) throws IOException {
        Map<UUID, Integer> points = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            UUID playerId = readUuid(key, "permanent balance account");
            if (points.containsKey(playerId))
                throw new IOException("Duplicate permanent balance account " + key);
            long amount = readWholeNumber(section.get(key), "permanent balance for " + key);
            if (amount < 0 || amount > Integer.MAX_VALUE)
                throw new IOException("Invalid permanent balance for " + key);
            points.put(playerId, (int) amount);
        }
        return points;
    }

    private static Map<UUID, String> parseUsernames(ConfigurationSection section) throws IOException {
        Map<UUID, String> usernames = new LinkedHashMap<>();
        if (section == null)
            return usernames;

        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (!(value instanceof String))
                throw new IOException("Invalid username for " + key);
            UUID playerId = readUuid(key, "username account");
            if (usernames.containsKey(playerId))
                throw new IOException("Duplicate username account " + key);
            usernames.put(playerId, (String) value);
        }
        return usernames;
    }

    private static List<TemporaryPointGrant> parseTemporaryGrants(ConfigurationSection section,
                                                                  Set<UUID> accounts,
                                                                  long now) throws IOException {
        List<TemporaryPointGrant> grants = new ArrayList<>();
        if (section == null)
            return grants;

        Set<UUID> grantIds = new HashSet<>();
        for (String key : section.getKeys(false)) {
            UUID grantId = readUuid(key, "temporary grant");
            if (!grantIds.add(grantId))
                throw new IOException("Duplicate temporary grant " + key);

            Object value = section.get(key);
            if (!(value instanceof ConfigurationSection))
                throw new IOException("Temporary grant " + key + " is not a section");
            ConfigurationSection grantSection = (ConfigurationSection) value;
            Object playerIdValue = grantSection.get("uuid");
            if (!(playerIdValue instanceof String))
                throw new IOException("Invalid account UUID for temporary grant " + key);

            UUID playerId = readUuid((String) playerIdValue, "temporary grant account");
            if (!accounts.contains(playerId))
                throw new IOException("Temporary grant " + key + " references a missing account");

            long amount = readWholeNumber(grantSection.get("amount"), "temporary grant amount");
            long expiresAt = readWholeNumber(grantSection.get("expires-at"), "temporary grant expiry");
            if (amount <= 0 || amount > Integer.MAX_VALUE)
                throw new IOException("Invalid amount for temporary grant " + key);
            if (expiresAt > now)
                grants.add(new TemporaryPointGrant(grantId, playerId, (int) amount, expiresAt));
        }
        return grants;
    }

    private static ConfigurationSection optionalSection(ConfigurationSection parent, String key,
                                                        boolean required) throws IOException {
        if (!hasKey(parent, key)) {
            if (required)
                throw new IOException("Versioned storage.yml is missing the " + key + " section");
            return null;
        }
        return requireSection(parent, key);
    }

    private static ConfigurationSection requireSection(ConfigurationSection parent, String key) throws IOException {
        Object value = parent.get(key);
        if (!(value instanceof ConfigurationSection))
            throw new IOException(key + " must be a section");
        return (ConfigurationSection) value;
    }

    private static boolean hasKey(ConfigurationSection section, String key) {
        return section.getKeys(false).contains(key);
    }

    private static UUID readUuid(String value, String description) throws IOException {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid UUID for " + description + ": " + value, e);
        }
        if (!parsed.toString().equalsIgnoreCase(value))
            throw new IOException("Invalid UUID for " + description + ": " + value);
        return parsed;
    }

    private static long readWholeNumber(Object value, String description) throws IOException {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long))
            throw new IOException(description + " must be a whole number");
        return ((Number) value).longValue();
    }

}
