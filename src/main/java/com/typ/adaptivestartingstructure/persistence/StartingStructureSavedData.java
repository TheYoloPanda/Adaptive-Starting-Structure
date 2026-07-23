package com.typ.adaptivestartingstructure.persistence;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

public final class StartingStructureSavedData extends SavedData {
    public static final String DATA_NAME = "adaptive_starting_structure";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final SavedData.Factory<StartingStructureSavedData> FACTORY =
            new SavedData.Factory<>(
                    StartingStructureSavedData::rejectImplicitCreation,
                    StartingStructureSavedData::load);

    private static final String SCHEMA_VERSION = "SchemaVersion";
    private static final String STATE = "State";
    private static final String PLAN = "Plan";
    private static final String LAST_ERROR = "LastError";

    private StartingStructurePlan plan;
    private State state;
    private String lastError;

    private StartingStructureSavedData(
            StartingStructurePlan plan,
            State state,
            String lastError,
            boolean dirty) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.state = Objects.requireNonNull(state, "state");
        this.lastError = validateError(state, lastError);
        setDirty(dirty);
    }

    public static StartingStructureSavedData planned(
            StartingStructurePlan plan) {
        return new StartingStructureSavedData(
                plan,
                State.PLANNED,
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

    public void markPlacing() {
        transition(State.PLACING, null);
    }

    public void markComplete() {
        transition(State.COMPLETE, null);
    }

    public void markFailed(String error) {
        transition(State.FAILED, error);
    }

    public void advanceCandidate() {
        if (state != State.PLANNED) {
            throw new IllegalStateException(
                    "Candidate retry requires PLANNED state");
        }
        plan = plan.advanceCandidate();
        setDirty();
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
        return tag;
    }

    public static StartingStructureSavedData load(
            CompoundTag tag,
            HolderLookup.Provider registries) {
        requireType(tag, SCHEMA_VERSION, Tag.TAG_INT);
        int schemaVersion = tag.getInt(SCHEMA_VERSION);
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new StartingStructureDataException(
                    "Unsupported starting-structure schema version "
                            + schemaVersion
                            + "; expected "
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
        try {
            return new StartingStructureSavedData(
                    plan,
                    state,
                    lastError,
                    false);
        } catch (IllegalArgumentException exception) {
            throw new StartingStructureDataException(
                    "Invalid persisted starting-structure state: "
                            + exception.getMessage(),
                    exception);
        }
    }

    private void transition(State target, String error) {
        Objects.requireNonNull(target, "target");
        boolean allowed = switch (state) {
            case PLANNED ->
                    target == State.PLACING || target == State.FAILED;
            case PLACING ->
                    target == State.COMPLETE || target == State.FAILED;
            case COMPLETE, FAILED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                    "Invalid starting-structure state transition from "
                            + state
                            + " to "
                            + target);
        }
        String validatedError = validateError(target, error);
        state = target;
        lastError = validatedError;
        setDirty();
    }

    private static String validateError(State state, String error) {
        if (state == State.FAILED) {
            if (error == null || error.isBlank()) {
                throw new IllegalArgumentException(
                        "FAILED state requires a non-blank last error");
            }
            return error;
        }
        if (error != null) {
            throw new IllegalArgumentException(
                    state + " state must not contain a last error");
        }
        return null;
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
        FAILED
    }
}
