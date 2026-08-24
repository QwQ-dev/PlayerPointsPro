package org.black_ixx.playerpoints.database.migrations;

import dev.rosewood.rosegarden.database.DataMigration;
import dev.rosewood.rosegarden.database.DatabaseConnector;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class _2_Add_Table_Username_Cache extends DataMigration {

    public _2_Add_Table_Username_Cache() {
        super(2);
    }

    @Override
    public void migrate(DatabaseConnector connector, Connection connection, String tablePrefix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "username_cache (" +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "username VARCHAR(30) NOT NULL, " +
                    "UNIQUE (uuid)" +
                    ")");
        }

        this.createIndexIfMissing(connection, tablePrefix + "username_cache",
                tablePrefix + "username_cache_uuid_index", "uuid");
        this.createIndexIfMissing(connection, tablePrefix + "username_cache",
                tablePrefix + "username_cache_username_index", "username");
    }

    private void createIndexIfMissing(Connection connection, String tableName,
                                      String indexName, String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(
                connection.getCatalog(), null, tableName, false, false)) {
            while (indexes.next()) {
                String existingName = indexes.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(existingName))
                    return;
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + indexName + " ON " + tableName
                    + " (" + columnName + ")");
        }
    }

}
