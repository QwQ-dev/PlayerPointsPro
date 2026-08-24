package org.black_ixx.playerpoints.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class MigrationStateVerifier {

    private MigrationStateVerifier() {

    }

    public static void requireAtLeast(Connection connection, String tableName,
                                      int requiredRevision) throws SQLException {
        String query = "SELECT migration_version FROM " + tableName;
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            if (!result.next())
                throw new SQLException("Migration table is missing its version row");

            int actualRevision = result.getInt(1);
            if (result.next())
                throw new SQLException("Migration table contains more than one version row");
            if (actualRevision < requiredRevision) {
                throw new SQLException("Database migration revision " + actualRevision
                        + " is older than required revision " + requiredRevision);
            }
        }
    }

}
