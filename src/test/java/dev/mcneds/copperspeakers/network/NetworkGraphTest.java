package dev.mcneds.copperspeakers.network;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkGraphTest {
    @Test void cyclesTerminateAndEndpointsDoNotBridgeNetworks() {
        Map<Integer, List<Integer>> graph = Map.of(0, List.of(1), 1, List.of(0, 2, 3),
                2, List.of(1, 3), 3, List.of(1, 2, 4), 4, List.of(3, 5), 5, List.of(4));
        var c = NetworkGraph.find(0, graph::get, graph::containsKey, n -> n > 0 && n < 4, n -> n == 4 || n == 5, 20);
        assertEquals(Set.of(0, 1, 2, 3, 4), c.nodes());
        assertEquals(Set.of(4), c.speakers());
        assertFalse(c.overflow());
    }
    @Test void unloadedNodesAreBoundaries() {
        var c = NetworkGraph.find(0, n -> List.of(n - 1, n + 1), n -> n >= 0 && n != 3,
                n -> true, n -> n == 4, 10);
        assertEquals(Set.of(0, 1, 2), c.nodes());
        assertTrue(c.speakers().isEmpty());
    }
    @Test void oversizedNetworkFailsClosedWithoutPartialSpeakerSelection() {
        var c = NetworkGraph.find(0, n -> List.of(n + 1), n -> true, n -> true, n -> n % 2 == 0, 4096);
        assertTrue(c.overflow());
        assertEquals(4096, c.nodes().size());
        assertTrue(c.speakers().isEmpty());
    }
    @Test void cableLengthDoesNotAddTemporalState() {
        var c = NetworkGraph.find(0, n -> List.of(n + 1), n -> n <= 1000, n -> n < 1000, n -> n == 1000, 4096);
        assertEquals(Set.of(1000), c.speakers());
        assertEquals(1001, c.nodes().size());
    }
}
