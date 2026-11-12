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
