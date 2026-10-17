// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

public enum BanType {
    UUID, IP, DUAL;
    public boolean appliesToUuid() { return this != IP; }
    public boolean appliesToIp() { return this != UUID; }
}
