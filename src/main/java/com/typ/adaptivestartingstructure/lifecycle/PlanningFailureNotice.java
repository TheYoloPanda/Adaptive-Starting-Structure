package com.typ.adaptivestartingstructure.lifecycle;

import com.typ.adaptivestartingstructure.planner.BiomeConfigurationException;
import com.typ.adaptivestartingstructure.planner.CoarseRejectionReason;
import com.typ.adaptivestartingstructure.planner.FineRejectionReason;
import com.typ.adaptivestartingstructure.planner.GeneratorQueryBudgetExceededException;
import com.typ.adaptivestartingstructure.planner.SitePlanningDiagnostics;
import com.typ.adaptivestartingstructure.planner.SitePlanningException;
import com.typ.adaptivestartingstructure.planner.SpawnRejectionReason;
import com.typ.adaptivestartingstructure.structure.StructureLoadException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

record PlanningFailureNotice(
        PlanningFailurePhase phase,
        Component reason) {
    static final String MESSAGE_KEY =
            "message.adaptive_starting_structure.planning_failure";
    static final String PREFIX_KEY =
            "message.adaptive_starting_structure.prefix";

    private static final String REASON_PREFIX =
            "message.adaptive_starting_structure.reason.";
    private static final String REJECTION_PREFIX =
            "message.adaptive_starting_structure.rejection.";
    private static final String STAGE_PREFIX =
            "message.adaptive_starting_structure.stage.";
    private static final int MAX_RAW_REASON_LENGTH = 240;

    PlanningFailureNotice {
        Objects.requireNonNull(phase, "phase");
        reason = Objects.requireNonNull(reason, "reason").copy();
    }

    static PlanningFailureNotice from(
            PlanningFailurePhase phase,
            Exception failure) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(failure, "failure");

        if (phase == PlanningFailurePhase.CONFIGURATION) {
            return new PlanningFailureNotice(
                    phase,
                    Component.translatable(
                            REASON_PREFIX + "configuration",
                            safeMessage(failure)));
        }
        if (failure instanceof StructureLoadException structureFailure) {
            return new PlanningFailureNotice(
                    phase,
                    structureReason(structureFailure));
        }
        if (failure instanceof SitePlanningException planningFailure) {
            return new PlanningFailureNotice(
                    phase,
                    siteReason(planningFailure.diagnostics()));
        }
        if (failure instanceof GeneratorQueryBudgetExceededException budgetFailure) {
            return new PlanningFailureNotice(
                    phase,
                    queryBudgetReason(
                            budgetFailure.queriesUsed(),
                            budgetFailure.maximumQueries()));
        }
        if (failure instanceof BiomeConfigurationException) {
            return new PlanningFailureNotice(
                    phase,
                    biomeConfigurationReason(safeMessage(failure)));
        }
        if (failure instanceof IOException) {
            return new PlanningFailureNotice(
                    phase,
                    Component.translatable(
                            REASON_PREFIX + "structure_pool",
                            safeMessage(failure)));
        }
        return new PlanningFailureNotice(
                phase,
                Component.translatable(
                        REASON_PREFIX + "unexpected",
                        failure.getClass().getSimpleName(),
                        safeMessage(failure)));
    }

    Component message() {
        Component prefix = Component.translatable(PREFIX_KEY)
                .withStyle(ChatFormatting.GOLD);
        Component styledReason = reason.copy()
                .withStyle(ChatFormatting.WHITE);
        return Component.translatable(
                        MESSAGE_KEY,
                        prefix,
                        styledReason)
                .withStyle(ChatFormatting.YELLOW);
    }

    private static Component structureReason(
            StructureLoadException failure) {
        String fileName = fileName(failure.sourcePath());
        String detail = structureDetail(failure, fileName);
        return structureReason(fileName, detail);
    }

    static Component structureReason(
            String fileName,
            String detail) {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(detail, "detail");
        return Component.translatable(
                REASON_PREFIX + "invalid_structure",
                fileName,
                detail);
    }

    static Component siteReason(SitePlanningDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        DominantRejection rejection = dominant(
                "spawn",
                SpawnRejectionReason.values(),
                diagnostics.spawnRejectionCounts(),
                diagnostics.spawnValidationCount());
        if (rejection == null) {
            rejection = dominant(
                    "fine",
                    FineRejectionReason.values(),
                    diagnostics.fineRejectionCounts(),
                    diagnostics.fineEvaluationCount());
        }
        if (rejection == null) {
            rejection = dominant(
                    "coarse",
                    CoarseRejectionReason.values(),
                    diagnostics.coarseRejectionCounts(),
                    diagnostics.coarseEvaluationCount());
        }
        if (rejection == null) {
            return Component.translatable(REASON_PREFIX + "no_site");
        }
        return Component.translatable(
                REASON_PREFIX + "no_site_dominant",
                Component.translatable(STAGE_PREFIX + rejection.stage()),
                Component.translatable(rejectionKey(rejection.reason())),
                rejection.count(),
                rejection.total());
    }

    static Component queryBudgetReason(
            int queriesUsed,
            int maximumQueries) {
        return Component.translatable(
                REASON_PREFIX + "query_budget",
                queriesUsed,
                maximumQueries);
    }

    static Component biomeConfigurationReason(String detail) {
        return Component.translatable(
                REASON_PREFIX + "biome_configuration",
                Objects.requireNonNull(detail, "detail"));
    }

    private static <E extends Enum<E>> DominantRejection dominant(
            String stage,
            E[] orderedReasons,
            Map<E, Integer> counts,
            int total) {
        E selected = null;
        int selectedCount = 0;
        for (E reason : orderedReasons) {
            int count = counts.getOrDefault(reason, 0);
            if (count > selectedCount) {
                selected = reason;
                selectedCount = count;
            }
        }
        return selected == null
                ? null
                : new DominantRejection(
                        stage,
                        selected,
                        selectedCount,
                        total);
    }

    private static String rejectionKey(Enum<?> reason) {
        return REJECTION_PREFIX
                + reason.name().toLowerCase(Locale.ROOT);
    }

    private static String structureDetail(
            StructureLoadException failure,
            String fileName) {
        return structureDetail(
                failure.getMessage(),
                failure.sourcePath(),
                fileName);
    }

    static String structureDetail(
            String message,
            Path sourcePath,
            String fileName) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(fileName, "fileName");
        String detail = message == null || message.isBlank()
                ? StructureLoadException.class.getSimpleName()
                : normalize(message);
        String encodedPath = sourcePath.toString();
        detail = detail.replace(" in " + encodedPath, "");
        detail = detail.replace(": " + encodedPath, "");
        detail = detail.replace(encodedPath, fileName);
        return limit(detail);
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return limit(normalize(message));
    }

    private static String normalize(String message) {
        return message.replaceAll("\\s+", " ").trim();
    }

    private static String limit(String message) {
        if (message.length() <= MAX_RAW_REASON_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_RAW_REASON_LENGTH - 3) + "...";
    }

    private record DominantRejection(
            String stage,
            Enum<?> reason,
            int count,
            int total) {
    }
}
