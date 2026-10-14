package dev.guardianac.detection;

import java.util.UUID;

/** Distance is eye to target bounding box, after conservative latency compensation in the adapter. */
public record CombatSample(long tick, UUID target, double distance, double reachLimit,
                           double angleDegrees, boolean exempt) {}
