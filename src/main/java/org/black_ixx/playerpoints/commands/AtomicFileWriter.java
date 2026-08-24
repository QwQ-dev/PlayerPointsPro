package org.black_ixx.playerpoints.commands;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class AtomicFileWriter {

    private AtomicFileWriter() {

    }

    static void replace(File destination, FileProducer producer) throws IOException {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
            throw new IOException("Unable to create backup directory");

        File temporary = File.createTempFile(destination.getName() + ".", ".tmp", parent);
        boolean replaced = false;
        try {
            producer.write(temporary);
            if (!temporary.isFile())
                throw new IOException("Backup producer did not create a file");
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced)
                Files.deleteIfExists(temporary.toPath());
        }
    }

    @FunctionalInterface
    interface FileProducer {

        void write(File file) throws IOException;

    }

}
