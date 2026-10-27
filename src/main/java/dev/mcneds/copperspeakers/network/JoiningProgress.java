package dev.mcneds.copperspeakers.network;

/** The displayed countdown uses the same deadline as admission, including reschedules. */
final class JoiningProgress {
    private String last;
    private long second = Long.MIN_VALUE;
    String update(long now, Long due) {
        String message = due == null ? "Loading music…"
                : "Joining music in " + Math.max(0, (long)Math.ceil((due - now) / 1_000_000_000.0)) + "s…";
        long current = Math.floorDiv(now, 1_000_000_000L);
        if (message.equals(last) && (due != null || current == second)) return null;
        last = message;
        second = current;
        return message;
    }
}
