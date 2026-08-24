package org.black_ixx.playerpoints.database.migrations;

import dev.rosewood.rosegarden.database.DataMigration;
import dev.rosewood.rosegarden.database.DatabaseConnector;
import dev.rosewood.rosegarden.database.MySQLConnector;
import dev.rosewood.rosegarden.database.SQLiteConnector;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class _1_Create_Tables extends DataMigration {

    private final String activePointsTable;
    private final String activeAccountColumn;
    private final LegacyDataImportState legacyImportState;

    public _1_Create_Tables() {
        this(null, null, null);
    }

    public _1_Create_Tables(LegacyDataImportState legacyImportState) {
        this(null, null, legacyImportState);
    }

    public _1_Create_Tables(String activePointsTable, String activeAccountColumn,
                            LegacyDataImportState legacyImportState) {
        super(1);
        this.activePointsTable = activePointsTable;
        this.activeAccountColumn = activeAccountColumn;
        this.legacyImportState = legacyImportState;
    }

    @Override
    public void migrate(DatabaseConnector connector, Connection connection, String tablePrefix) throws SQLException {
        if (this.activePointsTable != null && this.activePointsTable.indexOf('.') >= 0) {
            throw new SQLException("Schema-qualified points table names are not supported: "
                    + this.activePointsTable);
        }

        String databaseProduct = connection.getMetaData().getDatabaseProductName();
        boolean sqlite = connector instanceof SQLiteConnector
                || databaseProduct.toLowerCase(Locale.ROOT).contains("sqlite");
        boolean mysql = connector instanceof MySQLConnector
                || databaseProduct.toLowerCase(Locale.ROOT).contains("mysql")
                || databaseProduct.toLowerCase(Locale.ROOT).contains("mariadb");
        String autoIncrement = mysql ? " AUTO_INCREMENT" : "";

        boolean managesStandardTable = this.activePointsTable == null
                || (this.activePointsTable.equals(tablePrefix + "points")
                && "uuid".equalsIgnoreCase(this.activeAccountColumn));
        if (!managesStandardTable) {
            if (sqlite && this.legacyImportState != null)
                this.legacyImportState.markMigrationOneRan();
            return;
        }

        String pointsTable = tablePrefix + "points";
        boolean oldTableExists = this.tableExists(connection, "playerpoints");
        boolean pointsTableExists = this.tableExists(connection, pointsTable);
        if (oldTableExists && pointsTableExists) {
            throw new SQLException("Both playerpoints and " + pointsTable
                    + " exist; refusing to guess which points table is authoritative");
        }

        if (oldTableExists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE playerpoints RENAME TO " + pointsTable);
            }
            pointsTableExists = true;
        }

        if (!pointsTableExists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE " + pointsTable + " (" +
                        "id INTEGER PRIMARY KEY" + autoIncrement + ", " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "points INTEGER NOT NULL, " +
                        "UNIQUE (uuid)" +
                        ")");
            }
        }

        boolean hasUuid = this.columnExists(connection, pointsTable, "uuid");
        boolean hasPlayerName = this.columnExists(connection, pointsTable, "playername");
        if (hasUuid && hasPlayerName) {
            throw new SQLException("Points table contains both uuid and playername columns: " + pointsTable);
        }
        if (!hasUuid && hasPlayerName) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + pointsTable + " RENAME COLUMN playername TO uuid");
            }
            hasUuid = true;
        }
        if (!hasUuid)
            throw new SQLException("Points table is missing its uuid column: " + pointsTable);

        if (sqlite && this.legacyImportState != null)
            this.legacyImportState.markMigrationOneRan();
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String pattern = this.escapePattern(tableName, metadata.getSearchStringEscape());
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, pattern, new String[]{"TABLE"})) {
            while (tables.next()) {
                if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME")))
                    return true;
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection, String tableName,
                                 String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String tablePattern = this.escapePattern(tableName, metadata.getSearchStringEscape());
        String columnPattern = this.escapePattern(columnName, metadata.getSearchStringEscape());
        try (ResultSet columns = metadata.getColumns(
                connection.getCatalog(), null, tablePattern, columnPattern)) {
            while (columns.next()) {
                if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String escapePattern(String value, String escape) {
        if (escape == null || escape.isEmpty())
            return value;
        return value.replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

}
