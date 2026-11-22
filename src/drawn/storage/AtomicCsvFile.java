package drawn.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Writes a complete UTF-8 CSV replacement beside the original before replacing it. */
final class AtomicCsvFile {
    @FunctionalInterface
    interface Replacer { void replace(Path temporary, Path destination) throws IOException; }

    private AtomicCsvFile() { }

    static void write(Path destination, List<String> rows) throws IOException {
        write(destination, rows, AtomicCsvFile::replace);
    }

    static void write(Path destination, List<String> rows, Replacer replacer) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("CSV destination has no parent directory: " + destination);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, destination.getFileName().toString(), ".tmp");
        try {
            byte[] bytes = (String.join(System.lineSeparator(), rows) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                replacer.replace(temporary, destination);
            } catch (AtomicMoveNotSupportedException unsupported) {
                try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException replacementFailure) {
                    replacementFailure.addSuppressed(unsupported);
                    throw replacementFailure;
                }
            }
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static void replace(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException replacementFailure) {
                replacementFailure.addSuppressed(unsupported);
                throw replacementFailure;
            }
        }
    }
}
