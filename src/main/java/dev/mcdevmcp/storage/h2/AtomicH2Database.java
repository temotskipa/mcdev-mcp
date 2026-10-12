package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class AtomicH2Database {
    public static final Duration WRITE_LOCK_TIMEOUT = Duration.ofSeconds(30);
    private final H2DatabasePromotion promotion;

    public AtomicH2Database() {
        this(Files::move);
    }

    AtomicH2Database(DatabaseFileOperations files) {
        promotion = new H2DatabasePromotion(Objects.requireNonNull(files, "files"));
    }

    public <T> T rebuild(Path target, Duration lockTimeout, DatabaseBuilder<T> builder, DatabaseValidator validator) throws IOException {
        return rebuild(target, lockTimeout, builder, validator, validator);
    }

    public <T> T rebuild(Path target, Duration lockTimeout, DatabaseBuilder<T> builder, DatabaseValidator existingTargetValidator, DatabaseValidator candidateValidator) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(existingTargetValidator, "existingTargetValidator");
        Objects.requireNonNull(candidateValidator, "candidateValidator");
        H2DatabaseArtifacts artifacts = new H2DatabaseArtifacts(target);
        artifacts.createTargetDirectory();
        try (var databaseLock = DatabaseLock.write(artifacts.target, lockTimeout)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock");
            }
            promotion.resolveBackup(artifacts, existingTargetValidator);
            artifacts.deleteTemporary();
            try {
                T result = builder.build(artifacts.temporary);
                H2DatabasePromotion.validate(artifacts.temporary, candidateValidator);
                promotion.promote(artifacts, candidateValidator);
                return result;
            } catch (Exception exception) {
                try {
                    artifacts.deleteTemporary();
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Symbol index rebuild failed", exception);
            }
        }
    }
}
