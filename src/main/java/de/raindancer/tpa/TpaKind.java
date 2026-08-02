package de.raindancer.tpa;

/**
 * The two directions a teleport request can run in.
 *
 * <h2>Why one enum rather than two request types</h2>
 * Everything about a request is the same in both directions — who asked, who answers, when it expires,
 * what the warmup does — except the single question of who ends up moving. Keeping that as one field
 * means the registry, the menu and the expiry all handle both without a branch, and the branch that
 * remains is here, where it is one line and can be read.
 */
public enum TpaKind {

    /** {@code /tpa}: the player who asked travels to the player who answers. */
    TO("tpa", "wants to teleport to you", "to let you come to them"),

    /** {@code /tpahere}: the player who answers travels to the player who asked. */
    HERE("tpahere", "wants you to teleport to them", "to come to you");

    private final String command;
    private final String asked;
    private final String asking;

    TpaKind(String command, String asked, String asking) {
        this.command = command;
        this.asked = asked;
        this.asking = asking;
    }

    /** The command that starts a request of this kind, without the slash. */
    public String command() {
        return command;
    }

    /**
     * The whole clause the player being asked reads: "Steve <wants to teleport to you>."
     * <p>
     * A clause rather than a fragment, because the two directions do not share a sentence shape —
     * gluing "to come to them" onto "wants to teleport" produced "wants to teleport to come to them"
     * on a live server, which is the sort of thing only a real request in real chat shows you.
     */
    public String asked() {
        return asked;
    }

    /** How it reads to the player who asked: "Asked Steve <to come to you>." */
    public String asking() {
        return asking;
    }

    /** Whether the player who sent the request is the one who moves. */
    public boolean requesterTravels() {
        return this == TO;
    }
}
