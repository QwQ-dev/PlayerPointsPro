package org.black_ixx.playerpoints.database;

import org.black_ixx.playerpoints.models.TransactionType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class DatabaseSchemaVerifier {

    private static final int MAX_TRANSACTION_TYPE_LENGTH = Arrays.stream(TransactionType.values())
            .mapToInt(type -> type.name().length())
            .max()
            .orElse(0);

    private final String tablePrefix;
    private final String pointsTable;
    private final String accountColumn;
    private final boolean mysql;

    public DatabaseSchemaVerifier(String tablePrefix, String pointsTable,
                                  String accountColumn, boolean mysql) {
        this.tablePrefix = Objects.requireNonNull(tablePrefix, "tablePrefix");
        this.pointsTable = Objects.requireNonNull(pointsTable, "pointsTable");
        this.accountColumn = Objects.requireNonNull(accountColumn, "accountColumn");
        this.mysql = mysql;
    }

    public void requireReady(Connection connection, int requiredRevision) throws SQLException {
        if (this.pointsTable.indexOf('.') >= 0)
            throw new SQLException("Schema-qualified points table names are not supported: " + this.pointsTable);

        String temporaryTable = this.tablePrefix + "temporary_points";
        String usernameTable = this.tablePrefix + "username_cache";
        String transactionTable = this.tablePrefix + "transaction_log";

        MigrationStateVerifier.requireAtLeast(
                connection, this.tablePrefix + "migrations", requiredRevision);
        Map<String, ColumnDefinition> pointsColumns = this.requireColumns(
                connection, this.pointsTable, this.accountColumn, "points");
        Map<String, ColumnDefinition> temporaryColumns = this.requireColumns(connection, temporaryTable,
                "grant_id", "uuid", "points", "expires_at");
        Map<String, ColumnDefinition> usernameColumns = this.requireColumns(
                connection, usernameTable, "uuid", "username");
        Map<String, ColumnDefinition> transactionColumns = this.requireColumns(connection, transactionTable,
                "transaction_type", "description", "source", "receiver", "amount", "timestamp");

        this.requireText(this.pointsTable, this.accountColumn,
                pointsColumns.get(this.accountColumn.toLowerCase(Locale.ROOT)), 36, true);
        this.requireInteger(this.pointsTable, "points", pointsColumns.get("points"), false, true, false);
        this.requireText(temporaryTable, "grant_id", temporaryColumns.get("grant_id"), 36, true);
        this.requireText(temporaryTable, "uuid", temporaryColumns.get("uuid"), 36, true);
        this.requireInteger(temporaryTable, "points", temporaryColumns.get("points"), false, true, false);
        this.requireInteger(temporaryTable, "expires_at", temporaryColumns.get("expires_at"), true, true, false);
        this.requireText(usernameTable, "uuid", usernameColumns.get("uuid"), 36, true);
        this.requireText(usernameTable, "username", usernameColumns.get("username"), 17, true);
        this.requireText(transactionTable, "transaction_type",
                transactionColumns.get("transaction_type"), MAX_TRANSACTION_TYPE_LENGTH, true);
        this.requireText(transactionTable, "description",
                transactionColumns.get("description"), 16, true);
        this.requireText(transactionTable, "source", transactionColumns.get("source"), 36, false);
        this.requireNullable(transactionTable, "source", transactionColumns.get("source"));
        this.requireText(transactionTable, "receiver", transactionColumns.get("receiver"), 36, true);
        this.requireInteger(transactionTable, "amount", transactionColumns.get("amount"), false, true, true);
        this.requireOptionalOnInsert(transactionTable, "timestamp", transactionColumns.get("timestamp"));

        this.requireNoMandatoryExtraColumns(this.pointsTable, pointsColumns,
                this.accountColumn, "points");
        this.requireNoMandatoryExtraColumns(temporaryTable, temporaryColumns,
                "grant_id", "uuid", "points", "expires_at");
        this.requireNoMandatoryExtraColumns(usernameTable, usernameColumns, "uuid", "username");
        this.requireNoMandatoryExtraColumns(transactionTable, transactionColumns,
                "transaction_type", "description", "source", "receiver", "amount");

        Map<String, DatabaseIndexMetadata.IndexDefinition> pointsIndexes =
                DatabaseIndexMetadata.load(connection, this.pointsTable, this.mysql);
        Map<String, DatabaseIndexMetadata.IndexDefinition> temporaryIndexes =
                DatabaseIndexMetadata.load(connection, temporaryTable, this.mysql);
        Map<String, DatabaseIndexMetadata.IndexDefinition> usernameIndexes =
                DatabaseIndexMetadata.load(connection, usernameTable, this.mysql);
        Map<String, DatabaseIndexMetadata.IndexDefinition> transactionIndexes =
                DatabaseIndexMetadata.load(connection, transactionTable, this.mysql);

        this.requireSingleColumnUniqueIndex(pointsIndexes, this.pointsTable, this.accountColumn);
        this.requireSingleColumnUniqueIndex(temporaryIndexes, temporaryTable, "grant_id");
        this.requireSingleColumnUniqueIndex(usernameIndexes, usernameTable, "uuid");
        this.requireSingleColumnNonUniqueIndex(temporaryIndexes, temporaryTable, "uuid");
        this.requireSingleColumnNonUniqueIndex(temporaryIndexes, temporaryTable, "expires_at");

        Set<String> allowedPointUniqueColumns = new HashSet<>();
        allowedPointUniqueColumns.add(this.accountColumn);
        ColumnDefinition idColumn = pointsColumns.get("id");
        if (idColumn != null && idColumn.autoIncrement
                && idColumn.isInteger(false, !this.mysql)) {
            allowedPointUniqueColumns.add("id");
        }
        this.requireNoUnexpectedUniqueIndex(pointsIndexes, this.pointsTable, allowedPointUniqueColumns);
        this.requireNoUnexpectedUniqueIndex(usernameIndexes, usernameTable,
                Collections.singleton("uuid"));
        this.requireNoUnexpectedUniqueIndex(temporaryIndexes, temporaryTable,
                Collections.singleton("grant_id"));
        this.requireNoUnexpectedUniqueIndex(transactionIndexes, transactionTable,
                Collections.emptySet());

        if (this.mysql) {
            this.requireInnoDb(connection, this.pointsTable);
            this.requireInnoDb(connection, temporaryTable);
            this.requireInnoDb(connection, usernameTable);
            this.requireInnoDb(connection, transactionTable);
        }
    }

    private Map<String, ColumnDefinition> requireColumns(Connection connection, String tableName,
                                                         String... requiredColumns) throws SQLException {
        Map<String, ColumnDefinition> actualColumns = new HashMap<>();
        Set<String> generatedDefaults = this.mysql
                ? this.loadMySqlGeneratedDefaults(connection, tableName)
                : Collections.emptySet();
        DatabaseMetaData metadata = connection.getMetaData();
        String tablePattern = this.escapeMetadataPattern(tableName, metadata.getSearchStringEscape());
        try (ResultSet columns = metadata.getColumns(
                connection.getCatalog(), null, tablePattern, null)) {
            while (columns.next()) {
                if (!tableName.equalsIgnoreCase(columns.getString("TABLE_NAME")))
                    continue;
                String columnName = columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                boolean generatedDefault = generatedDefaults.contains(columnName);
                boolean generatedColumn = "YES".equalsIgnoreCase(
                        columns.getString("IS_GENERATEDCOLUMN")) && !generatedDefault;
                actualColumns.put(columnName, new ColumnDefinition(
                        columns.getInt("DATA_TYPE"), columns.getString("TYPE_NAME"),
                        columns.getInt("COLUMN_SIZE"), columns.getInt("DECIMAL_DIGITS"),
                        columns.getInt("NULLABLE"), columns.getString("COLUMN_DEF"),
                        "YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT")),
                        generatedColumn, generatedDefault));
            }
        }

        for (String requiredColumn : requiredColumns) {
            if (!actualColumns.containsKey(requiredColumn.toLowerCase(Locale.ROOT))) {
                throw new SQLException("Required column " + tableName + "."
                        + requiredColumn + " is missing");
            }
        }
        return actualColumns;
    }

    private Set<String> loadMySqlGeneratedDefaults(Connection connection,
                                                   String tableName) throws SQLException {
        String query = "SELECT COLUMN_NAME, EXTRA FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        Set<String> generatedDefaults = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String extra = result.getString("EXTRA");
                    if (extra != null && extra.toLowerCase(Locale.ROOT).contains("default_generated")) {
                        generatedDefaults.add(result.getString("COLUMN_NAME")
                                .toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return generatedDefaults;
    }

    private String escapeMetadataPattern(String value, String escape) {
        if (escape == null || escape.isEmpty())
            return value;
        return value.replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    private void requireText(String tableName, String columnName, ColumnDefinition column,
                             int minimumLength, boolean nonNull) throws SQLException {
        if (!column.isText(this.mysql) || column.size < minimumLength) {
            throw new SQLException("Required column " + tableName + "." + columnName
                    + " must hold at least " + minimumLength + " characters, but is " + column.typeName);
        }
        this.requireNullability(tableName, columnName, column, nonNull);
        this.requireWritable(tableName, columnName, column);
    }

    private void requireInteger(String tableName, String columnName, ColumnDefinition column,
                                boolean require64Bit, boolean nonNull,
                                boolean requireSigned) throws SQLException {
        if (!column.isInteger(require64Bit, !this.mysql)
                || (requireSigned && column.isUnsigned())) {
            throw new SQLException("Required column " + tableName + "." + columnName
                    + (require64Bit ? " must be a 64-bit integer, but is " : " must be an integer, but is ")
                    + column.typeName);
        }
        this.requireNullability(tableName, columnName, column, nonNull);
        this.requireWritable(tableName, columnName, column);
    }

    private void requireNullability(String tableName, String columnName, ColumnDefinition column,
                                    boolean nonNull) throws SQLException {
        if (nonNull && column.nullable != DatabaseMetaData.columnNoNulls) {
            throw new SQLException("Required column " + tableName + "." + columnName
                    + " must be NOT NULL");
        }
    }

    private void requireNullable(String tableName, String columnName,
                                 ColumnDefinition column) throws SQLException {
        if (column.nullable != DatabaseMetaData.columnNullable) {
            throw new SQLException("Required column " + tableName + "." + columnName
                    + " must accept NULL values");
        }
    }

    private void requireOptionalOnInsert(String tableName, String columnName,
                                         ColumnDefinition column) throws SQLException {
        if (!column.canOmitOnInsert(this.mysql)) {
            throw new SQLException("Required column " + tableName + "." + columnName
                    + " must accept NULL or define a default value");
        }
    }

    private void requireWritable(String tableName, String columnName,
                                 ColumnDefinition column) throws SQLException {
        if (column.generated) {
            throw new SQLException("Required column " + tableName + "." + columnName
                    + " must not be generated");
        }
    }

    private void requireNoMandatoryExtraColumns(String tableName,
                                                Map<String, ColumnDefinition> columns,
                                                String... insertedColumns) throws SQLException {
        Set<String> inserted = new HashSet<>();
        for (String column : insertedColumns)
            inserted.add(column.toLowerCase(Locale.ROOT));

        for (Map.Entry<String, ColumnDefinition> entry : columns.entrySet()) {
            if (!inserted.contains(entry.getKey())
                    && !entry.getValue().canOmitOnInsert(this.mysql)) {
                throw new SQLException("Column " + tableName + "." + entry.getKey()
                        + " cannot be populated by PlayerPoints inserts");
            }
        }
    }

    private void requireSingleColumnUniqueIndex(
            Map<String, DatabaseIndexMetadata.IndexDefinition> indexes,
            String tableName,
            String columnName) throws SQLException {
        boolean found = indexes.values().stream()
                .anyMatch(index -> index.isUniqueSingleColumn(columnName));
        if (!found) {
            throw new SQLException("Required unique index on " + tableName
                    + " (" + columnName + ") is missing");
        }
    }

    private void requireSingleColumnNonUniqueIndex(
            Map<String, DatabaseIndexMetadata.IndexDefinition> indexes,
            String tableName,
            String columnName) throws SQLException {
        boolean found = indexes.values().stream()
                .anyMatch(index -> index.isNonUniqueSingleColumn(columnName));
        if (!found) {
            throw new SQLException("Required index on " + tableName
                    + " (" + columnName + ") is missing or unique");
        }
    }

    private void requireNoUnexpectedUniqueIndex(
            Map<String, DatabaseIndexMetadata.IndexDefinition> indexes,
            String tableName,
            Set<String> allowedColumns) throws SQLException {
        boolean unexpected = indexes.values().stream()
                .anyMatch(index -> index.isUnique()
                        && allowedColumns.stream().noneMatch(index::isUniqueSingleColumn));
        if (unexpected)
            throw new SQLException("Unexpected unique index on " + tableName);
    }

    private void requireInnoDb(Connection connection, String tableName) throws SQLException {
        String query = "SELECT ENGINE FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"InnoDB".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("Required table is not using InnoDB: " + tableName);
                }
            }
        }
    }

    private static final class ColumnDefinition {

        private static final Pattern SQLITE_NUMBER = Pattern.compile(
                "[+-]?(?:0[xX][0-9a-fA-F]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
        private static final Pattern MYSQL_CURRENT_TIME = Pattern.compile(
                "(?i)(?:CURRENT_TIMESTAMP|CURRENT_TIME|CURRENT_DATE|LOCALTIME|LOCALTIMESTAMP|"
                        + "NOW|CURDATE|CURTIME)(?:\\([0-6]?\\))?");

        private final int jdbcType;
        private final String typeName;
        private final int size;
        private final int decimalDigits;
        private final int nullable;
        private final String defaultValue;
        private final boolean autoIncrement;
        private final boolean generated;
        private final boolean generatedDefault;

        private ColumnDefinition(int jdbcType, String typeName, int size,
                                 int decimalDigits, int nullable, String defaultValue,
                                 boolean autoIncrement, boolean generated,
                                 boolean generatedDefault) {
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.size = size;
            this.decimalDigits = decimalDigits;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
            this.autoIncrement = autoIncrement;
            this.generated = generated;
            this.generatedDefault = generatedDefault;
        }

        private static String unwrapParentheses(String value) {
            String unwrapped = value;
            while (isWrappedInParentheses(unwrapped))
                unwrapped = unwrapped.substring(1, unwrapped.length() - 1).trim();
            return unwrapped;
        }

        private static boolean isWrappedInParentheses(String value) {
            if (value.length() < 2 || value.charAt(0) != '('
                    || value.charAt(value.length() - 1) != ')') {
                return false;
            }

            int depth = 0;
            char quote = 0;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (quote != 0) {
                    if (current == quote) {
                        if (index + 1 < value.length() && value.charAt(index + 1) == quote) {
                            index++;
                        } else {
                            quote = 0;
                        }
                    }
                    continue;
                }
                if (current == '\'' || current == '"') {
                    quote = current;
                } else if (current == '(') {
                    depth++;
                } else if (current == ')' && --depth == 0 && index != value.length() - 1) {
                    return false;
                }
            }
            return depth == 0 && quote == 0;
        }

        private static boolean isQuotedLiteral(String value, char quote) {
            if (value.length() < 2 || value.charAt(0) != quote
                    || value.charAt(value.length() - 1) != quote) {
                return false;
            }
            for (int index = 1; index < value.length() - 1; index++) {
                if (value.charAt(index) != quote)
                    continue;
                if (index + 1 >= value.length() - 1 || value.charAt(index + 1) != quote)
                    return false;
                index++;
            }
            return true;
        }

        private boolean isText(boolean mysql) {
            boolean textJdbcType = this.jdbcType == Types.CHAR || this.jdbcType == Types.VARCHAR
                    || this.jdbcType == Types.LONGVARCHAR || this.jdbcType == Types.NCHAR
                    || this.jdbcType == Types.NVARCHAR || this.jdbcType == Types.LONGNVARCHAR;
            if (!textJdbcType || !mysql)
                return textJdbcType;

            if (this.typeName == null)
                return false;
            switch (this.typeName.toUpperCase(Locale.ROOT)) {
                case "CHAR":
                case "VARCHAR":
                case "NCHAR":
                case "NVARCHAR":
                case "TINYTEXT":
                case "TEXT":
                case "MEDIUMTEXT":
                case "LONGTEXT":
                    return true;
                default:
                    return false;
            }
        }

        private boolean isInteger(boolean require64Bit, boolean sqliteIntegerIs64Bit) {
            if (this.jdbcType == Types.BIGINT)
                return true;
            if (require64Bit && sqliteIntegerIs64Bit && this.jdbcType == Types.INTEGER)
                return true;
            if (!require64Bit && this.jdbcType == Types.INTEGER && this.size >= 10)
                return true;
            int minimumPrecision = require64Bit ? 19 : 10;
            return (this.jdbcType == Types.NUMERIC || this.jdbcType == Types.DECIMAL)
                    && this.decimalDigits == 0 && this.size >= minimumPrecision;
        }

        private boolean isUnsigned() {
            return this.typeName != null
                    && this.typeName.toLowerCase(Locale.ROOT).contains("unsigned");
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean canOmitOnInsert(boolean mysql) {
            return this.nullable == DatabaseMetaData.columnNullable
                    || this.hasUsableDefault(mysql) || this.autoIncrement || this.generated;
        }

        private boolean hasUsableDefault(boolean mysql) {
            if (this.defaultValue == null)
                return false;

            if (mysql) {
                return !this.generatedDefault
                        || SQLITE_NUMBER.matcher(this.defaultValue.trim()).matches()
                        || MYSQL_CURRENT_TIME.matcher(this.defaultValue.trim()).matches();
            }

            String value = unwrapParentheses(this.defaultValue.trim());
            if ("NULL".equalsIgnoreCase(value))
                return false;
            if ("TRUE".equalsIgnoreCase(value) || "FALSE".equalsIgnoreCase(value)
                    || "CURRENT_TIME".equalsIgnoreCase(value)
                    || "CURRENT_DATE".equalsIgnoreCase(value)
                    || "CURRENT_TIMESTAMP".equalsIgnoreCase(value)) {
                return true;
            }
            return SQLITE_NUMBER.matcher(value).matches()
                    || isQuotedLiteral(value, '\'')
                    || isQuotedLiteral(value, '"')
                    || value.length() >= 3
                    && (value.charAt(0) == 'x' || value.charAt(0) == 'X')
                    && isQuotedLiteral(value.substring(1), '\'');
        }

    }

}
