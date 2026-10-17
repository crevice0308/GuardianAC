/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

/** Last valid packet observation, not an authoritative world position. */
public record PacketMovement(long atMillis, double x, double y, double z, float yaw, float pitch,
                             boolean onGround, boolean hasPosition, boolean hasRotation,
                             boolean protocolTranslated) { }
