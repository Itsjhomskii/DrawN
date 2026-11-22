package drawn.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Failure-before-replacement and successful CSV replacement checks. */
public final class AtomicCsvFileTest {
    private AtomicCsvFileTest() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("drawn-atomic-csv-");
        Path file = directory.resolve("events.csv");
        Files.write(file, List.of("header", "original row"), StandardCharsets.UTF_8);

        boolean failed = false;
        try {
            AtomicCsvFile.write(file, List.of("header", "replacement row"), (temporary, destination) -> {
                assert Files.readAllLines(temporary, StandardCharsets.UTF_8)
                        .equals(List.of("header", "replacement row"));
                throw new IOException("injected replacement failure");
            });
        } catch (IOException expected) {
            failed = true;
        }
        assert failed : "injected replacement failure was not reported";
        assert Files.readAllLines(file, StandardCharsets.UTF_8).equals(List.of("header", "original row"))
                : "failed replacement changed the original file";
        try (var files = Files.list(directory)) {
            assert files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"))
                    : "temporary replacement file was not cleaned up";
        }

        AtomicCsvFile.write(file, List.of("header", "updated row"));
        assert Files.readAllLines(file, StandardCharsets.UTF_8).equals(List.of("header", "updated row"));
        System.out.println("AtomicCsvFileTest passed.");
    }
}
