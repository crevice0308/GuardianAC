package dev.guardianac.detection;

/** Distances over exactly one server tick. The adapter supplies the legitimate movement envelope. */
public record MovementSample(long tick, double horizontal, double vertical, boolean onGround,
                             boolean nearSupport, boolean exempt, double horizontalLimit) {}
