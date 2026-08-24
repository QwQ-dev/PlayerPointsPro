package org.black_ixx.playerpoints.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Base64;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MySqlNamedLock {

    private static final Logger LOGGER = Logger.getLogger(MySqlNamedLock.class.getName());

    private MySqlNamedLock() {

    }

    public static <T> T execute(Connection connection, String namespace, String value,
                                int timeoutSeconds, Operation<T> operation) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(operation, "operation");
        if (timeoutSeconds < 0)
            throw new IllegalArgumentException("timeoutSeconds must not be negative");

        String lockName = createLockName(connection, namespace, value);
        acquire(connection, lockName, timeoutSeconds);
        Throwable operationFailure = null;
        try {
            return operation.execute(connection);
        } catch (SQLException | RuntimeException | Error failure) {
            operationFailure = failure;
            throw failure;
        } finally {
            release(connection, lockName, operationFailure);
        }
    }

    private static String createLockName(Connection connection, String namespace,
                                         String value) throws SQLException {
        String catalog = connection.getCatalog();
        String input = String.valueOf(catalog) + '\0' + namespace + '\0' + value;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return "playerpoints:" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("SHA-256 is unavailable", failure);
        }
    }

    private static void acquire(Connection connection, String lockName,
                                int timeoutSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lockName);
            statement.setInt(2, timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    throw new SQLException("GET_LOCK returned no result");
                int status = result.getInt(1);
                if (result.wasNull())
                    throw new SQLException("GET_LOCK could not acquire the account-name lock");
                if (status == 0)
                    throw new SQLTimeoutException("Timed out waiting for the account-name lock");
                if (status != 1)
                    throw new SQLException("Unexpected GET_LOCK result: " + status);
            }
        }
    }

    private static void release(Connection connection, String lockName,
                                Throwable operationFailure) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    logSafely(Level.WARNING,
                            "The MySQL named lock was no longer owned by this connection",
                            null);
                }
            }
        } catch (SQLException releaseFailure) {
            abortConnection(connection, releaseFailure);
            if (operationFailure != null) {
                operationFailure.addSuppressed(releaseFailure);
            } else {
                logSafely(Level.SEVERE, "Unable to release the MySQL account-name lock",
                        releaseFailure);
            }
        }
    }

    private static void abortConnection(Connection connection, SQLException releaseFailure) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException | Error abortFailure) {
            releaseFailure.addSuppressed(abortFailure);
        }
    }

    private static void logSafely(Level level, String message, Throwable failure) {
        try {
            LOGGER.log(level, message, failure);
        } catch (RuntimeException | Error loggingFailure) {
            if (failure != null)
                failure.addSuppressed(loggingFailure);
        }
    }

    @FunctionalInterface
    public interface Operation<T> {

        T execute(Connection connection) throws SQLException;

    }

}
