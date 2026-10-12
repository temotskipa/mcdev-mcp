package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class H2DatabasePromotion {
    private final DatabaseFileOperations files;

    H2DatabasePromotion(DatabaseFileOperations files) {
        this.files = files;
    }

    static void validate(Path file, DatabaseValidator validator) throws IOException {
        try {
            validator.validate(file);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Symbol index validation failed", exception);
        }
    }

    void resolveBackup(H2DatabaseArtifacts artifacts, DatabaseValidator validator) throws IOException {
        Path target = artifacts.target;
        Path backup = artifacts.backup;
        if (!Files.exists(backup)) {
            return;
        }
        if (!Files.exists(target)) {
            files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            validate(target, validator);
        } catch (IOException exception) {
            throw new IOException("Both the symbol index and its backup exist and the index is invalid; preserve and inspect " + target + " and " + backup, exception);
        }
        Files.delete(backup);
    }

    void promote(H2DatabaseArtifacts artifacts, DatabaseValidator validator) throws IOException {
        try {
            files.move(artifacts.temporary, artifacts.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            promoteWithBackup(artifacts, validator, exception);
        }
    }

    private void promoteWithBackup(H2DatabaseArtifacts artifacts, DatabaseValidator validator, AtomicMoveNotSupportedException atomicFailure) throws IOException {
        Path temporary = artifacts.temporary;
        Path target = artifacts.target;
        Path backup = artifacts.backup;
        boolean originalTargetExisted = Files.exists(target);
        DatabasePromotionPhase phase = null;
        try {
            Files.deleteIfExists(backup);
            if (originalTargetExisted) {
                phase = DatabasePromotionPhase.BACKING_UP_TARGET;
                files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            phase = DatabasePromotionPhase.PROMOTING_TEMPORARY;
            files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            validate(target, validator);
            if (Files.exists(backup)) {
                Files.deleteIfExists(backup);
            }
        } catch (IOException exception) {
            restorePrePromotionState(target, backup, phase, originalTargetExisted, exception);
            exception.addSuppressed(atomicFailure);
            throw exception;
        }
    }

    private void restorePrePromotionState(Path target, Path backup, DatabasePromotionPhase phase, boolean originalTargetExisted, IOException originalFailure) {
        boolean targetExists = Files.exists(target);
        boolean backupExists = Files.exists(backup);
        if (phase == DatabasePromotionPhase.BACKING_UP_TARGET) {
            if (targetExists) {
                return;
            }
            if (backupExists) {
                restoreBackup(target, backup, originalFailure);
                return;
            }
            originalFailure.addSuppressed(new IOException("Neither target nor backup remains after failed backup move: " + target + " and " + backup));
            return;
        }

        if (phase != DatabasePromotionPhase.PROMOTING_TEMPORARY) {
            return;
        }
        if (targetExists) {
            try {
                files.delete(target);
            } catch (IOException exception) {
                originalFailure.addSuppressed(new IOException("Unable to remove uncertain promoted target; preserving observed state for " + target + " and " + backup, exception));
                return;
            }
        }
        if (backupExists) {
            restoreBackup(target, backup, originalFailure);
        }
        else if (originalTargetExisted) {
            originalFailure.addSuppressed(new IOException("Backup missing after failed temporary promotion: " + backup));
        }
    }

    private void restoreBackup(Path target, Path backup, IOException originalFailure) {
        DatabasePromotionPhase phase = DatabasePromotionPhase.RESTORING_BACKUP;
        try {
            files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            boolean targetExists = Files.exists(target);
            boolean backupExists = Files.exists(backup);
            String state = "target=" + targetExists + ", backup=" + backupExists + ", phase=" + phase;
            originalFailure.addSuppressed(new IOException("Unable to restore backup; preserving observed state for " + target + " and " + backup + " (" + state + ")", exception));
        }
    }
}