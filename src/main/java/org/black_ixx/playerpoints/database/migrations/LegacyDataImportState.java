package org.black_ixx.playerpoints.database.migrations;

import java.io.File;
import java.util.Objects;

public final class LegacyDataImportState {

    private final File backupFile;
    private boolean migrationOneRan;
    private boolean imported;

    public LegacyDataImportState(File backupFile) {
        this.backupFile = Objects.requireNonNull(backupFile, "backupFile");
    }

    void markMigrationOneRan() {
        this.migrationOneRan = true;
    }

    boolean shouldImport() {
        return this.migrationOneRan && this.backupFile.isFile();
    }

    File getBackupFile() {
        return this.backupFile;
    }

    void markImported() {
        this.imported = true;
    }

    public boolean wasImported() {
        return this.imported;
    }

}
