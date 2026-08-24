package org.black_ixx.playerpoints.database;

import dev.rosewood.rosegarden.database.DatabaseConnector;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MigrationLockingDatabaseConnector implements DatabaseConnector {

    private final DatabaseConnector delegate;
    private final String lockNamespace;
    private final String lockValue;
    private final int timeoutSeconds;
    private final AtomicBoolean lockNextCallback;

    public MigrationLockingDatabaseConnector(DatabaseConnector delegate, String lockNamespace,
                                             String lockValue, int timeoutSeconds) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lockNamespace = Objects.requireNonNull(lockNamespace, "lockNamespace");
        this.lockValue = Objects.requireNonNull(lockValue, "lockValue");
        if (timeoutSeconds < 0)
            throw new IllegalArgumentException("timeoutSeconds must not be negative");
        this.timeoutSeconds = timeoutSeconds;
        this.lockNextCallback = new AtomicBoolean(true);
    }

    public DatabaseConnector getDelegate() {
        return this.delegate;
    }

    @Override
    public void connect(ConnectionCallback callback) {
        if (this.lockNextCallback.compareAndSet(true, false)) {
            this.delegate.connect(connection -> this.runLocked(connection, callback));
        } else {
            this.delegate.connect(callback);
        }
    }

    @Override
    public void connect(ConnectionCallback callback, boolean useTransaction) {
        if (this.lockNextCallback.compareAndSet(true, false)) {
            this.delegate.connect(
                    connection -> this.runLocked(connection, callback), useTransaction);
        } else {
            this.delegate.connect(callback, useTransaction);
        }
    }

    private void runLocked(Connection connection, ConnectionCallback callback) throws SQLException {
        MySqlNamedLock.execute(connection, this.lockNamespace, this.lockValue,
                this.timeoutSeconds, lockedConnection -> {
                    callback.accept(lockedConnection);
                    return null;
                });
    }

    @Override
    public Connection connect() throws SQLException {
        return this.delegate.connect();
    }

    @Override
    public void closeConnection() {
        this.delegate.closeConnection();
    }

    @Override
    public Object getLock() {
        return this.delegate.getLock();
    }

    @Override
    public boolean isFinished() {
        return this.delegate.isFinished();
    }

    @Override
    public void cleanup() {
        this.delegate.cleanup();
    }

}
