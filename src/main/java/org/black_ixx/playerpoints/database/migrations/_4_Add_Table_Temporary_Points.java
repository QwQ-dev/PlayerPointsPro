package org.black_ixx.playerpoints.database.migrations;

import dev.rosewood.rosegarden.database.DataMigration;
import dev.rosewood.rosegarden.database.DatabaseConnector;
import dev.rosewood.rosegarden.database.MySQLConnector;
import org.black_ixx.playerpoints.database.DatabaseIndexMetadata;
import org.black_ixx.playerpoints.database.MySqlNamedLock;
import org.black_ixx.playerpoints.database.PointsBackup;
import org.black_ixx.playerpoints.database.PointsBackupParser;
import org.black_ixx.playerpoints.database.PointsDatabaseStorage;
import org.black_ixx.playerpoints.database.TemporaryPointsStorage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class _4_Add_Table_Temporary_Points extends DataMigration {

    private static final int MIGRATION_LOCK_TIMEOUT_SECONDS = 60;

    private final String pointsTable;
    private final String accountColumn;
    private final LegacyDataImportState legacyImportState;

    public _4_Add_Table_Temporary_Points(String pointsTable, String accountColumn) {
        this(pointsTable, accountColumn, null);
    }

    public _4_Add_Table_Temporary_Points(String pointsTable, String accountColumn,
                                         LegacyDataImportState legacyImportState) {
        super(4);
        this.pointsTable = pointsTable;
        this.accountColumn = accountColumn;
        this.legacyImportState = legacyImportState;
    }

    @Override
    public void migrate(DatabaseConnector connector, Connection connection, String tablePrefix) throws SQLException {
        if (this.pointsTable.indexOf('.') >= 0) {
            throw new SQLException("Schema-qualified points table names are not supported: "
                    + this.pointsTable);
        }

        boolean mysql = this.isMySql(connector, connection);
        if (mysql) {
            MySqlNamedLock.execute(connection, tablePrefix + "migrations",
                    "temporary-points-v4", MIGRATION_LOCK_TIMEOUT_SECONDS,
                    lockedConnection -> {
                        this.migrateSchema(lockedConnection, tablePrefix, true);
                        return null;
                    });
            return;
        }
        this.migrateSchema(connection, tablePrefix, false);
    }

    private void migrateSchema(Connection connection, String tablePrefix,
                               boolean mysql) throws SQLException {
        String tableName = tablePrefix + "temporary_points";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tableName + " ("
                    + "grant_id VARCHAR(36) NOT NULL, "
                    + "uuid VARCHAR(36) NOT NULL, "
                    + "points INTEGER NOT NULL, "
                    + "expires_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (grant_id)"
                    + ")" + (mysql ? " ENGINE=InnoDB" : ""));
        }

        if (mysql) {
            this.ensureInnoDb(connection, this.pointsTable);
            this.ensureInnoDb(connection, tablePrefix + "username_cache");
            this.ensureInnoDb(connection, tablePrefix + "transaction_log");
            this.ensureInnoDb(connection, tableName);
        }

        this.createUniqueIndexIfMissing(connection, this.pointsTable,
                tablePrefix + "points_account_unique_index", this.accountColumn, mysql);
        this.createUniqueIndexIfMissing(connection, tablePrefix + "username_cache",
                tablePrefix + "username_cache_uuid_unique_index", "uuid", mysql);
        this.createUniqueIndexIfMissing(connection, tableName,
                tablePrefix + "temporary_points_grant_unique_index", "grant_id", mysql);
        this.ensureSecondaryIndex(connection, tableName,
                tablePrefix + "temporary_points_uuid_index", "uuid", mysql);
        this.ensureSecondaryIndex(connection, tableName,
                tablePrefix + "temporary_points_expiration_index", "expires_at", mysql);

        if (!mysql)
            this.importLegacyBackup(connection, tablePrefix, tableName);
    }

    private void importLegacyBackup(Connection connection, String tablePrefix,
                                    String temporaryTable) throws SQLException {
        if (this.legacyImportState == null || !this.legacyImportState.shouldImport())
            return;

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 FROM " + this.pointsTable + " LIMIT 1")) {
            if (result.next())
                return;
        }

        try {
            long now = System.currentTimeMillis();
            PointsBackup backup = PointsBackupParser.load(this.legacyImportState.getBackupFile(), now);
            TemporaryPointsStorage temporaryStorage = new TemporaryPointsStorage(temporaryTable, false);
            PointsDatabaseStorage storage = new PointsDatabaseStorage(
                    this.pointsTable, this.accountColumn, tablePrefix + "username_cache",
                    temporaryStorage, PointsDatabaseStorage.Dialect.SQLITE_LEGACY);
            if (!storage.replaceAll(connection, backup.getPermanentPoints(), backup.getUsernames(),
                    backup.getTemporaryGrants(), now)) {
                throw new SQLException("storage.yml contains balances that cannot be imported safely");
            }
            this.legacyImportState.markImported();
        } catch (IOException | RuntimeException e) {
            throw new SQLException("Unable to import storage.yml", e);
        }
    }

    private boolean isMySql(DatabaseConnector connector, Connection connection) throws SQLException {
        if (connector instanceof MySQLConnector)
            return true;

        String productName = connection.getMetaData().getDatabaseProductName();
        return "MySQL".equalsIgnoreCase(productName)
                || productName.toLowerCase(Locale.ROOT).contains("mariadb");
    }

    private void ensureInnoDb(Connection connection, String tableName) throws SQLException {
        String query = "SELECT ENGINE FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        String engine = null;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next())
                    engine = result.getString(1);
            }
        }
        if (engine == null)
            throw new SQLException("Required database table is missing: " + tableName);
        if ("InnoDB".equalsIgnoreCase(engine))
            return;

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ENGINE=InnoDB");
        }
    }

    private void createUniqueIndexIfMissing(Connection connection, String tableName,
                                            String indexName, String columnName,
                                            boolean mysql) throws SQLException {
        DatabaseIndexMetadata.IndexDefinition namedIndex =
                DatabaseIndexMetadata.load(connection, tableName, mysql)
                        .get(indexName.toLowerCase(Locale.ROOT));
        if (namedIndex != null) {
            if (namedIndex.isUniqueSingleColumn(columnName))
                return;
            this.dropIndex(connection, tableName, indexName, mysql);
        }

        if (this.hasSingleColumnUniqueIndex(connection, tableName, columnName, mysql))
            return;

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE UNIQUE INDEX " + indexName + " ON " + tableName
                    + " (" + columnName + ")");
        }
    }

    private boolean hasSingleColumnUniqueIndex(Connection connection, String tableName,
                                               String columnName, boolean mysql) throws SQLException {
        return DatabaseIndexMetadata.load(connection, tableName, mysql).values().stream()
                .anyMatch(index -> index.isUniqueSingleColumn(columnName));
    }

    private void ensureSecondaryIndex(Connection connection, String tableName,
                                      String indexName, String columnName,
                                      boolean mysql) throws SQLException {
        DatabaseIndexMetadata.IndexDefinition namedIndex =
                DatabaseIndexMetadata.load(connection, tableName, mysql)
                        .get(indexName.toLowerCase(Locale.ROOT));
        if (namedIndex != null && namedIndex.isNonUniqueSingleColumn(columnName))
            return;

        if (namedIndex != null)
            this.dropIndex(connection, tableName, indexName, mysql);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + indexName + " ON " + tableName
                    + " (" + columnName + ")");
        }
    }

    private void dropIndex(Connection connection, String tableName,
                           String indexName, boolean mysql) throws SQLException {
        String query = mysql
                ? "DROP INDEX " + indexName + " ON " + tableName
                : "DROP INDEX " + indexName;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(query);
        }
    }

}
