package com.trafficflowx.model;

/**
 * The steps of the signal cycle. The intersection is always in exactly one phase,
 * and the colour of every light is decided by the phase. Both lights of an axis
 * always get the same colour, so conflicting greens cannot happen.
 *
 * Normal cycle:  NS_GREEN -> NS_YELLOW -> EW_GREEN -> EW_YELLOW -> NS_GREEN ...
 * Emergency:     NS_EMERGENCY / EW_EMERGENCY = that axis GREEN, held until the
 *                emergency vehicles have crossed. Afterwards next() continues with
 *                that axis' YELLOW, so the normal cycle resumes by itself.
 */
public enum SignalPhase {
    NS_GREEN("North/South GREEN"),
    NS_YELLOW("North/South YELLOW"),
    EW_GREEN("East/West GREEN"),
    EW_YELLOW("East/West YELLOW"),
    NS_EMERGENCY("EMERGENCY: North/South GREEN"),
    EW_EMERGENCY("EMERGENCY: East/West GREEN");

    private final String displayName;

    SignalPhase(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** What colour the light of the given direction shows in this phase. */
    public SignalState stateFor(Direction direction) {
        boolean northSouth = direction.isNorthSouthAxis();
        return switch (this) {
            case NS_GREEN, NS_EMERGENCY -> northSouth ? SignalState.GREEN : SignalState.RED;
            case NS_YELLOW -> northSouth ? SignalState.YELLOW : SignalState.RED;
            case EW_GREEN, EW_EMERGENCY -> northSouth ? SignalState.RED : SignalState.GREEN;
            case EW_YELLOW -> northSouth ? SignalState.RED : SignalState.YELLOW;
        };
    }

    /** The phase that normally follows this one. */
    public SignalPhase next() {
        return switch (this) {
            case NS_GREEN, NS_EMERGENCY -> NS_YELLOW;
            case NS_YELLOW -> EW_GREEN;
            case EW_GREEN, EW_EMERGENCY -> EW_YELLOW;
            case EW_YELLOW -> NS_GREEN;
        };
    }

    /** True for the two NORMAL green phases (emergency greens are separate). */
    public boolean isGreen() {
        return this == NS_GREEN || this == EW_GREEN;
    }

    public boolean isYellow() {
        return this == NS_YELLOW || this == EW_YELLOW;
    }

    public boolean isEmergency() {
        return this == NS_EMERGENCY || this == EW_EMERGENCY;
    }

    /** True if this phase belongs to the North/South axis. */
    public boolean isNorthSouthPhase() {
        return this == NS_GREEN || this == NS_YELLOW || this == NS_EMERGENCY;
    }

    public static SignalPhase emergencyFor(boolean northSouth) {
        return northSouth ? NS_EMERGENCY : EW_EMERGENCY;
    }
}