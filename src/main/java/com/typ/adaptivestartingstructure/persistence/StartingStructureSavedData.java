package com.typ.adaptivestartingstructure.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

public final class StartingStructureSavedData extends SavedData {
    public static final String DATA_NAME = "adaptive_starting_structure";
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final SavedData.Factory<StartingStructureSavedData> FACTORY =
            new SavedData.Factory<>(
                    StartingStructureSavedData::rejectImplicitCreation,
                    StartingStructureSavedData::load);

    private static final String SCHEMA_VERSION = "SchemaVersion";
    private static final String STATE = "State";
    private static final String PLAN = "Plan";
    private static final String LAST_ERROR = "LastError";
    private static final String FALLBACK = "Fallback";
    private static final String FALLBACK_SPAWN = "VanillaSpawn";
    private static final String GENERATE_BONUS_CHEST =
            "GenerateBonusChest";

    private StartingStructurePlan plan;
    private State state;
    private String lastError;
    private FallbackDecision fallbackDecision;

    private StartingStructureSavedData(
            StartingStructurePlan plan,
            State state,
            String lastError,
            FallbackDecision fallbackDecision,
            boolean dirty) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.state = Objects.requireNonNull(state, "state");
        this.lastError = validateError(
                state,
                lastError,
                fallbackDecision);
        this.fallbackDecision = validateFallback(
                state,
                fallbackDecision,
                this.lastError);
        setDirty(dirty);
    }

    public static StartingStructureSavedData planned(
            StartingStructurePlan plan) {
        return new StartingStructureSavedData(
                plan,
                State.PLANNED,
                null,
                null,
                true);
    }

    public StartingStructurePlan plan() {
        return plan;
    }

    public State state() {
        return state;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    public Optional<FallbackDecision> fallbackDecision() {
        return Optional.ofNullable(fallbackDecision);
    }

    public void markPlacing() {
        transition(State.PLACING, null, null);
    }

    public void markComplete() {
        transition(State.COMPLETE, null, null);
    }

    public void markFailed(String error) {
        transition(State.FAILED, error, null);
    }

    public void markAwaitingDecision(
            FallbackDecision decision) {
        Objects.requireNonNull(decision, "decision");
        transition(
                State.AWAITING_DECISION,
                decision.originalError(),
                decision);
    }

    public void markFallbackApplying() {
        transition(
                State.FALLBACK_APPLYING,
                lastError,
                fallbackDecision);
    }

    public void markSkipped() {
        transition(
                State.SKIPPED,
                lastError,
                fallbackDecision);
    }

    public void advanceCandidate() {
        if (state != State.PLANNED) {
            throw new IllegalStateException(
                    "Candidate retry requires PLANNED state");
        }
        plan = plan.advanceCandidate();
        setDirty();
    }

    public OptionalInt advancePastCurrentSite() {
        if (state != State.PLANNED) {
            throw new IllegalStateException(
                    "Site retry requires PLANNED state");
        }
        Optional<StartingStructurePlan.SiteAdvance> advance =
                plan.advancePastCurrentSite();
        if (advance.isEmpty()) {
            return OptionalInt.empty();
        }
        StartingStructurePlan.SiteAdvance result =
                advance.orElseThrow();
        plan = result.plan();
        setDirty();
        return OptionalInt.of(result.discardedCandidates());
    }

    @Override
    public CompoundTag save(
            CompoundTag tag,
            HolderLookup.Provider registries) {
        tag.putInt(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
        tag.putString(STATE, state.name());
        tag.put(PLAN, StartingStructurePlanCodec.encode(plan));
        if (lastError != null) {
            tag.putString(LAST_ERROR, lastError);
        }
        if (fallbackDecision != null) {
            CompoundTag fallback = new CompoundTag();
            fallback.put(
                    FALLBACK_SPAWN,
                    NbtUtils.writeBlockPos(
                            fallbackDecision.vanillaSpawn()));
            fallback.putBoolean(
                    GENERATE_BONUS_CHEST,
                    fallbackDecision.generateBonusChest());
            tag.put(FALLBACK, fallback);
        }
        return tag;
    }

    CompoundTag saveAwaitingDecision(
            CompoundTag tag,
            HolderLookup.Provider registries,
            FallbackDecision decision) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(decision, "decision");
        if (state != State.PLANNED) {
            throw new IllegalStateException(
                    "Awaiting-decision persistence requires PLANNED state");
        }
        StartingStructureSavedData next =
                new StartingStructureSavedData(
                        plan,
                        State.AWAITING_DECISION,
                        decision.originalError(),
                        decision,
                        false);
        return next.save(tag, registries);
    }

    public static StartingStructureSavedData load(
            CompoundTag tag,
            HolderLookup.Provider registries) {
        requireType(tag, SCHEMA_VERSION, Tag.TAG_INT);
        int schemaVersion = tag.getInt(SCHEMA_VERSION);
        if (schemaVersion < 1
                || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new StartingStructureDataException(
                    "Unsupported starting-structure schema version "
                            + schemaVersion
                            + "; expected 1 through "
                            + CURRENT_SCHEMA_VERSION);
        }

        requireType(tag, STATE, Tag.TAG_STRING);
        State state;
        try {
            state = State.valueOf(tag.getString(STATE));
        } catch (IllegalArgumentException exception) {
            throw new StartingStructureDataException(
                    "Unknown starting-structure state '"
                            + tag.getString(STATE)
                            + "'",
                    exception);
        }

        requireType(tag, PLAN, Tag.TAG_COMPOUND);
        StartingStructurePlan plan =
                StartingStructurePlanCodec.decode(tag.getCompound(PLAN));
        String lastError = null;
        if (tag.contains(LAST_ERROR)) {
            requireType(tag, LAST_ERROR, Tag.TAG_STRING);
            lastError = tag.getString(LAST_ERROR);
        }
        FallbackDecision fallbackDecision = null;
        if (schemaVersion == 1) {
            if (state == State.AWAITING_DECISION
                    || state == State.FALLBACK_APPLYING
                    || state == State.SKIPPED) {
                throw new StartingStructureDataException(
                        "Schema 1 cannot contain fallback state "
                                + state);
            }
            if (tag.contains(FALLBACK)) {
                throw new StartingStructureDataException(
                        "Schema 1 must not contain fallback data");
            }
        } else if (tag.contains(FALLBACK)) {
            requireType(tag, FALLBACK, Tag.TAG_COMPOUND);
            CompoundTag fallback = tag.getCompound(FALLBACK);
            requireType(
                    fallback,
                    FALLBACK_SPAWN,
                    Tag.TAG_INT_ARRAY);
            requireType(
                    fallback,
                    GENERATE_BONUS_CHEST,
                    Tag.TAG_BYTE);
            var spawn = NbtUtils.readBlockPos(
                            fallback,
                            FALLBACK_SPAWN)
                    .orElseThrow(() ->
                            new StartingStructureDataException(
                                    "Persisted fallback spawn must contain exactly three coordinates"));
            try {
                fallbackDecision = new FallbackDecision(
                        spawn,
                        fallback.getBoolean(
                                GENERATE_BONUS_CHEST),
                        lastError);
            } catch (IllegalArgumentException
                    | NullPointerException exception) {
                throw new StartingStructureDataException(
                        "Invalid persisted fallback decision: "
                                + exception.getMessage(),
                        exception);
            }
        }
        try {
            return new StartingStructureSavedData(
                    plan,
                    state,
                    lastError,
                    fallbackDecision,
                    false);
        } catch (IllegalArgumentException exception) {
            throw new StartingStructureDataException(
                    "Invalid persisted starting-structure state: "
                            + exception.getMessage(),
                    exception);
        }
    }

    private void transition(
            State target,
            String error,
            FallbackDecision decision) {
        Objects.requireNonNull(target, "target");
        boolean allowed = switch (state) {
            case PLANNED ->
                    target == State.PLACING
                            || target == State.FAILED
                            || target == State.AWAITING_DECISION;
            case PLACING ->
                    target == State.COMPLETE || target == State.FAILED;
            case AWAITING_DECISION ->
                    target == State.FALLBACK_APPLYING;
            case FALLBACK_APPLYING ->
                    target == State.SKIPPED;
            case COMPLETE, FAILED, SKIPPED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                    "Invalid starting-structure state transition from "
                            + state
                            + " to "
                            + target);
        }
        String validatedError = validateError(
                target,
                error,
                decision);
        FallbackDecision validatedFallback =
                validateFallback(
                        target,
                        decision,
                        validatedError);
        state = target;
        lastError = validatedError;
        fallbackDecision = validatedFallback;
        setDirty();
    }

    private static String validateError(
            State state,
            String error,
            FallbackDecision decision) {
        if (state == State.FAILED
                || state == State.AWAITING_DECISION
                || state == State.FALLBACK_APPLYING
                || state == State.SKIPPED) {
            if (error == null || error.isBlank()) {
                throw new IllegalArgumentException(
                        state
                                + " state requires a non-blank last error");
            }
            return error;
        }
        if (error != null) {
            throw new IllegalArgumentException(
                    state + " state must not contain a last error");
        }
        return null;
    }

    private static FallbackDecision validateFallback(
            State state,
            FallbackDecision decision,
            String error) {
        boolean requiresFallback =
                state == State.AWAITING_DECISION
                        || state == State.FALLBACK_APPLYING
                        || state == State.SKIPPED;
        if (!requiresFallback) {
            if (decision != null) {
                throw new IllegalArgumentException(
                        state
                                + " state must not contain fallback data");
            }
            return null;
        }
        if (decision == null) {
            throw new IllegalArgumentException(
                    state + " state requires fallback data");
        }
        if (!decision.originalError().equals(error)) {
            throw new IllegalArgumentException(
                    state
                            + " state fallback error must match the last error");
        }
        return decision;
    }

    private static void requireType(
            CompoundTag tag,
            String key,
            int type) {
        if (!tag.contains(key)) {
            throw new StartingStructureDataException(
                    "Missing persisted starting-structure field " + key);
        }
        if (!tag.contains(key, type)) {
            throw new StartingStructureDataException(
                    "Persisted starting-structure field "
                            + key
                            + " has the wrong NBT type");
        }
    }

    private static StartingStructureSavedData rejectImplicitCreation() {
        throw new IllegalStateException(
                "StartingStructureSavedData must be created with a completed plan");
    }

    public enum State {
        PLANNED,
        PLACING,
        COMPLETE,
        FAILED,
        AWAITING_DECISION,
        FALLBACK_APPLYING,
        SKIPPED
    }
}
