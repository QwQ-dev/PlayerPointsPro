package org.black_ixx.playerpoints.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DatabaseIndexMetadata {

    private DatabaseIndexMetadata() {

    }

    public static Map<String, IndexDefinition> load(Connection connection, String tableName,
                                                    boolean mysql) throws SQLException {
        return mysql
                ? loadMySql(connection, tableName)
                : loadSqlite(connection, tableName);
    }

    private static Map<String, IndexDefinition> loadMySql(Connection connection,
                                                          String tableName) throws SQLException {
        String query = "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SUB_PART "
                + "FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX";
        Map<String, IndexDefinition> indexes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String indexName = result.getString("INDEX_NAME");
                    if (indexName == null)
                        continue;
                    String key = indexName.toLowerCase(Locale.ROOT);
                    IndexDefinition index = indexes.get(key);
                    if (index == null) {
                        index = new IndexDefinition(result.getBoolean("NON_UNIQUE"));
                        indexes.put(key, index);
                    }
                    index.addColumn(result.getString("COLUMN_NAME"),
                            result.getObject("SUB_PART") != null);
                }
            }
        }
        return indexes;
    }

    private static Map<String, IndexDefinition> loadSqlite(Connection connection,
                                                           String tableName) throws SQLException {
        Map<String, IndexDefinition> indexes = new LinkedHashMap<>();
        String listQuery = "SELECT name, \"unique\", partial FROM pragma_index_list(?) ORDER BY seq";
        try (PreparedStatement statement = connection.prepareStatement(listQuery)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String indexName = result.getString("name");
                    if (indexName == null)
                        continue;
                    IndexDefinition index = new IndexDefinition(!result.getBoolean("unique"));
                    index.exact = !result.getBoolean("partial");
                    indexes.put(indexName.toLowerCase(Locale.ROOT), index);
                }
            }
        }

        String infoQuery = "SELECT name FROM pragma_index_info(?) ORDER BY seqno";
        for (Map.Entry<String, IndexDefinition> entry : indexes.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement(infoQuery)) {
                statement.setString(1, entry.getKey());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next())
                        entry.getValue().addColumn(result.getString("name"), false);
                }
            }
        }
        return indexes;
    }

    public static final class IndexDefinition {

        private final boolean nonUnique;
        private final List<String> columns;
        private boolean exact;

        private IndexDefinition(boolean nonUnique) {
            this.nonUnique = nonUnique;
            this.columns = new ArrayList<>();
            this.exact = true;
        }

        private void addColumn(String columnName, boolean prefix) {
            if (columnName == null || prefix) {
                this.exact = false;
                return;
            }
            this.columns.add(columnName);
        }

        public boolean isUnique() {
            return !this.nonUnique;
        }

        public boolean isUniqueSingleColumn(String columnName) {
            return !this.nonUnique && this.isExactSingleColumn(columnName);
        }

        public boolean isNonUniqueSingleColumn(String columnName) {
            return this.nonUnique && this.isExactSingleColumn(columnName);
        }

        private boolean isExactSingleColumn(String columnName) {
            return this.exact && this.columns.size() == 1
                    && columnName.equalsIgnoreCase(this.columns.get(0));
        }

    }

}
