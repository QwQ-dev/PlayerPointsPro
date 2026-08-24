package org.black_ixx.playerpoints.database;

import org.black_ixx.playerpoints.models.TemporaryPointGrant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public class PointsDatabaseStorage {

    private final String pointsTable;
    private final String accountColumn;
    private final String usernameTable;
    private final TemporaryPointsStorage temporaryStorage;
    private final Dialect dialect;
    private final LongSupplier clock;

    public PointsDatabaseStorage(String pointsTable, String accountColumn, String usernameTable,
                                 TemporaryPointsStorage temporaryStorage, Dialect dialect) {
        this(pointsTable, accountColumn, usernameTable, temporaryStorage, dialect,
                System::currentTimeMillis);
    }

    PointsDatabaseStorage(String pointsTable, String accountColumn, String usernameTable,
                          TemporaryPointsStorage temporaryStorage, Dialect dialect,
                          LongSupplier clock) {
        this.pointsTable = pointsTable;
        this.accountColumn = accountColumn;
        this.usernameTable = usernameTable;
        this.temporaryStorage = temporaryStorage;
        this.dialect = dialect;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static List<TemporaryPointGrant> validateImport(Map<UUID, Integer> permanent,
                                                            Map<UUID, String> usernames,
                                                            Collection<TemporaryPointGrant> grants,
                                                            long now) {
        if (permanent == null || usernames == null || grants == null)
            return null;
        Map<UUID, Long> combined = new HashMap<>();
        for (Map.Entry<UUID, Integer> entry : permanent.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)
                return null;
            combined.put(entry.getKey(), entry.getValue().longValue());
        }
        for (Map.Entry<UUID, String> entry : usernames.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null)
                return null;
        }

        Set<UUID> grantIds = new HashSet<>();
        List<TemporaryPointGrant> active = new ArrayList<>();
        for (TemporaryPointGrant grant : grants) {
            if (grant == null || !grantIds.add(grant.getGrantId()))
                return null;
            if (grant.getExpiresAt() <= now)
                continue;
            Long total = combined.get(grant.getPlayerId());
            if (total == null)
                return null;
            long updated = Math.addExact(total, grant.getAmount());
            if (updated > Integer.MAX_VALUE)
                return null;
            combined.put(grant.getPlayerId(), updated);
            active.add(grant);
        }
        return active;
    }

    private static long sumGrants(Collection<TemporaryPointGrant> grants) {
        long sum = 0;
        for (TemporaryPointGrant grant : grants)
            sum = Math.addExact(sum, grant.getAmount());
        return sum;
    }

    private static String uuidSignOrder(String account, int position) {
        return "CASE WHEN LOWER(SUBSTR(" + account + ", " + position
                + ", 1)) IN ('8','9','a','b','c','d','e','f') THEN 0 ELSE 1 END";
    }

    public boolean withdraw(Connection connection, UUID playerId, int amount, boolean permanentOnly,
                            int startingBalance, long now) throws SQLException {
        return this.withdraw(connection, playerId, amount, permanentOnly, startingBalance, () -> now);
    }

    public boolean withdraw(Connection connection, UUID playerId, int amount, boolean permanentOnly,
                            int startingBalance) throws SQLException {
        return this.withdraw(connection, playerId, amount, permanentOnly, startingBalance, this.clock);
    }

    private boolean withdraw(Connection connection, UUID playerId, int amount, boolean permanentOnly,
                             int startingBalance, LongSupplier clock) throws SQLException {
        if (amount <= 0)
            return false;

        int permanent = this.lockAccount(connection, playerId, startingBalance);
        return this.withdrawLocked(connection, playerId, amount, permanentOnly,
                permanent, clock.getAsLong());
    }

    public boolean withdrawPermanent(Connection connection, UUID playerId, int amount,
                                     int startingBalance, long now) throws SQLException {
        return this.withdraw(connection, playerId, amount, true, startingBalance, () -> now);
    }

    public boolean withdrawPermanent(Connection connection, UUID playerId, int amount,
                                     int startingBalance) throws SQLException {
        return this.withdraw(connection, playerId, amount, true, startingBalance, this.clock);
    }

    public boolean withdrawTemporary(Connection connection, UUID playerId, int amount,
                                     int startingBalance, long now) throws SQLException {
        return this.withdrawTemporary(
                connection, playerId, amount, startingBalance, () -> now);
    }

    public boolean withdrawTemporary(Connection connection, UUID playerId, int amount,
                                     int startingBalance) throws SQLException {
        return this.withdrawTemporary(
                connection, playerId, amount, startingBalance, this.clock);
    }

    private boolean withdrawTemporary(Connection connection, UUID playerId, int amount,
                                      int startingBalance, LongSupplier clock) throws SQLException {
        if (amount <= 0)
            return false;

        this.lockAccount(connection, playerId, startingBalance);
        List<TemporaryPointGrant> grants = this.temporaryStorage.loadActive(
                connection, playerId, clock.getAsLong());
        if (sumGrants(grants) < amount)
            return false;

        return this.temporaryStorage.consume(connection, grants, amount) == amount;
    }

    private boolean withdrawLocked(Connection connection, UUID playerId, int amount,
                                   boolean permanentOnly, int permanent,
                                   long now) throws SQLException {
        List<TemporaryPointGrant> grants = permanentOnly
                ? Collections.emptyList()
                : this.temporaryStorage.loadActive(connection, playerId, now);
        long temporary = sumGrants(grants);
        long available = permanentOnly ? permanent : Math.addExact(permanent, temporary);
        if (available < amount)
            return false;

        int temporaryConsumed = permanentOnly ? 0
                : this.temporaryStorage.consume(connection, grants, amount);
        int permanentConsumed = amount - temporaryConsumed;
        this.updatePermanent(connection, playerId, permanent - permanentConsumed);
        return true;
    }

    public boolean grantTemporary(Connection connection, TemporaryPointGrant grant,
                                  int startingBalance, long now) throws SQLException {
        return this.grantTemporary(connection, grant, startingBalance, () -> now);
    }

    public boolean grantTemporary(Connection connection, TemporaryPointGrant grant,
                                  int startingBalance) throws SQLException {
        return this.grantTemporary(connection, grant, startingBalance, this.clock);
    }

    private boolean grantTemporary(Connection connection, TemporaryPointGrant grant,
                                   int startingBalance, LongSupplier clock) throws SQLException {
        int permanent = this.lockAccount(connection, grant.getPlayerId(), startingBalance);
        long now = clock.getAsLong();
        if (grant.getExpiresAt() <= now)
            return false;
        long temporary = sumGrants(this.temporaryStorage.loadActive(connection, grant.getPlayerId(), now));
        long combined = Math.addExact(Math.addExact(permanent, temporary), grant.getAmount());
        if (combined > Integer.MAX_VALUE)
            return false;

        this.temporaryStorage.insert(connection, grant);
        return true;
    }

    public boolean transferPermanent(Connection connection, UUID sourceId, UUID targetId,
                                     int sourceAmount, int targetAmount, int startingBalance,
                                     long now) throws SQLException {
        return this.transferPermanent(connection, sourceId, targetId, sourceAmount, targetAmount,
                startingBalance, () -> now);
    }

    public boolean transferPermanent(Connection connection, UUID sourceId, UUID targetId,
                                     int sourceAmount, int targetAmount,
                                     int startingBalance) throws SQLException {
        return this.transferPermanent(connection, sourceId, targetId, sourceAmount, targetAmount,
                startingBalance, this.clock);
    }

    private boolean transferPermanent(Connection connection, UUID sourceId, UUID targetId,
                                      int sourceAmount, int targetAmount, int startingBalance,
                                      LongSupplier clock) throws SQLException {
        if (sourceAmount <= 0 || targetAmount <= 0 || sourceId.equals(targetId))
            return false;

        List<UUID> lockOrder = new ArrayList<>();
        lockOrder.add(sourceId);
        lockOrder.add(targetId);
        lockOrder.sort(Comparator.comparing(UUID::toString));
        Map<UUID, Integer> permanent = new HashMap<>();
        for (UUID playerId : lockOrder)
            permanent.put(playerId, this.lockAccount(connection, playerId, startingBalance));
        long now = clock.getAsLong();

        int sourcePermanent = permanent.get(sourceId);
        int targetPermanent = permanent.get(targetId);
        if (sourcePermanent < sourceAmount)
            return false;

        long targetTemporary = sumGrants(this.temporaryStorage.loadActive(connection, targetId, now));
        long targetCombined = Math.addExact(Math.addExact(targetPermanent, targetTemporary), targetAmount);
        if (targetCombined > Integer.MAX_VALUE)
            return false;

        this.updatePermanent(connection, sourceId, sourcePermanent - sourceAmount);
        this.updatePermanent(connection, targetId, Math.addExact(targetPermanent, targetAmount));
        return true;
    }

    public boolean deposit(Connection connection, UUID playerId, int amount,
                           int startingBalance, long now) throws SQLException {
        return this.deposit(connection, playerId, amount, startingBalance, () -> now);
    }

    public boolean deposit(Connection connection, UUID playerId, int amount,
                           int startingBalance) throws SQLException {
        return this.deposit(connection, playerId, amount, startingBalance, this.clock);
    }

    private boolean deposit(Connection connection, UUID playerId, int amount,
                            int startingBalance, LongSupplier clock) throws SQLException {
        if (amount < 0)
            return false;
        if (amount == 0)
            return true;

        int permanent = this.lockAccount(connection, playerId, startingBalance);
        return this.depositLocked(connection, playerId, amount, permanent,
                clock.getAsLong());
    }

    private boolean depositLocked(Connection connection, UUID playerId, int amount,
                                  int permanent, long now) throws SQLException {
        long temporary = sumGrants(this.temporaryStorage.loadActive(connection, playerId, now));
        long combined = Math.addExact(Math.addExact(permanent, temporary), amount);
        if (combined > Integer.MAX_VALUE)
            return false;
        this.updatePermanent(connection, playerId, Math.addExact(permanent, amount));
        return true;
    }

    public void set(Connection connection, UUID playerId, int amount, int startingBalance) throws SQLException {
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative");
        this.lockAccount(connection, playerId, startingBalance);
        this.temporaryStorage.clear(connection, playerId);
        this.updatePermanent(connection, playerId, amount);
    }

    public boolean setPermanent(Connection connection, UUID playerId, int amount,
                                int startingBalance) throws SQLException {
        if (amount < 0)
            return false;
        this.lockAccount(connection, playerId, startingBalance);
        long temporary = sumGrants(this.temporaryStorage.loadActive(
                connection, playerId, this.clock.getAsLong()));
        if ((long) amount + temporary > Integer.MAX_VALUE)
            return false;
        this.updatePermanent(connection, playerId, amount);
        return true;
    }

    public boolean replaceTemporary(Connection connection, TemporaryPointGrant grant,
                                    int startingBalance, long now) throws SQLException {
        return this.replaceTemporary(
                connection, grant, startingBalance, () -> now);
    }

    public boolean replaceTemporary(Connection connection, TemporaryPointGrant grant,
                                    int startingBalance) throws SQLException {
        return this.replaceTemporary(
                connection, grant, startingBalance, this.clock);
    }

    private boolean replaceTemporary(Connection connection, TemporaryPointGrant grant,
                                     int startingBalance, LongSupplier clock) throws SQLException {
        int permanent = this.lockAccount(
                connection, grant.getPlayerId(), startingBalance);
        if (grant.getExpiresAt() <= clock.getAsLong()
                || (long) permanent + grant.getAmount() > Integer.MAX_VALUE) {
            return false;
        }

        this.temporaryStorage.clear(connection, grant.getPlayerId());
        this.temporaryStorage.insert(connection, grant);
        return true;
    }

    public void clearTemporary(Connection connection, UUID playerId,
                               int startingBalance) throws SQLException {
        this.lockAccount(connection, playerId, startingBalance);
        this.temporaryStorage.clear(connection, playerId);
    }

    public Optional<Map<UUID, Integer>> offsetAll(Connection connection, int amount, long now)
            throws SQLException {
        return this.offsetAll(connection, amount, () -> now);
    }

    public Optional<Map<UUID, Integer>> offsetAll(Connection connection, int amount)
            throws SQLException {
        return this.offsetAll(connection, amount, this.clock);
    }

    private Optional<Map<UUID, Integer>> offsetAll(Connection connection, int amount,
                                                   LongSupplier clock) throws SQLException {
        if (amount == 0)
            return Optional.of(Collections.emptyMap());

        Map<UUID, Integer> permanent = this.lockAllAccounts(connection);
        long now = clock.getAsLong();
        Map<UUID, List<TemporaryPointGrant>> grantsByAccount =
                this.temporaryStorage.loadAllActive(connection, now);
        Map<UUID, Integer> appliedOffsets = new LinkedHashMap<>();
        Map<UUID, Integer> updatedPermanent = new LinkedHashMap<>();
        if (amount > 0) {
            for (Map.Entry<UUID, Integer> entry : permanent.entrySet()) {
                UUID playerId = entry.getKey();
                long temporary = sumGrants(grantsByAccount.getOrDefault(
                        playerId, Collections.emptyList()));
                long combined = Math.addExact(
                        Math.addExact(entry.getValue().longValue(), temporary), amount);
                if (combined > Integer.MAX_VALUE)
                    return Optional.empty();
                updatedPermanent.put(playerId, Math.addExact(entry.getValue(), amount));
                appliedOffsets.put(playerId, amount);
            }
            this.updatePermanentBatch(connection, updatedPermanent);
            return Optional.of(appliedOffsets);
        }

        Map<UUID, Integer> temporaryConsumptions = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : permanent.entrySet()) {
            UUID playerId = entry.getKey();
            long temporary = sumGrants(grantsByAccount.getOrDefault(
                    playerId, Collections.emptyList()));
            long total = Math.addExact(entry.getValue().longValue(), temporary);
            int withdrawn = (int) Math.min(total, -(long) amount);
            if (withdrawn == 0)
                continue;

            int temporaryConsumed = (int) Math.min(temporary, withdrawn);
            if (temporaryConsumed > 0)
                temporaryConsumptions.put(playerId, temporaryConsumed);
            int permanentConsumed = withdrawn - temporaryConsumed;
            if (permanentConsumed > 0)
                updatedPermanent.put(playerId, entry.getValue() - permanentConsumed);
            appliedOffsets.put(playerId, -withdrawn);
        }
        this.temporaryStorage.consumeBatch(
                connection, grantsByAccount, temporaryConsumptions);
        this.updatePermanentBatch(connection, updatedPermanent);
        return Optional.of(appliedOffsets);
    }

    public void upsertPermanentBalances(Connection connection, Map<UUID, Integer> balances)
            throws SQLException {
        for (Map.Entry<UUID, Integer> entry : balances.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)
                throw new IllegalArgumentException("balances must contain non-negative values");
        }

        List<UUID> playerIds = new ArrayList<>(balances.keySet());
        playerIds.sort(Comparator.comparing(UUID::toString));
        for (UUID playerId : playerIds)
            this.lockAccount(connection, playerId, balances.get(playerId));

        long now = this.clock.getAsLong();
        for (UUID playerId : playerIds) {
            long temporary = sumGrants(this.temporaryStorage.loadActive(connection, playerId, now));
            if ((long) balances.get(playerId) + temporary > Integer.MAX_VALUE) {
                throw new SQLException("Imported balance exceeds the integer limit for " + playerId);
            }
        }
        for (UUID playerId : playerIds)
            this.updatePermanent(connection, playerId, balances.get(playerId));
    }

    public AccountSnapshot loadAccountSnapshot(Connection connection, UUID playerId, long now) throws SQLException {
        String query = "SELECT t.points, tp.grant_id, tp.points, tp.expires_at FROM "
                + this.pointsTable + " t LEFT JOIN " + this.temporaryStorage.getTableName()
                + " tp ON t." + this.accountColumn + " = tp.uuid AND tp.expires_at > ?"
                + " WHERE t." + this.accountColumn + " = ? ORDER BY tp.expires_at ASC, tp.grant_id ASC";
        Integer permanent = null;
        List<TemporaryPointGrant> grants = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, now);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (permanent == null)
                        permanent = result.getInt(1);
                    String grantId = result.getString(2);
                    if (grantId != null) {
                        grants.add(new TemporaryPointGrant(UUID.fromString(grantId), playerId,
                                result.getInt(3), result.getLong(4)));
                    }
                }
            }
        }
        return new AccountSnapshot(permanent, grants);
    }

    public AccountSnapshot loadOrCreateAccountSnapshot(Connection connection, UUID playerId,
                                                       int startingBalance, long now) throws SQLException {
        AccountSnapshot snapshot = this.loadAccountSnapshot(connection, playerId, now);
        if (snapshot.getPermanent() != null)
            return snapshot;

        int permanent = this.lockAccount(connection, playerId, startingBalance);
        return new AccountSnapshot(permanent,
                this.temporaryStorage.loadActive(connection, playerId, now));
    }

    public PointsBackup loadBackupSnapshot(Connection connection, long now) throws SQLException {
        String query = "SELECT t." + this.accountColumn + ", t.points, tp.grant_id, tp.points, tp.expires_at FROM "
                + this.pointsTable + " t LEFT JOIN " + this.temporaryStorage.getTableName()
                + " tp ON t." + this.accountColumn + " = tp.uuid AND tp.expires_at > ?"
                + " ORDER BY t." + this.accountColumn + ", tp.expires_at ASC, tp.grant_id ASC";
        Map<UUID, Integer> permanent = new LinkedHashMap<>();
        Map<UUID, String> usernames = new LinkedHashMap<>();
        List<TemporaryPointGrant> grants = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID playerId = UUID.fromString(result.getString(1));
                    permanent.put(playerId, result.getInt(2));
                    String grantId = result.getString(3);
                    if (grantId != null) {
                        grants.add(new TemporaryPointGrant(UUID.fromString(grantId), playerId,
                                result.getInt(4), result.getLong(5)));
                    }
                }
            }
        }

        String usernamesQuery = "SELECT uuid, username FROM " + this.usernameTable + " ORDER BY uuid";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(usernamesQuery)) {
            while (result.next())
                usernames.put(UUID.fromString(result.getString(1)), result.getString(2));
        }
        return new PointsBackup(permanent, usernames, grants);
    }

    public boolean replaceAll(Connection connection, Map<UUID, Integer> permanent,
                              Map<UUID, String> usernames,
                              Collection<TemporaryPointGrant> grants, long now) throws SQLException {
        return this.replaceAllAndGetAffectedAccountIds(
                connection, permanent, usernames, grants, now).isPresent();
    }

    public Optional<Set<UUID>> replaceAllAndGetAffectedAccountIds(
            Connection connection, Map<UUID, Integer> permanent,
            Map<UUID, String> usernames, Collection<TemporaryPointGrant> grants,
            long now) throws SQLException {
        List<TemporaryPointGrant> activeGrants = validateImport(permanent, usernames, grants, now);
        if (activeGrants == null)
            return Optional.empty();

        Set<UUID> affectedAccountIds = new HashSet<>(this.lockAllAccounts(connection).keySet());
        affectedAccountIds.addAll(permanent.keySet());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + this.temporaryStorage.getTableName());
            statement.executeUpdate("DELETE FROM " + this.pointsTable);
            statement.executeUpdate("DELETE FROM " + this.usernameTable);
        }

        String pointsQuery = "INSERT INTO " + this.pointsTable + " (" + this.accountColumn + ", points) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(pointsQuery)) {
            for (Map.Entry<UUID, Integer> entry : permanent.entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setInt(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }

        for (TemporaryPointGrant grant : activeGrants)
            this.temporaryStorage.insert(connection, grant);
        this.upsertUsernames(connection, usernames);
        return Optional.of(Collections.unmodifiableSet(affectedAccountIds));
    }

    @SuppressWarnings("UnusedReturnValue")
    public Set<UUID> importLegacyBalances(Connection connection, String tableName)
            throws SQLException {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+"))
            throw new SQLException("Legacy table name contains unsupported characters");

        Map<UUID, Integer> balances = new LinkedHashMap<>();
        String query = "SELECT playername, points FROM " + tableName;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                String rawAccountId = result.getString(1);
                if (rawAccountId == null)
                    throw new SQLException("Legacy table contains a null UUID");

                UUID accountId;
                try {
                    accountId = UUID.fromString(rawAccountId);
                } catch (IllegalArgumentException e) {
                    throw new SQLException("Legacy table contains an invalid UUID", e);
                }
                if (!accountId.toString().equalsIgnoreCase(rawAccountId))
                    throw new SQLException("Legacy table contains an invalid UUID: " + rawAccountId);

                int balance = result.getInt(2);
                if (result.wasNull())
                    throw new SQLException("Legacy table contains a null balance");
                if (balance < 0)
                    throw new SQLException("Legacy table contains a negative balance");
                if (balances.putIfAbsent(accountId, balance) != null)
                    throw new SQLException("Legacy table contains a duplicate UUID: " + accountId);
            }
        }

        this.upsertPermanentBalances(connection, balances);
        return new HashSet<>(balances.keySet());
    }

    public void deleteAccount(Connection connection, UUID playerId) throws SQLException {
        this.lockAccount(connection, playerId, 0);
        this.temporaryStorage.clear(connection, playerId);
        this.deleteByUuid(connection, this.pointsTable, this.accountColumn, playerId);
        this.deleteByUuid(connection, this.usernameTable, "uuid", playerId);
    }

    public void upsertUsernames(Connection connection, Map<UUID, String> usernames) throws SQLException {
        if (usernames.isEmpty())
            return;
        if (this.dialect == Dialect.SQLITE_LEGACY) {
            this.upsertLegacySqliteUsernames(connection, usernames);
            return;
        }

        String query;
        if (this.dialect == Dialect.MYSQL) {
            query = "INSERT INTO " + this.usernameTable
                    + " (uuid, username) VALUES (?, ?) ON DUPLICATE KEY UPDATE username = ?";
        } else {
            query = "INSERT INTO " + this.usernameTable + " (uuid, username) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username";
        }
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (Map.Entry<UUID, String> entry : usernames.entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setString(2, entry.getValue());
                if (this.dialect == Dialect.MYSQL)
                    statement.setString(3, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void upsertUsernamesWithAccountLocks(Connection connection,
                                                Map<UUID, String> usernames,
                                                int startingBalance) throws SQLException {
        List<UUID> accountIds = new ArrayList<>(usernames.keySet());
        accountIds.sort(Comparator.comparing(UUID::toString));
        for (UUID accountId : accountIds)
            this.lockAccount(connection, accountId, startingBalance);
        this.upsertUsernames(connection, usernames);
    }

    private void upsertLegacySqliteUsernames(Connection connection,
                                             Map<UUID, String> usernames) throws SQLException {
        String insertQuery = "INSERT OR IGNORE INTO " + this.usernameTable
                + " (uuid, username) VALUES (?, ?)";
        String updateQuery = "UPDATE " + this.usernameTable + " SET username = ? WHERE uuid = ?";
        try (PreparedStatement insert = connection.prepareStatement(insertQuery);
             PreparedStatement update = connection.prepareStatement(updateQuery)) {
            for (Map.Entry<UUID, String> entry : usernames.entrySet()) {
                insert.setString(1, entry.getKey().toString());
                insert.setString(2, entry.getValue());
                if (insert.executeUpdate() != 0)
                    continue;

                update.setString(1, entry.getValue());
                update.setString(2, entry.getKey().toString());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Unable to update username for " + entry.getKey()
                            + " because another database constraint rejected it");
                }
            }
        }
    }

    private int lockAccount(Connection connection, UUID playerId, int startingBalance) throws SQLException {
        if (this.dialect == Dialect.MYSQL) {
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE
                    && this.selectPermanent(connection, playerId, false) != null) {
                Integer existing = this.selectPermanentForUpdate(connection, playerId);
                if (existing != null)
                    return existing;
            }

            String insert = "INSERT INTO " + this.pointsTable + " (" + this.accountColumn + ", points) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE points = points";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, startingBalance);
                statement.executeUpdate();
            }
            Integer created = this.selectPermanentForUpdate(connection, playerId);
            if (created == null)
                throw new SQLException("Failed to create points account " + playerId);
            return created;
        }

        String insert = "INSERT OR IGNORE INTO " + this.pointsTable + " ("
                + this.accountColumn + ", points) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, startingBalance);
            statement.executeUpdate();
        }

        // SQLite has no FOR UPDATE, but UPDATE can get the lock
        String lock = "UPDATE " + this.pointsTable + " SET points = points WHERE "
                + this.accountColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(lock)) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }

        Integer permanent = this.selectPermanentForUpdate(connection, playerId);
        if (permanent == null)
            throw new SQLException("Failed to create points account " + playerId);
        return permanent;
    }

    private Integer selectPermanentForUpdate(Connection connection, UUID playerId)
            throws SQLException {
        return this.selectPermanent(connection, playerId, true);
    }

    private Integer selectPermanent(Connection connection, UUID playerId,
                                    boolean lock) throws SQLException {
        String select = "SELECT points FROM " + this.pointsTable + " WHERE " + this.accountColumn + " = ?"
                + (lock && this.dialect == Dialect.MYSQL ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : null;
            }
        }
    }

    private Map<UUID, Integer> lockAllAccounts(Connection connection) throws SQLException {
        Map<UUID, Integer> balances = new LinkedHashMap<>();
        if (this.dialect == Dialect.MYSQL) {
            String query = "SELECT " + this.accountColumn + ", points FROM " + this.pointsTable
                    + " ORDER BY " + this.accountColumn + " FOR UPDATE";
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(query)) {
                while (result.next())
                    balances.put(UUID.fromString(result.getString(1)), result.getInt(2));
            }
        } else {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + this.pointsTable + " SET points = points");
            }
            String query = "SELECT " + this.accountColumn + ", points FROM " + this.pointsTable
                    + " ORDER BY " + this.accountColumn;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(query)) {
                while (result.next())
                    balances.put(UUID.fromString(result.getString(1)), result.getInt(2));
            }
        }
        return balances;
    }

    public List<UUID> loadPlayerAccountIds(Connection connection) throws SQLException {
        List<UUID> playerIds = new ArrayList<>();
        String query = "SELECT t." + this.accountColumn + " FROM " + this.pointsTable
                + " t LEFT JOIN " + this.usernameTable + " u ON t." + this.accountColumn
                + " = u.uuid WHERE u.username IS NULL OR u.username NOT LIKE '*%'"
                + " ORDER BY t." + this.accountColumn;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next())
                playerIds.add(UUID.fromString(result.getString(1)));
        }
        return playerIds;
    }

    public List<RankedBalance> loadTopBalances(Connection connection,
                                               boolean includeNonPlayerAccounts,
                                               long now, Integer limit, Integer offset)
            throws SQLException {
        String account = "t." + this.accountColumn;
        StringBuilder query = new StringBuilder("SELECT ")
                .append(account)
                .append(", u.username, t.points + COALESCE(SUM(tp.points), 0) AS total_points FROM ")
                .append(this.pointsTable).append(" t LEFT JOIN ")
                .append(this.usernameTable).append(" u ON ")
                .append(account).append(" = u.uuid LEFT JOIN ")
                .append(this.temporaryStorage.getTableName()).append(" tp ON ")
                .append(account).append(" = tp.uuid AND tp.expires_at > ?");
        if (!includeNonPlayerAccounts)
            query.append(" WHERE u.username IS NULL OR u.username NOT LIKE '*%'");

        query.append(" GROUP BY ").append(account).append(", u.username, t.points")
                .append(" ORDER BY total_points DESC, ")
                .append(uuidSignOrder(account, 1)).append(", LOWER(SUBSTR(")
                .append(account).append(", 1, 18)), ")
                .append(uuidSignOrder(account, 20)).append(", ")
                .append("LOWER(SUBSTR(").append(account).append(", 20))");

        boolean paginate = limit != null && limit >= 0;
        if (paginate)
            query.append(" LIMIT ? OFFSET ?");

        List<RankedBalance> balances = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query.toString())) {
            statement.setLong(1, now);
            if (paginate) {
                statement.setInt(2, limit);
                statement.setInt(3, offset == null ? 0 : Math.max(0, offset));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long points = result.getLong(3);
                    if (points < 0 || points > Integer.MAX_VALUE) {
                        throw new SQLException("Ranked balance is outside the integer range: "
                                + points);
                    }
                    balances.add(new RankedBalance(UUID.fromString(result.getString(1)),
                            result.getString(2), (int) points));
                }
            }
        }
        return balances;
    }

    private void updatePermanent(Connection connection, UUID playerId, int amount) throws SQLException {
        if (amount < 0)
            throw new IllegalArgumentException("permanent balance must not be negative");
        String query = "UPDATE " + this.pointsTable + " SET points = ? WHERE " + this.accountColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, amount);
            statement.setString(2, playerId.toString());
            if (statement.executeUpdate() != 1)
                throw new SQLException("Points account disappeared during transaction: " + playerId);
        }
    }

    private void updatePermanentBatch(Connection connection, Map<UUID, Integer> balances)
            throws SQLException {
        if (balances.isEmpty())
            return;
        String query = "UPDATE " + this.pointsTable + " SET points = ? WHERE "
                + this.accountColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (Map.Entry<UUID, Integer> entry : balances.entrySet()) {
                statement.setInt(1, entry.getValue());
                statement.setString(2, entry.getKey().toString());
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            if (results.length != balances.size())
                throw new SQLException("Database returned an incomplete points batch result");
            for (int result : results) {
                if (result != 1 && result != Statement.SUCCESS_NO_INFO)
                    throw new SQLException("A points account changed during a bulk update");
            }
        }
    }

    private void deleteByUuid(Connection connection, String table, String column, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    public enum Dialect {
        SQLITE_MODERN,
        SQLITE_LEGACY,
        MYSQL
    }

    public static final class RankedBalance {

        private final UUID playerId;
        private final String username;
        private final int points;

        private RankedBalance(UUID playerId, String username, int points) {
            this.playerId = playerId;
            this.username = username;
            this.points = points;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public String getUsername() {
            return this.username;
        }

        public int getPoints() {
            return this.points;
        }

    }

    public static final class AccountSnapshot {

        private final Integer permanent;
        private final List<TemporaryPointGrant> temporaryGrants;

        private AccountSnapshot(Integer permanent, List<TemporaryPointGrant> temporaryGrants) {
            this.permanent = permanent;
            this.temporaryGrants = Collections.unmodifiableList(new ArrayList<>(temporaryGrants));
        }

        public Integer getPermanent() {
            return this.permanent;
        }

        public int getPermanentOrDefault(int defaultValue) {
            return this.permanent == null ? defaultValue : this.permanent;
        }

        public List<TemporaryPointGrant> getTemporaryGrants() {
            return this.temporaryGrants;
        }

    }

}
