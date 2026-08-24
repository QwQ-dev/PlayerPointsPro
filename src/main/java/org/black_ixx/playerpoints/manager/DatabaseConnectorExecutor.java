package org.black_ixx.playerpoints.manager;

import dev.rosewood.rosegarden.database.DatabaseConnector;

import java.util.Objects;

final class DatabaseConnectorExecutor {

    private final DatabaseConnector connector;
    private final boolean serializeCallbacks;
    private final Object callbackLock;

    DatabaseConnectorExecutor(DatabaseConnector connector, boolean serializeCallbacks) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.serializeCallbacks = serializeCallbacks;
        this.callbackLock = new Object();
    }

    void connect(DatabaseConnector.ConnectionCallback callback) {
        this.connect(callback, true);
    }

    void connect(DatabaseConnector.ConnectionCallback callback, boolean useTransaction) {
        if (!this.serializeCallbacks) {
            this.connector.connect(callback, useTransaction);
            return;
        }

        synchronized (this.callbackLock) {
            this.connector.connect(callback, useTransaction);
        }
    }

}
