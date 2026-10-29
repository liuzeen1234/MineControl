package dev.minecontrol.sdk;

/** A command reached the Mod, which rejected it. Transport failures use IOException. */
public final class CommandException extends RuntimeException {
    private final String code;
    public CommandException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }
    public String code() { return code; }
}
