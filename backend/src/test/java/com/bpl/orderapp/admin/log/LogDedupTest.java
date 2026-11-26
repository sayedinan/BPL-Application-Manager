package com.bpl.orderapp.admin.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogDedupTest {

    private static List<String> fresh(List<String> stored, List<String> snapshot) {
        return LogDedup.newLines(snapshot, stored);
    }

    @Test
    void everythingIsNewTheFirstTime() {
        assertThat(fresh(List.of(), List.of("a", "b"))).containsExactly("a", "b");
    }

    @Test
    void anUnchangedSnapshotAddsNothing() {
        assertThat(fresh(List.of("a", "b"), List.of("a", "b"))).isEmpty();
    }

    @Test
    void aSlidingWindowAddsOnlyTheTail() {
        assertThat(fresh(List.of("a", "b", "c"), List.of("b", "c", "d"))).containsExactly("d");
    }

    @Test
    void aWindowReachingFurtherBackThanWhatIsStoredStillMatches() {
        assertThat(fresh(List.of("c", "d"), List.of("a", "b", "c", "d", "e"))).containsExactly("e");
    }

    @Test
    void repeatedIdenticalLinesAreCounted() {
        assertThat(fresh(List.of("a", "x"), List.of("a", "x", "x"))).containsExactly("x");
        assertThat(fresh(List.of("a", "x"), List.of("a", "x", "b", "x"))).containsExactly("b", "x");
    }

    @Test
    void aRotatedLogIsTakenWhole() {
        assertThat(fresh(List.of("a", "b"), List.of("new1", "new2"))).containsExactly("new1", "new2");
    }

    @Test
    void theLastStoredLineIsStillUsedWhenEarlierHistoryDiffers() {
        // For example, a script that filters or edits older lines between runs.
        assertThat(fresh(List.of("a", "b"), List.of("changed", "b", "c"))).containsExactly("c");
    }

    @Test
    void largeIdenticalWindowsFinishQuickly() {
        List<String> same = Collections.nCopies(5000, "x");
        assertThat(fresh(Collections.nCopies(500, "x"), same)).isEmpty();
    }
}
