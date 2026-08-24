package org.black_ixx.playerpoints.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JdbcTransactionExecutor {

    private static final Logger LOGGER = Logger.getLogger(JdbcTransactionExecutor.class.getName());

    private JdbcTransactionExecutor() {

    }

    public static boolean execute(Connection connection, Operation operation) throws SQLException {
        return execute(connection, null, operation);
    }

    public static boolean execute(Connection connection, int isolationLevel,
                                  Operation operation) throws SQLException {
        return execute(connection, Integer.valueOf(isolationLevel), operation);
    }

    private static boolean execute(Connection connection, Integer isolationLevel,
                                   Operation operation) throws SQLException {
        boolean autoCommitWasEnabled = connection.getAutoCommit();
        Integer originalIsolation = null;
        boolean isolationChanged = false;
        if (isolationLevel != null) {
            originalIsolation = connection.getTransactionIsolation();
            isolationChanged = originalIsolation.intValue() != isolationLevel;
            if (isolationChanged && !autoCommitWasEnabled) {
                throw new SQLException(
                        "Cannot change isolation level for an existing transaction");
            }
        }

        boolean result;
        boolean committed = false;
        try {
            if (isolationChanged)
                connection.setTransactionIsolation(isolationLevel);
            if (autoCommitWasEnabled)
                connection.setAutoCommit(false);

            result = operation.execute(connection);
            if (result) {
                connection.commit();
                committed = true;
            } else {
                connection.rollback();
            }
        } catch (SQLException | RuntimeException | Error failure) {
            rollbackAfterFailure(connection, failure);
            restoreConnectionState(connection, autoCommitWasEnabled, originalIsolation,
                    isolationChanged, false, failure);
            throw failure;
        }

        restoreConnectionState(connection, autoCommitWasEnabled, originalIsolation,
                isolationChanged, committed, null);
        return result;
    }

    private static void rollbackAfterFailure(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException | Error rollbackFailure) {
            addSuppressed(failure, rollbackFailure);
        }
    }

    private static void restoreConnectionState(Connection connection,
                                               boolean restoreAutoCommit,
                                               Integer originalIsolation,
                                               boolean restoreIsolation,
                                               boolean committed,
                                               Throwable primaryFailure) throws SQLException {
        Throwable cleanupFailure = null;
        if (restoreAutoCommit) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException | RuntimeException | Error failure) {
                cleanupFailure = failure;
            }
        }
        if (restoreIsolation) {
            try {
                connection.setTransactionIsolation(originalIsolation);
            } catch (SQLException | RuntimeException | Error failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    addSuppressed(cleanupFailure, failure);
                }
            }
        }
        if (cleanupFailure == null)
            return;

        if (primaryFailure != null) {
            addSuppressed(primaryFailure, cleanupFailure);
            return;
        }
        if (!committed)
            throwCleanupFailure(cleanupFailure);

        try {
            LOGGER.log(Level.WARNING,
                    "Transaction committed, but restoring connection state failed",
                    cleanupFailure);
        } catch (RuntimeException | Error loggingFailure) {
            addSuppressed(cleanupFailure, loggingFailure);
        }
    }

    private static void throwCleanupFailure(Throwable failure) throws SQLException {
        if (failure instanceof SQLException)
            throw (SQLException) failure;
        if (failure instanceof RuntimeException)
            throw (RuntimeException) failure;
        throw (Error) failure;
    }

    private static void addSuppressed(Throwable failure, Throwable suppressed) {
        if (failure != suppressed)
            failure.addSuppressed(suppressed);
    }

    @FunctionalInterface
    public interface Operation {

        boolean execute(Connection connection) throws SQLException;

    }

}
