package org.black_ixx.playerpoints.database;

import org.black_ixx.playerpoints.models.TemporaryPointGrant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class TemporaryPointsStorage {

    private final String tableName;
    private final boolean supportsSelectForUpdate;

    public TemporaryPointsStorage(String tableName, boolean supportsSelectForUpdate) {
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.supportsSelectForUpdate = supportsSelectForUpdate;
    }

    private static void requireSuccessfulBatch(int[] results, int expected) throws SQLException {
        if (results.length != expected)
            throw new SQLException("Database returned an incomplete batch result");
        for (int result : results) {
            if (result != 1 && result != Statement.SUCCESS_NO_INFO)
                throw new SQLException("A temporary points row changed during a bulk update");
        }
    }

    String getTableName() {
        return this.tableName;
    }

    public List<TemporaryPointGrant> loadActive(Connection connection, UUID playerId, long now) throws SQLException {
        String query = "SELECT grant_id, points, expires_at FROM " + this.tableName
                + " WHERE uuid = ? AND expires_at > ? ORDER BY expires_at ASC, grant_id ASC"
                + (this.supportsSelectForUpdate ? " FOR UPDATE" : "");
        List<TemporaryPointGrant> grants = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    grants.add(new TemporaryPointGrant(UUID.fromString(result.getString("grant_id")), playerId,
                            result.getInt("points"), result.getLong("expires_at")));
            }
        }
        return grants;
    }

    Map<UUID, List<TemporaryPointGrant>> loadAllActive(Connection connection, long now)
            throws SQLException {
        String query = "SELECT uuid, grant_id, points, expires_at FROM " + this.tableName
                + " WHERE expires_at > ? ORDER BY uuid ASC, expires_at ASC, grant_id ASC"
                + (this.supportsSelectForUpdate ? " FOR UPDATE" : "");
        Map<UUID, List<TemporaryPointGrant>> grants = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID playerId = UUID.fromString(result.getString("uuid"));
                    grants.computeIfAbsent(playerId, ignored -> new ArrayList<>())
                            .add(new TemporaryPointGrant(
                                    UUID.fromString(result.getString("grant_id")), playerId,
                                    result.getInt("points"), result.getLong("expires_at")));
                }
            }
        }
        return grants;
    }

    public void insert(Connection connection, TemporaryPointGrant grant) throws SQLException {
        String query = "INSERT INTO " + this.tableName + " (grant_id, uuid, points, expires_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, grant.getGrantId().toString());
            statement.setString(2, grant.getPlayerId().toString());
            statement.setInt(3, grant.getAmount());
            statement.setLong(4, grant.getExpiresAt());
            statement.executeUpdate();
        }
    }

    public int consume(Connection connection, UUID playerId, int amount, long now) throws SQLException {
        return this.consume(connection, this.loadActive(connection, playerId, now), amount);
    }

    int consume(Connection connection, List<TemporaryPointGrant> grants, int amount)
            throws SQLException {
        if (amount <= 0)
            throw new IllegalArgumentException("amount must be positive");

        int remaining = amount;
        int consumed = 0;
        for (TemporaryPointGrant grant : grants) {
            int fromGrant = Math.min(remaining, grant.getAmount());
            if (fromGrant == grant.getAmount()) {
                this.delete(connection, grant.getGrantId());
            } else {
                this.updateAmount(connection, grant.getGrantId(), grant.getAmount() - fromGrant);
            }

            remaining -= fromGrant;
            consumed += fromGrant;
            if (remaining == 0)
                break;
        }
        return consumed;
    }

    void consumeBatch(Connection connection,
                      Map<UUID, List<TemporaryPointGrant>> grantsByAccount,
                      Map<UUID, Integer> amounts) throws SQLException {
        if (amounts.isEmpty())
            return;

        String deleteQuery = "DELETE FROM " + this.tableName + " WHERE grant_id = ?";
        String updateQuery = "UPDATE " + this.tableName + " SET points = ? WHERE grant_id = ?";
        int deletes = 0;
        int updates = 0;
        try (PreparedStatement delete = connection.prepareStatement(deleteQuery);
             PreparedStatement update = connection.prepareStatement(updateQuery)) {
            for (Map.Entry<UUID, Integer> entry : amounts.entrySet()) {
                int remaining = entry.getValue();
                if (remaining <= 0)
                    throw new IllegalArgumentException("amounts must be positive");

                for (TemporaryPointGrant grant : grantsByAccount.getOrDefault(
                        entry.getKey(), Collections.emptyList())) {
                    int consumed = Math.min(remaining, grant.getAmount());
                    if (consumed == grant.getAmount()) {
                        delete.setString(1, grant.getGrantId().toString());
                        delete.addBatch();
                        deletes++;
                    } else {
                        update.setInt(1, grant.getAmount() - consumed);
                        update.setString(2, grant.getGrantId().toString());
                        update.addBatch();
                        updates++;
                    }

                    remaining -= consumed;
                    if (remaining == 0)
                        break;
                }
                if (remaining != 0)
                    throw new SQLException("Temporary balance changed during bulk update for "
                            + entry.getKey());
            }

            requireSuccessfulBatch(delete.executeBatch(), deletes);
            requireSuccessfulBatch(update.executeBatch(), updates);
        }
    }

    public void clear(Connection connection, UUID playerId) throws SQLException {
        String query = "DELETE FROM " + this.tableName + " WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    public int deleteExpired(Connection connection, long now) throws SQLException {
        String query = "DELETE FROM " + this.tableName + " WHERE expires_at <= ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }

    private void delete(Connection connection, UUID grantId) throws SQLException {
        String query = "DELETE FROM " + this.tableName + " WHERE grant_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, grantId.toString());
            statement.executeUpdate();
        }
    }

    private void updateAmount(Connection connection, UUID grantId, int amount) throws SQLException {
        String query = "UPDATE " + this.tableName + " SET points = ? WHERE grant_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, amount);
            statement.setString(2, grantId.toString());
            statement.executeUpdate();
        }
    }

}
