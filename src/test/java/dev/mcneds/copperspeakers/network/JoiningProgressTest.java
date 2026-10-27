package dev.mcneds.copperspeakers.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JoiningProgressTest {
    @Test void loadingCountdownRescheduleAndRefreshUseAdmissionDeadline() {
        var progress = new JoiningProgress();
        assertEquals("Loading music…", progress.update(0, null));
        assertNull(progress.update(50_000_000, null));
        assertEquals("Joining music in 12s…", progress.update(1_000_000_000, 13_000_000_000L));
        assertNull(progress.update(1_050_000_000, 13_000_000_000L));
        assertEquals("Joining music in 11s…", progress.update(2_000_000_000L, 13_000_000_000L));
        assertEquals("Joining music in 26s…", progress.update(2_050_000_000L, 28_000_000_000L));
        assertEquals("Loading music…", progress.update(2_100_000_000L, null));
        assertEquals("Loading music…", progress.update(3_000_000_000L, null));
    }
}
