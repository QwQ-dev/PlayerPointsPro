package org.black_ixx.playerpoints.database.migrations;

import dev.rosewood.rosegarden.database.DataMigration;
import dev.rosewood.rosegarden.database.DatabaseConnector;
import dev.rosewood.rosegarden.database.SQLiteConnector;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class _3_Add_Table_Transaction_Log extends DataMigration {

    public _3_Add_Table_Transaction_Log() {
        super(3);
    }

    @Override
    public void migrate(DatabaseConnector connector, Connection connection, String tablePrefix) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        boolean sqlite = connector instanceof SQLiteConnector
                || productName.toLowerCase(Locale.ROOT).contains("sqlite");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "transaction_log (" +
                    "transaction_type VARCHAR(20) NOT NULL, " +
                    "description VARCHAR(100) NOT NULL, " +
                    "source VARCHAR(36) NULL, " +
                    "receiver VARCHAR(36) NOT NULL, " +
                    "amount INT NOT NULL, " +
                    "timestamp " + (sqlite ? "TEXT" : "TIMESTAMP") + " DEFAULT CURRENT_TIMESTAMP" +
                    ")");
        }
    }

}
