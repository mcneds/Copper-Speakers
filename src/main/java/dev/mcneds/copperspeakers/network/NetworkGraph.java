package dev.mcneds.copperspeakers.network;

import java.util.*;
import java.util.function.*;

/** Bounded, deterministic traversal; readers must treat unloaded positions as absent. */
public final class NetworkGraph {
    private NetworkGraph() {}
    public record Component<P>(Set<P> nodes, Set<P> speakers, boolean overflow) {}
    public static <P> Component<P> find(P root, Function<P, ? extends Iterable<P>> neighbors,
            Predicate<P> present, Predicate<P> conductor, Predicate<P> speaker, int limit) {
        Set<P> visited = new LinkedHashSet<>();
        Set<P> speakers = new LinkedHashSet<>();
        ArrayDeque<P> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            P p = queue.removeFirst();
            if (visited.contains(p) || !present.test(p)) continue;
            if (visited.size() == limit) return new Component<>(Set.copyOf(visited), Set.of(), true);
            visited.add(p);
            if (speaker.test(p)) speakers.add(p);
            if (p.equals(root) || conductor.test(p)) for (P n : neighbors.apply(p)) queue.addLast(n);
        }
        return new Component<>(Collections.unmodifiableSet(visited), Collections.unmodifiableSet(speakers), false);
    }
}
