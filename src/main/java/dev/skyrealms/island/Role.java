package dev.skyrealms.island;

public enum Role {
    OWNER, FRIEND, VISITOR;

    public String pretty() {
        return switch (this) { case OWNER -> "Owner"; case FRIEND -> "Friends"; case VISITOR -> "Visitors"; };
    }
}
