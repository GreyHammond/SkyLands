/*
 * SkyLands (SkyRealms) - floating-island survival for Paper
 * Copyright (C) 2026 Hammond Digital Studios
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License version 3, together with the additional terms in the NOTICE
 * file (section 7: you must keep the attribution "SkyLands by Hammond Digital Studios" and mark
 * modified versions). This program is distributed WITHOUT ANY WARRANTY. See the LICENSE and
 * NOTICE files, or <https://www.gnu.org/licenses/>.
 */
package dev.skyrealms.island;

public enum Role {
    OWNER, COOWNER, FRIEND, VISITOR;

    public String pretty() {
        return switch (this) {
            case OWNER -> "Owner"; case COOWNER -> "Co-owners"; case FRIEND -> "Friends"; case VISITOR -> "Visitors";
        };
    }

    /** Owners and co-owners can manage the island and do everything on it. */
    public boolean manages() { return this == OWNER || this == COOWNER; }
}
