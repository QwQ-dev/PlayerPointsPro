package org.black_ixx.playerpoints.manager;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.database.DataMigration;
import dev.rosewood.rosegarden.database.DatabaseConnector;
import dev.rosewood.rosegarden.database.MySQLConnector;
import dev.rosewood.rosegarden.database.SQLiteConnector;
import dev.rosewood.rosegarden.manager.AbstractDataManager;
import dev.rosewood.rosegarden.scheduler.task.ScheduledTask;
import org.black_ixx.playerpoints.config.SettingKey;
import org.black_ixx.playerpoints.database.DatabaseSchemaVerifier;
import org.black_ixx.playerpoints.database.JdbcTransactionExecutor;
import org.black_ixx.playerpoints.database.MigrationLockingDatabaseConnector;
import org.black_ixx.playerpoints.database.MySqlNamedLock;
import org.black_ixx.playerpoints.database.PointsBackup;
import org.black_ixx.playerpoints.database.PointsDatabaseStorage;
import org.black_ixx.playerpoints.database.TemporaryPointsStorage;
import org.black_ixx.playerpoints.database.migrations.LegacyDataImportState;
import org.black_ixx.playerpoints.database.migrations._1_Create_Tables;
import org.black_ixx.playerpoints.database.migrations._2_Add_Table_Username_Cache;
import org.black_ixx.playerpoints.database.migrations._3_Add_Table_Transaction_Log;
import org.black_ixx.playerpoints.database.migrations._4_Add_Table_Temporary_Points;
import org.black_ixx.playerpoints.listeners.PointsMessageListener;
import org.black_ixx.playerpoints.models.DetailedPointsBalance;
import org.black_ixx.playerpoints.models.PendingTransaction;
import org.black_ixx.playerpoints.models.PointsBalance;
import org.black_ixx.playerpoints.models.SortedPlayer;
import org.black_ixx.playerpoints.models.TemporaryPointGrant;
import org.black_ixx.playerpoints.models.TransactionType;
import org.black_ixx.playerpoints.models.UpdateType;
import org.black_ixx.playerpoints.util.PointsBalanceCalculator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class DataManager extends AbstractDataManager implements Listener {

    private static final int ACCOUNT_LOCK_STRIPES = 64;
    private static final int ACCOUNT_NAME_LOCK_TIMEOUT_SECONDS = 30;
    private static final int MIGRATION_LOCK_TIMEOUT_SECONDS = 60;

    private final PendingAccountRefreshes pendingAccountRefreshes;
    private final PendingRefresh pendingDatasetRefresh;
    private final AccountNameReservations accountNameReservations;
    private final Set<String> allAccountNames;
    private final StripedAccountLock accountLocks;
    private final DatasetLock datasetLock;
    private final AtomicLong lifecycleGeneration;
    private ScheduledTask accountUpdateTask;
    private ScheduledTask expirationCleanupTask;
    private LoadingCache<UUID, PointsDatabaseStorage.AccountSnapshot> balanceCache;
    private TemporaryPointsStorage temporaryPointsStorage;
    private PointsDatabaseStorage pointsDatabaseStorage;
    private DatabaseConnectorExecutor databaseExecutor;
    private boolean isModernSqlite;
    private boolean logTransactions;
    private LegacyDataImportState legacyDataImportState;
    private boolean active;

    public DataManager(RosePlugin rosePlugin) {
        super(rosePlugin);

        this.pendingAccountRefreshes = new PendingAccountRefreshes();
        this.pendingDatasetRefresh = new PendingRefresh();
        this.accountNameReservations = new AccountNameReservations();
        this.allAccountNames = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.accountLocks = new StripedAccountLock(ACCOUNT_LOCK_STRIPES);
        this.datasetLock = new DatasetLock();
        this.lifecycleGeneration = new AtomicLong();

        Bukkit.getPluginManager().registerEvents(this, rosePlugin);
    }

    @Override
    public void reload() {
        this.cancelTasks();
        this.datasetLock.runWrite(this::reloadLocked);
    }

    private void reloadLocked() {
        long generation = this.lifecycleGeneration.incrementAndGet();
        this.active = false;
        this.balanceCache = null;
        this.temporaryPointsStorage = null;
        this.pointsDatabaseStorage = null;
        this.databaseExecutor = null;
        this.legacyDataImportState = new LegacyDataImportState(
                new File(this.rosePlugin.getDataFolder(), "storage.yml"));
        try {
            super.reload();
        } finally {
            if (this.databaseConnector instanceof MigrationLockingDatabaseConnector) {
                this.databaseConnector = ((MigrationLockingDatabaseConnector) this.databaseConnector)
                        .getDelegate();
            }
        }

        this.verifyDatabaseSchema();
        if (this.legacyDataImportState.wasImported())
            this.rosePlugin.getLogger().warning("Imported legacy data from storage.yml");
        this.databaseExecutor = new DatabaseConnectorExecutor(
                this.databaseConnector, this.databaseConnector instanceof SQLiteConnector);
        this.temporaryPointsStorage = new TemporaryPointsStorage(
                super.getTablePrefix() + "temporary_points",
                !(this.databaseConnector instanceof SQLiteConnector));

        if (this.databaseConnector instanceof SQLiteConnector) {
            this.connect(connection -> {
                // Get SQLite version
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT sqlite_version()")) {
                    if (rs.next()) {
                        String version = rs.getString(1);
                        // Parse version to check if it's >= 3.24.0
                        String[] parts = version.split("\\.");
                        int major = Integer.parseInt(parts[0]);
                        int minor = Integer.parseInt(parts[1]);
                        this.isModernSqlite = (major > 3) || (major == 3 && minor >= 24);
                    }
                }
            });
        } else {
            this.isModernSqlite = false;
        }

        PointsDatabaseStorage.Dialect dialect;
        if (!(this.databaseConnector instanceof SQLiteConnector)) {
            dialect = PointsDatabaseStorage.Dialect.MYSQL;
        } else if (this.isModernSqlite) {
            dialect = PointsDatabaseStorage.Dialect.SQLITE_MODERN;
        } else {
            dialect = PointsDatabaseStorage.Dialect.SQLITE_LEGACY;
        }
        this.pointsDatabaseStorage = new PointsDatabaseStorage(this.getPointsTableName(),
                this.getUuidColumnName(), this.getTablePrefix() + "username_cache",
                this.temporaryPointsStorage, dialect);
        this.balanceCache = CacheBuilder.newBuilder()
                .concurrencyLevel(2)
                .expireAfterAccess(Duration.ofMillis(5))
                .refreshAfterWrite(Duration.ofSeconds(SettingKey.CACHE_DURATION.get()))
                .build(new CacheLoader<UUID, PointsDatabaseStorage.AccountSnapshot>() {
                    @Override
                    public PointsDatabaseStorage.@NonNull AccountSnapshot load(@NonNull UUID uuid) {
                        return DataManager.this.loadBalanceSnapshot(uuid);
                    }
                });

        this.active = true;
        this.expirationCleanupTask = this.rosePlugin.getScheduler().runTaskTimerAsync(
                () -> this.deleteExpiredTemporaryPoints(generation), 1_200L, 1_200L);
        if (SettingKey.TAB_COMPLETE_SHOW_ALL_PLAYERS.get()) {
            this.accountUpdateTask = this.rosePlugin.getScheduler().runTaskTimerAsync(
                    () -> this.updateAccountUUIDMaps(generation), 10L,
                    SettingKey.CACHED_ACCOUNT_LIST_REFRESH_INTERVAL.get() * 20L);
        }

        this.logTransactions = SettingKey.LOG_TRANSACTIONS.get();
    }

    @Override
    public void disable() {
        this.cancelTasks();
        this.datasetLock.runWrite(this::disableLocked);
    }

    private void cancelTasks() {
        if (this.accountUpdateTask != null) {
            this.accountUpdateTask.cancel();
            this.accountUpdateTask = null;
        }

        if (this.expirationCleanupTask != null) {
            this.expirationCleanupTask.cancel();
            this.expirationCleanupTask = null;
        }
    }

    private void disableLocked() {
        this.lifecycleGeneration.incrementAndGet();
        this.active = false;
        this.accountLocks.runWithAllLocks(() -> {
            this.accountNameReservations.clear();
            if (this.balanceCache != null)
                this.balanceCache.invalidateAll();
        });
        this.allAccountNames.clear();
        super.disable();
    }

    private boolean isCurrentGeneration(long generation) {
        return this.active && this.lifecycleGeneration.get() == generation;
    }

    private void verifyDatabaseSchema() {
        try (Connection connection = this.databaseConnector.connect()) {
            if (connection == null)
                throw new SQLException("Database connector returned no connection");
            if (this.databaseConnector instanceof SQLiteConnector && !connection.getAutoCommit())
                connection.rollback();

            new DatabaseSchemaVerifier(super.getTablePrefix(), this.getPointsTableName(),
                    this.getUuidColumnName(), !(this.databaseConnector instanceof SQLiteConnector))
                    .requireReady(connection, 4);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Database migrations did not produce a usable PlayerPoints schema", e);
        }
    }

    private void updateAccountUUIDMaps(long generation) {
        this.datasetLock.runWrite(() -> {
            if (!this.isCurrentGeneration(generation))
                return;

            DatabaseLoadResult<Set<String>> stored = this.loadFromDatabase(connection -> {
                Set<String> accountNames = new HashSet<>();
                String accountUUIDMapQuery = "SELECT username FROM "
                        + this.getTablePrefix() + "username_cache";
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery(accountUUIDMapQuery)) {
                    while (result.next())
                        accountNames.add(result.getString(1));
                }
                return accountNames;
            });
            if (!stored.isSuccessful())
                return;

            Set<String> allAccountNames = stored.getValue();
            this.allAccountNames.clear();
            this.allAccountNames.addAll(allAccountNames);
        });
    }

    /**
     * @return a set of all account names registered by PlayerPoints, will be empty if tab-complete-show-all-players is false
     */
    public Set<String> getAllAccountNames() {
        return this.datasetLock.withRead(() -> new HashSet<>(this.allAccountNames));
    }

    public List<UUID> getAllPlayerAccountIds() {
        return this.datasetLock.withRead(() -> {
            if (!this.active)
                throw new IllegalStateException("Data manager is not active");
            DatabaseLoadResult<List<UUID>> stored = this.loadFromDatabase(
                    this.pointsDatabaseStorage::loadPlayerAccountIds);
            if (!stored.isSuccessful())
                throw new IllegalStateException("Failed to load PlayerPoints player account IDs");
            return stored.getValue();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED)
            this.refreshPointsNow(event.getUniqueId(), this.lifecycleGeneration.get(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        this.rosePlugin.getScheduler().runTaskAsync(() ->
                this.updateCachedUsernames(
                        Collections.singletonMap(playerId, playerName)));
        this.sendPendingAccountRefreshes(player);
        this.sendPendingDatasetRefresh(player);
    }

    /**
     * Gets the effective amount of points that a player has (includes pending transactions)
     *
     * @param playerId The player ID to use to get the points
     * @return the effective points value
     */
    public int getEffectivePoints(UUID playerId) {
        return this.datasetLock.withRead(() ->
                this.accountLocks.withLock(playerId, () ->
                        this.getEffectiveBalance(
                                playerId, null, System.currentTimeMillis()).getTotal()));
    }

    public DetailedPointsBalance getDetailedBalance(UUID playerId) {
        return this.datasetLock.withRead(() ->
                this.accountLocks.withLock(playerId, () ->
                        this.getEffectiveBalance(
                                playerId, null, System.currentTimeMillis())));
    }

    public int getEffectivePoints(UUID playerId, int points) {
        return this.datasetLock.withRead(() ->
                this.accountLocks.withLock(playerId, () ->
                        this.getEffectiveBalance(
                                playerId, points, System.currentTimeMillis()).getTotal()));
    }

    public int getEffectiveTemporaryPoints(UUID playerId) {
        return this.datasetLock.withRead(() ->
                this.accountLocks.withLock(playerId, () ->
                        this.getEffectiveBalance(
                                playerId, null, System.currentTimeMillis()).getTemporary()));
    }

    public int getEffectivePermanentPoints(UUID playerId) {
        return this.datasetLock.withRead(() ->
                this.accountLocks.withLock(playerId, () ->
                        this.getEffectiveBalance(
                                playerId, null, System.currentTimeMillis()).getPermanent()));
    }

    private DetailedPointsBalance getEffectiveBalance(UUID playerId, Integer permanent, long now) {
        PointsDatabaseStorage.AccountSnapshot snapshot = this.getCachedBalance(playerId);
        if (permanent == null)
            permanent = snapshot.getPermanentOrDefault(SettingKey.STARTING_BALANCE.get());

        return PointsBalanceCalculator.calculateDetailed(
                playerId, permanent, snapshot.getTemporaryGrants(),
                Collections.emptyList(), now);
    }

    private PointsBalance getEffectiveBalance(UUID playerId, int permanent,
                                              Collection<TemporaryPointGrant> grants, long now) {
        return this.accountLocks.withLock(playerId, () ->
                PointsBalanceCalculator.calculate(permanent, grants,
                        Collections.emptyList(), now));
    }

    private PointsDatabaseStorage.AccountSnapshot getCachedBalance(UUID playerId) {
        try {
            return this.balanceCache.get(playerId);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to load balance for " + playerId, e.getCause());
        }
    }

    /**
     * Refreshes a player's cached balance asynchronously.
     *
     * @param uuid The player's UUID
     */
    public void refreshPoints(UUID uuid) {
        long generation = this.lifecycleGeneration.get();
        this.rosePlugin.getScheduler().runTaskAsync(() ->
                this.refreshPointsNow(uuid, generation, false));
    }

    public void refreshAllPoints() {
        this.rosePlugin.getScheduler().runTaskAsync(this::invalidateAllPoints);
    }

    public void invalidateAllPoints() {
        this.datasetLock.runRead(() -> {
            if (this.active && this.balanceCache != null)
                this.balanceCache.invalidateAll();
        });
    }

    private boolean refreshPointsNow(UUID uuid, long generation, boolean createIfMissing) {
        return this.datasetLock.withRead(() -> {
            if (!this.isCurrentGeneration(generation))
                return false;

            return this.accountLocks.withLock(uuid, () -> {
                DatabaseLoadResult<PointsDatabaseStorage.AccountSnapshot> snapshot =
                        createIfMissing
                                ? this.loadStoredBalanceSnapshot(uuid)
                                : this.loadExistingBalanceSnapshot(uuid);
                if (!snapshot.isSuccessful()) {
                    this.balanceCache.invalidate(uuid);
                    return false;
                }
                if (snapshot.getValue().getPermanent() == null) {
                    this.balanceCache.invalidate(uuid);
                    return true;
                }

                this.balanceCache.put(uuid, snapshot.getValue());
                return true;
            });
        });
    }

    private PointsDatabaseStorage.AccountSnapshot loadBalanceSnapshot(UUID playerId) {
        DatabaseLoadResult<PointsDatabaseStorage.AccountSnapshot> result =
                this.loadStoredBalanceSnapshot(playerId);
        if (!result.isSuccessful())
            throw new IllegalStateException("Failed to query balance for " + playerId);
        return result.getValue();
    }

    private DatabaseLoadResult<PointsDatabaseStorage.AccountSnapshot> loadStoredBalanceSnapshot(UUID playerId) {
        return this.loadFromDatabaseTransaction(connection ->
                this.pointsDatabaseStorage.loadOrCreateAccountSnapshot(connection, playerId,
                        SettingKey.STARTING_BALANCE.get(), Long.MIN_VALUE));
    }

    private DatabaseLoadResult<PointsDatabaseStorage.AccountSnapshot> loadExistingBalanceSnapshot(UUID playerId) {
        return this.loadFromDatabase(connection ->
                this.pointsDatabaseStorage.loadAccountSnapshot(
                        connection, playerId, Long.MIN_VALUE));
    }

    private void deleteExpiredTemporaryPoints(long generation) {
        this.datasetLock.runRead(() -> {
            if (this.isCurrentGeneration(generation)) {
                this.connect(connection -> this.temporaryPointsStorage.deleteExpired(
                        connection, System.currentTimeMillis()));
            }
        });
    }

    /**
     * Sets a player's points to a specified amount
     *
     * @param transactionType   The type of transaction
     * @param playerId          The Player to set the points of
     * @param sourceDescription The description of how the points were given
     * @param source            The UUID source of the points, nullable
     * @param amount            The amount to set to
     * @return true if the transaction was successful, false otherwise
     */
    public boolean setPoints(TransactionType transactionType, UUID playerId, String sourceDescription, UUID source, int amount) {
        if (amount < 0)
            return false;

        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(playerId, () -> {
                PendingTransaction transaction = new PendingTransaction(
                        UpdateType.SET, transactionType, sourceDescription, source, amount);
                boolean committed = this.executeAtomic(connection -> {
                    this.pointsDatabaseStorage.set(connection, playerId, amount,
                            SettingKey.STARTING_BALANCE.get());
                    this.logTransaction(connection, playerId, transaction);
                    return true;
                });
                if (committed)
                    this.publishCommittedUpdates(Collections.singleton(playerId));
                return committed;
            });
        });
    }

    public boolean setPermanentPoints(TransactionType transactionType, UUID playerId,
                                      String sourceDescription, UUID source, int amount) {
        if (amount < 0)
            return false;

        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(playerId, () -> {
                PendingTransaction transaction = new PendingTransaction(
                        UpdateType.SET_PERMANENT, transactionType,
                        sourceDescription, source, amount);
                boolean committed = this.executeAtomic(connection -> {
                    boolean accepted = this.pointsDatabaseStorage.setPermanent(
                            connection, playerId, amount,
                            SettingKey.STARTING_BALANCE.get());
                    if (accepted)
                        this.logTransaction(connection, playerId, transaction);
                    return accepted;
                });
                if (committed)
                    this.publishCommittedUpdates(Collections.singleton(playerId));
                return committed;
            });
        });
    }

    public boolean replaceTemporaryPoints(UUID playerId, String sourceDescription,
                                          UUID source, int amount, long expiresAt) {
        if (amount <= 0)
            return false;

        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(playerId, () -> {
                if (expiresAt <= System.currentTimeMillis()) {
                    return false;
                }

                PendingTransaction transaction = PendingTransaction.setTemporary(
                        sourceDescription, source, amount, expiresAt);
                boolean committed = this.executeAtomic(connection -> {
                    boolean accepted = this.pointsDatabaseStorage.replaceTemporary(
                            connection, new TemporaryPointGrant(
                                    transaction.getTemporaryGrantId(), playerId,
                                    amount, expiresAt),
                            SettingKey.STARTING_BALANCE.get());
                    if (accepted)
                        this.logTransaction(connection, playerId, transaction);
                    return accepted;
                });
                if (committed)
                    this.publishCommittedUpdates(Collections.singleton(playerId));
                return committed;
            });
        });
    }

    public boolean clearTemporaryPoints(UUID playerId, String sourceDescription,
                                        UUID source) {
        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(playerId, () -> {
                PendingTransaction transaction = new PendingTransaction(
                        UpdateType.CLEAR_TEMPORARY, TransactionType.RESET_TEMPORARY,
                        sourceDescription, source, 0);
                boolean committed = this.executeAtomic(connection -> {
                    this.pointsDatabaseStorage.clearTemporary(
                            connection, playerId, SettingKey.STARTING_BALANCE.get());
                    this.logTransaction(connection, playerId, transaction);
                    return true;
                });
                if (committed)
                    this.publishCommittedUpdates(Collections.singleton(playerId));
                return committed;
            });
        });
    }

    public boolean addTemporaryPoints(UUID playerId, String sourceDescription, UUID source, int amount, long expiresAt) {
        if (amount <= 0)
            return false;

        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(playerId, () -> {
                if (expiresAt <= System.currentTimeMillis())
                    return false;
                PendingTransaction transaction = PendingTransaction.temporary(
                        sourceDescription, source, amount, expiresAt);
                if (expiresAt <= System.currentTimeMillis())
                    return false;

                boolean committed = this.executeAtomic(connection -> {
                    boolean accepted = this.pointsDatabaseStorage.grantTemporary(connection,
                            new TemporaryPointGrant(transaction.getTemporaryGrantId(), playerId,
                                    transaction.getAmount(), transaction.getExpiresAt()),
                            SettingKey.STARTING_BALANCE.get());
                    if (accepted)
                        this.logTransaction(connection, playerId, transaction);
                    return accepted;
                });
                if (committed)
                    this.publishCommittedUpdates(Collections.singleton(playerId));
                return committed;
            });
        });
    }

    public boolean takeTemporaryPoints(UUID playerId, String sourceDescription,
                                       UUID source, int amount) {
        return this.takePointsComponent(
                playerId, sourceDescription, source, amount, true);
    }

    public boolean takePermanentPoints(UUID playerId, String sourceDescription,
                                       UUID source, int amount) {
        return this.takePointsComponent(
                playerId, sourceDescription, source, amount, false);
    }

    private boolean takePointsComponent(UUID playerId, String sourceDescription,
                                        UUID source, int amount, boolean temporary) {
        if (amount <= 0)
            return false;

        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(playerId, () -> {
                PendingTransaction transaction = new PendingTransaction(
                        UpdateType.OFFSET, temporary
                        ? TransactionType.TEMPORARY_OFFSET
                        : TransactionType.PERMANENT_OFFSET,
                        sourceDescription, source, -amount);
                boolean committed = this.executeAtomic(connection -> {
                    boolean accepted = temporary
                            ? this.pointsDatabaseStorage.withdrawTemporary(
                            connection, playerId, amount,
                            SettingKey.STARTING_BALANCE.get())
                            : this.pointsDatabaseStorage.withdrawPermanent(
                            connection, playerId, amount,
                            SettingKey.STARTING_BALANCE.get());
                    if (accepted)
                        this.logTransaction(connection, playerId, transaction);
                    return accepted;
                });
                if (committed)
                    this.publishCommittedUpdates(Collections.singleton(playerId));
                return committed;
            });
        });
    }

    /**
     * Adds a pending transaction to offset the player's points by a specified amount
     *
     * @param transactionType The type of transaction
     * @param playerId        The Player to offset the points of
     * @param amount          The amount to offset by
     * @return true if the transaction was successful, false otherwise
     */
    public boolean offsetPoints(TransactionType transactionType, UUID playerId, String sourceDescription, UUID source, int amount) {
        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(playerId, () -> {
                if (amount < 0) {
                    if (amount == Integer.MIN_VALUE)
                        return false;
                    PendingTransaction transaction = new PendingTransaction(
                            UpdateType.OFFSET, transactionType, sourceDescription, source, amount);
                    boolean committed = this.executeAtomic(connection -> {
                        boolean accepted = this.pointsDatabaseStorage.withdraw(connection, playerId,
                                -amount, transactionType == TransactionType.PAY_SENDER,
                                SettingKey.STARTING_BALANCE.get());
                        if (accepted)
                            this.logTransaction(connection, playerId, transaction);
                        return accepted;
                    });
                    if (committed)
                        this.publishCommittedUpdates(Collections.singleton(playerId));
                    return committed;
                }

                if (amount == 0)
                    return true;
                PendingTransaction transaction = new PendingTransaction(
                        UpdateType.OFFSET, transactionType, sourceDescription, source, amount);
                boolean committed = this.executeAtomic(connection -> {
                    boolean accepted = this.pointsDatabaseStorage.deposit(
                            connection, playerId, amount, SettingKey.STARTING_BALANCE.get());
                    if (accepted)
                        this.logTransaction(connection, playerId, transaction);
                    return accepted;
                });
                if (committed)
                    this.publishCommittedUpdates(Collections.singleton(playerId));
                return committed;
            });
        });
    }

    public boolean transferPoints(UUID sourceId, UUID targetId, int sourceAmount, int targetAmount) {
        Collection<UUID> playerIds = Arrays.asList(sourceId, targetId);
        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLocks(playerIds, () -> {
                PendingTransaction sourceTransaction = new PendingTransaction(UpdateType.OFFSET,
                        TransactionType.PAY_SENDER, "Pay", targetId, -sourceAmount);
                PendingTransaction targetTransaction = new PendingTransaction(UpdateType.OFFSET,
                        TransactionType.PAY_RECEIVER, "Pay", sourceId, targetAmount);
                boolean committed = this.executeAtomic(connection -> {
                    boolean accepted = this.pointsDatabaseStorage.transferPermanent(connection,
                            sourceId, targetId, sourceAmount, targetAmount,
                            SettingKey.STARTING_BALANCE.get());
                    if (accepted) {
                        this.logTransaction(connection, sourceId, sourceTransaction);
                        this.logTransaction(connection, targetId, targetTransaction);
                    }
                    return accepted;
                });
                if (committed) {
                    this.publishCommittedUpdates(
                            new HashSet<>(Arrays.asList(sourceId, targetId)));
                }
                return committed;
            });
        });
    }

    private void publishCommittedUpdates(Set<UUID> playerIds) {
        for (UUID uuid : playerIds)
            this.balanceCache.invalidate(uuid);

        try {
            this.sendProxyRefreshes(playerIds);
        } catch (RuntimeException e) {
            this.rosePlugin.getLogger().log(Level.SEVERE,
                    "Failed to schedule committed points refreshes", e);
        }
    }

    private void sendProxyRefreshes(Collection<UUID> playerIds) {
        if (playerIds.isEmpty() || !SettingKey.BUNGEECORD_SEND_UPDATES.get()
                || !this.rosePlugin.isEnabled()) {
            return;
        }

        this.pendingAccountRefreshes.markPending(playerIds);
        this.rosePlugin.getScheduler().runTask(() -> {
            if (!this.rosePlugin.isEnabled())
                return;
            Player attachedPlayer = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
            if (attachedPlayer != null)
                this.sendPendingAccountRefreshes(attachedPlayer);
        });
    }

    private void sendPendingAccountRefreshes(Player attachedPlayer) {
        if (!SettingKey.BUNGEECORD_SEND_UPDATES.get() || !this.rosePlugin.isEnabled())
            return;

        try {
            this.pendingAccountRefreshes.sendPending(uuid -> attachedPlayer.sendPluginMessage(
                    this.rosePlugin, PointsMessageListener.CHANNEL,
                    this.createProxyRefreshMessage(uuid)));
        } catch (RuntimeException failure) {
            this.rosePlugin.getLogger().log(Level.SEVERE,
                    "Failed to send a committed points refresh", failure);
        }
    }

    private byte[] createProxyRefreshMessage(UUID uuid) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("Forward");
        output.writeUTF("ONLINE");
        output.writeUTF(PointsMessageListener.REFRESH_SUBCHANNEL);

        byte[] bytes = uuid.toString().getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
        return output.toByteArray();
    }

    private void publishCommittedDatasetUpdate() {
        this.balanceCache.invalidateAll();
        if (!SettingKey.BUNGEECORD_SEND_UPDATES.get() || !this.rosePlugin.isEnabled())
            return;

        this.pendingDatasetRefresh.markPending();
        try {
            this.rosePlugin.getScheduler().runTask(() -> {
                if (!this.rosePlugin.isEnabled())
                    return;
                Player attachedPlayer = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
                if (attachedPlayer != null)
                    this.sendPendingDatasetRefresh(attachedPlayer);
            });
        } catch (RuntimeException failure) {
            this.rosePlugin.getLogger().log(Level.SEVERE,
                    "Failed to schedule the committed dataset refresh", failure);
        }
    }

    private void sendPendingDatasetRefresh(Player attachedPlayer) {
        if (!SettingKey.BUNGEECORD_SEND_UPDATES.get() || !this.rosePlugin.isEnabled())
            return;

        try {
            this.pendingDatasetRefresh.sendIfPending(() -> attachedPlayer.sendPluginMessage(
                    this.rosePlugin, PointsMessageListener.CHANNEL,
                    this.createProxyDatasetRefreshMessage()));
        } catch (RuntimeException failure) {
            this.rosePlugin.getLogger().log(Level.SEVERE,
                    "Failed to send the committed dataset refresh", failure);
        }
    }

    private byte[] createProxyDatasetRefreshMessage() {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("Forward");
        output.writeUTF("ONLINE");
        output.writeUTF(PointsMessageListener.REFRESH_ALL_SUBCHANNEL);
        output.writeShort(0);
        return output.toByteArray();
    }

    public void offsetAllPoints(int amount) {
        this.offsetAllPointsWithResult(amount);
    }

    public boolean offsetAllPointsWithResult(int amount) {
        return this.datasetLock.withWrite(() -> this.accountLocks.withAllLocks(() -> {
            if (!this.active)
                return false;
            if (amount == 0)
                return true;

            boolean committed = this.executeDatasetReplacement(connection -> {
                Optional<Map<UUID, Integer>> applied = this.pointsDatabaseStorage.offsetAll(
                        connection, amount);
                if (!applied.isPresent())
                    return false;

                String description = amount > 0 ? "Give All" : "Take All";
                this.logBulkTransactions(connection, applied.get(), description);
                return true;
            });
            if (!committed)
                return false;

            this.publishCommittedDatasetUpdate();
            return true;
        }));
    }

    public boolean doesDataExist() {
        return this.datasetLock.withRead(() -> {
            AtomicInteger count = new AtomicInteger();
            this.connect(connection -> {
                try (Statement statement = connection.createStatement()) {
                    ResultSet result = statement.executeQuery(
                            "SELECT COUNT(*) FROM " + this.getPointsTableName());
                    result.next();
                    count.set(result.getInt(1));
                }
            });
            return count.get() > 0;
        });
    }

    public PointsBackup getBackupSnapshot() {
        return this.datasetLock.withWrite(() -> this.accountLocks.withAllLocks(() -> {
            DatabaseValueLoader<PointsBackup> loader = connection ->
                    this.pointsDatabaseStorage.loadBackupSnapshot(
                            connection, System.currentTimeMillis());
            DatabaseLoadResult<PointsBackup> result = this.databaseConnector instanceof SQLiteConnector
                    ? this.loadFromDatabaseTransaction(loader)
                    : this.loadFromDatabaseTransaction(
                    Connection.TRANSACTION_REPEATABLE_READ, loader);
            if (!result.isSuccessful())
                throw new IllegalStateException("Failed to load a consistent points backup snapshot");
            return result.getValue();
        }));
    }

    public List<SortedPlayer> getTopSortedPoints() {
        return this.getTopSortedPoints(null, null);
    }

    public List<SortedPlayer> getTopSortedPoints(Integer limit) {
        return this.getTopSortedPoints(limit, null);
    }

    public List<SortedPlayer> getTopSortedPoints(Integer limit, Integer offset) {
        return this.datasetLock.withRead(() -> this.getTopSortedPointsLocked(limit, offset));
    }

    private List<SortedPlayer> getTopSortedPointsLocked(Integer limit, Integer offset) {
        boolean includeNonPlayerAccounts =
                SettingKey.SHOW_NON_PLAYER_ACCOUNTS_ON_LEADERBOARDS.get();
        DatabaseLoadResult<List<PointsDatabaseStorage.RankedBalance>> stored =
                this.loadFromDatabase(connection -> this.pointsDatabaseStorage.loadTopBalances(
                        connection, includeNonPlayerAccounts, System.currentTimeMillis(),
                        limit, offset));
        if (!stored.isSuccessful())
            return Collections.emptyList();

        List<SortedPlayer> players = new ArrayList<>(stored.getValue().size());
        for (PointsDatabaseStorage.RankedBalance balance : stored.getValue()) {
            if (balance.getUsername() == null) {
                players.add(new SortedPlayer(balance.getPlayerId(), balance.getPoints()));
            } else {
                players.add(new SortedPlayer(balance.getPlayerId(), balance.getUsername(),
                        balance.getPoints()));
            }
        }
        return players;
    }

    public int getLeaderboardSize() {
        return this.datasetLock.withRead(() -> {
            AtomicInteger size = new AtomicInteger();
            this.connect(connection -> {
                String query = "SELECT COUNT(*) FROM " + this.getPointsTableName();
                try (Statement statement = connection.createStatement()) {
                    ResultSet result = statement.executeQuery(query);
                    if (result.next()) {
                        size.set(result.getInt(1));
                    }
                }
            });
            return size.get();
        });
    }

    public Map<UUID, Long> getOnlineTopSortedPointPositions() {
        return this.datasetLock.withRead(this::getOnlineTopSortedPointPositionsLocked);
    }

    private Map<UUID, Long> getOnlineTopSortedPointPositionsLocked() {
        Map<UUID, Long> players = new HashMap<>();
        if (Bukkit.getOnlinePlayers().isEmpty())
            return players;

        Set<UUID> onlinePlayerIds = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());
        List<SortedPlayer> rankings = new ArrayList<>();
        long now = System.currentTimeMillis();
        DatabaseLoadResult<List<RankingSnapshotBuilder.Account>> snapshot =
                this.loadFromDatabase(connection -> this.loadRankingSnapshot(connection, now));
        if (snapshot.isSuccessful()) {
            for (RankingSnapshotBuilder.Account account : snapshot.getValue()) {
                int points = this.getEffectiveBalance(account.getPlayerId(), account.getPermanentPoints(),
                        account.getTemporaryGrants(), now).getTotal();
                rankings.add(new SortedPlayer(account.getPlayerId(), null, points));
            }
        }

        Collections.sort(rankings);
        for (int index = 0; index < rankings.size(); ) {
            int end = index + 1;
            int points = rankings.get(index).getPoints();
            while (end < rankings.size() && rankings.get(end).getPoints() == points)
                end++;

            long position = end;
            for (int tiedIndex = index; tiedIndex < end; tiedIndex++) {
                UUID uuid = rankings.get(tiedIndex).getUniqueId();
                if (onlinePlayerIds.contains(uuid))
                    players.put(uuid, position);
            }
            index = end;
        }
        return players;
    }

    private List<RankingSnapshotBuilder.Account> loadRankingSnapshot(Connection connection, long now) throws SQLException {
        String accountColumn = this.getUuidColumnName();
        String query = "SELECT t." + accountColumn + ", c.username, t.points, " +
                "tp.grant_id, tp.points, tp.expires_at FROM " + this.getPointsTableName() + " t " +
                "LEFT JOIN " + this.getTablePrefix() + "username_cache c ON t." + accountColumn + " = c.uuid " +
                "LEFT JOIN " + this.getTemporaryPointsTableName() + " tp ON t." + accountColumn + " = tp.uuid " +
                "AND tp.expires_at > ? " +
                "ORDER BY t.points DESC, tp.expires_at ASC, tp.grant_id ASC";
        RankingSnapshotBuilder builder = new RankingSnapshotBuilder();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID playerId = UUID.fromString(result.getString(1));
                    TemporaryPointGrant grant = null;
                    String grantId = result.getString(4);
                    if (grantId != null) {
                        grant = new TemporaryPointGrant(UUID.fromString(grantId), playerId,
                                result.getInt(5), result.getLong(6));
                    }
                    builder.addRow(playerId, result.getString(2), result.getInt(3), grant);
                }
            }
        }
        return builder.build();
    }

    public UUID createNonPlayerAccount(String accountName) {
        UUID accountId = this.datasetLock.withRead(() -> {
            if (!this.active)
                throw new IllegalStateException("Data manager is not active");
            return this.accountNameReservations.getOrCreate(accountName, () -> {
                DatabaseLoadResult<NamedAccountCreation> stored =
                        this.createNamedAccountInDatabase(accountName);
                if (!stored.isSuccessful())
                    throw new IllegalStateException("Failed to create account " + accountName);

                NamedAccountCreation creation = stored.getValue();
                if (creation.wasCreated()) {
                    this.allAccountNames.add(accountName);
                    this.publishCommittedUpdates(
                            Collections.singleton(creation.getAccountId()));
                }
                return creation.getAccountId();
            });
        });
        this.accountNameReservations.remove(accountName, accountId);
        return accountId;
    }

    private DatabaseLoadResult<NamedAccountCreation> createNamedAccountInDatabase(
            String accountName) {
        AtomicReference<DatabaseLoadResult<NamedAccountCreation>> result =
                new AtomicReference<>(DatabaseLoadResult.failure());
        this.connect(connection -> {
            NamedAccountCreation creation;
            if (this.databaseConnector instanceof SQLiteConnector) {
                creation = this.createNamedAccount(connection, accountName, false);
            } else {
                creation = MySqlNamedLock.execute(connection,
                        this.getTablePrefix() + "username_cache",
                        AccountNameReservations.normalize(accountName),
                        ACCOUNT_NAME_LOCK_TIMEOUT_SECONDS,
                        lockedConnection -> this.createNamedAccount(
                                lockedConnection, accountName, true));
            }
            result.set(DatabaseLoadResult.success(creation));
        }, false);
        return result.get();
    }

    private NamedAccountCreation createNamedAccount(Connection connection, String accountName,
                                                    boolean mysql) throws SQLException {
        UUID existingAccountId = this.lookupCachedUUID(connection, accountName);
        if (existingAccountId != null)
            return new NamedAccountCreation(existingAccountId, false);

        UUID createdAccountId = UUID.randomUUID();
        AtomicReference<UUID> concurrentAccountId = new AtomicReference<>();
        int startingBalance = SettingKey.STARTING_BALANCE.get();
        PendingTransaction transaction = new PendingTransaction(
                UpdateType.SET, TransactionType.SET,
                "Starting balance", null, startingBalance);
        boolean committed = JdbcTransactionExecutor.execute(connection, transactionConnection -> {
            if (mysql) {
                this.pointsDatabaseStorage.set(transactionConnection, createdAccountId,
                        startingBalance, startingBalance);
                concurrentAccountId.set(
                        this.lookupCachedUUID(transactionConnection, accountName));
                if (concurrentAccountId.get() != null)
                    return false;
            } else {
                concurrentAccountId.set(
                        this.lookupCachedUUID(transactionConnection, accountName));
                if (concurrentAccountId.get() != null)
                    return true;
                this.pointsDatabaseStorage.set(transactionConnection, createdAccountId,
                        startingBalance, startingBalance);
            }

            this.pointsDatabaseStorage.upsertUsernames(transactionConnection,
                    Collections.singletonMap(createdAccountId, accountName));
            this.logTransaction(transactionConnection, createdAccountId, transaction);
            return true;
        });
        if (concurrentAccountId.get() != null)
            return new NamedAccountCreation(concurrentAccountId.get(), false);
        if (!committed)
            throw new SQLException("Account creation transaction was rejected");
        return new NamedAccountCreation(createdAccountId, true);
    }

    public void deleteAccount(UUID accountID) {
        this.deleteAccountWithResult(accountID);
    }

    public boolean deleteAccountWithResult(UUID accountID) {
        return this.datasetLock.withRead(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withLock(accountID, () -> {
                String username = this.lookupCachedUsernameFromDatabase(accountID).getValue();
                boolean committed = this.executeAtomic(connection -> {
                    this.pointsDatabaseStorage.deleteAccount(connection, accountID);
                    return true;
                });
                if (!committed)
                    return false;

                this.accountNameReservations.remove(accountID);
                if (username != null)
                    this.allAccountNames.remove(username);
                this.publishCommittedUpdates(Collections.singleton(accountID));
                return true;
            });
        });
    }

    public void importData(Map<UUID, Integer> data, Map<UUID, String> cachedUsernames) {
        this.importDataWithResult(data, cachedUsernames, Collections.emptyList());
    }

    public boolean importDataWithResult(Map<UUID, Integer> data, Map<UUID, String> cachedUsernames,
                                        Collection<TemporaryPointGrant> temporaryGrants) {
        return this.datasetLock.withWrite(() -> this.accountLocks.withAllLocks(() -> {
            if (!this.active)
                return false;

            boolean committed = this.executeDatasetReplacement(connection ->
                    this.pointsDatabaseStorage.replaceAllAndGetAffectedAccountIds(
                            connection, data, cachedUsernames, temporaryGrants,
                            System.currentTimeMillis()).isPresent());
            if (!committed)
                return false;

            this.accountNameReservations.clear();
            this.allAccountNames.clear();
            this.allAccountNames.addAll(cachedUsernames.values());
            this.publishCommittedDatasetUpdate();
            return true;
        }));
    }

    public boolean importLegacyTable(String tableName) {
        return this.datasetLock.withWrite(() -> {
            if (!this.active)
                return false;
            return this.accountLocks.withAllLocks(() -> this.importLegacyTableLocked(tableName));
        });
    }

    private boolean importLegacyTableLocked(String tableName) {
        boolean committed = this.executeAtomic(connection -> {
            this.pointsDatabaseStorage.importLegacyBalances(connection, tableName);
            return true;
        });
        if (!committed)
            return false;

        this.publishCommittedDatasetUpdate();
        return true;
    }

    public void updateCachedUsernames(Map<UUID, String> cachedUsernames) {
        this.datasetLock.runRead(() -> {
            if (!this.active)
                return;
            this.accountLocks.runWithLocks(cachedUsernames.keySet(), () -> {
                boolean committed = this.executeAtomic(connection -> {
                    this.pointsDatabaseStorage.upsertUsernamesWithAccountLocks(connection,
                            cachedUsernames, SettingKey.STARTING_BALANCE.get());
                    return true;
                });
                if (!committed)
                    return;

                for (Map.Entry<UUID, String> entry : cachedUsernames.entrySet())
                    this.accountNameReservations.remove(entry.getValue(), entry.getKey());
                this.allAccountNames.addAll(cachedUsernames.values());
            });
        });
    }

    public String lookupCachedUsername(UUID uuid) {
        return this.datasetLock.withRead(() -> this.accountLocks.withLock(uuid, () -> {
            DatabaseLoadResult<String> stored = this.lookupCachedUsernameFromDatabase(uuid);
            return stored.isSuccessful() && stored.getValue() != null
                    ? stored.getValue()
                    : "Unknown";
        }));
    }

    private DatabaseLoadResult<String> lookupCachedUsernameFromDatabase(UUID uuid) {
        return this.loadFromDatabase(connection -> {
            String query = "SELECT username FROM " + this.getTablePrefix() + "username_cache WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            }
        });
    }

    public UUID lookupCachedUUID(String username) {
        return this.datasetLock.withRead(() -> {
            UUID reserved = this.accountNameReservations.get(username);
            if (reserved != null)
                return reserved;

            DatabaseLoadResult<UUID> stored = this.lookupCachedUUIDFromDatabase(username);
            return stored.isSuccessful() ? stored.getValue() : null;
        });
    }

    private DatabaseLoadResult<UUID> lookupCachedUUIDFromDatabase(String username) {
        return this.loadFromDatabase(connection ->
                this.lookupCachedUUID(connection, username));
    }

    private UUID lookupCachedUUID(Connection connection, String username) throws SQLException {
        String query = "SELECT uuid FROM " + this.getTablePrefix()
                + "username_cache WHERE LOWER(username) = LOWER(?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString(1)) : null;
            }
        }
    }

    private <T> DatabaseLoadResult<T> loadFromDatabase(DatabaseValueLoader<T> loader) {
        AtomicReference<DatabaseLoadResult<T>> result = new AtomicReference<>(DatabaseLoadResult.failure());
        this.connect(connection -> {
            T value = loader.load(connection);
            result.set(DatabaseLoadResult.success(value));
        }, false);
        return result.get();
    }

    private <T> DatabaseLoadResult<T> loadFromDatabaseTransaction(DatabaseValueLoader<T> loader) {
        return this.loadFromDatabaseTransaction(null, loader);
    }

    private <T> DatabaseLoadResult<T> loadFromDatabaseTransaction(
            int isolationLevel, DatabaseValueLoader<T> loader) {
        return this.loadFromDatabaseTransaction(Integer.valueOf(isolationLevel), loader);
    }

    private <T> DatabaseLoadResult<T> loadFromDatabaseTransaction(
            Integer isolationLevel, DatabaseValueLoader<T> loader) {
        AtomicReference<DatabaseLoadResult<T>> result = new AtomicReference<>(DatabaseLoadResult.failure());
        this.connect(connection -> {
            AtomicReference<T> loaded = new AtomicReference<>();
            JdbcTransactionExecutor.Operation operation = transaction -> {
                loaded.set(loader.load(transaction));
                return true;
            };
            boolean committed = isolationLevel == null
                    ? JdbcTransactionExecutor.execute(connection, operation)
                    : JdbcTransactionExecutor.execute(connection, isolationLevel, operation);
            if (committed)
                result.set(DatabaseLoadResult.success(loaded.get()));
        });
        return result.get();
    }

    private boolean executeAtomic(JdbcTransactionExecutor.Operation operation) {
        AtomicBoolean committed = new AtomicBoolean(false);
        this.connect(connection -> committed.set(
                JdbcTransactionExecutor.execute(connection, operation)));
        return committed.get();
    }

    private boolean executeDatasetReplacement(JdbcTransactionExecutor.Operation operation) {
        if (this.databaseConnector instanceof SQLiteConnector)
            return this.executeAtomic(operation);

        AtomicBoolean committed = new AtomicBoolean(false);
        this.connect(connection -> committed.set(JdbcTransactionExecutor.execute(
                connection, Connection.TRANSACTION_SERIALIZABLE, operation)));
        return committed.get();
    }

    private void connect(DatabaseConnector.ConnectionCallback callback) {
        this.connect(callback, true);
    }

    private void connect(DatabaseConnector.ConnectionCallback callback, boolean useTransaction) {
        if (this.databaseExecutor == null) {
            this.databaseConnector.connect(callback, useTransaction);
        } else {
            this.databaseExecutor.connect(callback, useTransaction);
        }
    }

    private void logTransaction(Connection connection, UUID receiver, PendingTransaction transaction) throws SQLException {
        if (!this.logTransactions)
            return;

        String query = "INSERT INTO " + this.getTablePrefix() + "transaction_log (transaction_type, description, source, receiver, amount) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, transaction.getTransactionType().name());
            statement.setString(2, transaction.getSourceDescription());
            if (transaction.getSource() == null) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, transaction.getSource().toString());
            }
            statement.setString(4, receiver.toString());
            statement.setInt(5, transaction.getAmount());
            statement.executeUpdate();
        }
    }

    private void logBulkTransactions(Connection connection, Map<UUID, Integer> amounts,
                                     String description) throws SQLException {
        if (!this.logTransactions || amounts.isEmpty())
            return;

        String query = "INSERT INTO " + this.getTablePrefix() + "transaction_log (transaction_type, description, source, receiver, amount) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (Map.Entry<UUID, Integer> entry : amounts.entrySet()) {
                statement.setString(1, TransactionType.OFFSET.name());
                statement.setString(2, description);
                statement.setNull(3, Types.VARCHAR);
                statement.setString(4, entry.getKey().toString());
                statement.setInt(5, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private String getPointsTableName() {
        if (org.black_ixx.playerpoints.config.SettingKey.LEGACY_DATABASE_MODE.get()) {
            return org.black_ixx.playerpoints.config.SettingKey.LEGACY_DATABASE_NAME.get();
        } else {
            return super.getTablePrefix() + "points";
        }
    }

    private String getTemporaryPointsTableName() {
        return super.getTablePrefix() + "temporary_points";
    }

    private String getUuidColumnName() {
        if (org.black_ixx.playerpoints.config.SettingKey.LEGACY_DATABASE_MODE.get()) {
            return "playername";
        } else {
            return "uuid";
        }
    }

    @Override
    public List<Supplier<? extends DataMigration>> getDataMigrations() {
        if (this.databaseConnector instanceof MySQLConnector) {
            this.databaseConnector = new MigrationLockingDatabaseConnector(
                    this.databaseConnector, "migrations", super.getTablePrefix(),
                    MIGRATION_LOCK_TIMEOUT_SECONDS);
        }

        LegacyDataImportState importState = this.legacyDataImportState;
        return Arrays.asList(
                () -> new _1_Create_Tables(
                        this.getPointsTableName(), this.getUuidColumnName(), importState),
                _2_Add_Table_Username_Cache::new,
                _3_Add_Table_Transaction_Log::new,
                () -> new _4_Add_Table_Temporary_Points(
                        this.getPointsTableName(), this.getUuidColumnName(), importState)
        );
    }

    @FunctionalInterface
    private interface DatabaseValueLoader<T> {

        T load(Connection connection) throws SQLException;

    }

    private static class DatabaseLoadResult<T> {

        private final boolean successful;
        private final T value;

        private DatabaseLoadResult(boolean successful, T value) {
            this.successful = successful;
            this.value = value;
        }

        private static <T> DatabaseLoadResult<T> success(T value) {
            return new DatabaseLoadResult<>(true, value);
        }

        private static <T> DatabaseLoadResult<T> failure() {
            return new DatabaseLoadResult<>(false, null);
        }

        private boolean isSuccessful() {
            return this.successful;
        }

        private T getValue() {
            return this.value;
        }

    }

    private static final class NamedAccountCreation {

        private final UUID accountId;
        private final boolean created;

        private NamedAccountCreation(UUID accountId, boolean created) {
            this.accountId = accountId;
            this.created = created;
        }

        private UUID getAccountId() {
            return this.accountId;
        }

        private boolean wasCreated() {
            return this.created;
        }

    }

}
