package com.unecroe.ucjackpot.jackpot;

import com.unecroe.ucjackpot.config.JackpotDefinition;
import com.unecroe.ucjackpot.config.JackpotMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JackpotRoundTest {
    @Test
    void countdownWaitsForMinimumParticipants() {
        JackpotRound round = new JackpotRound(definition());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        round.addEntry(entry(first, "first", 100.0));

        assertFalse(round.countdownStarted());
        assertEquals(60, round.secondsLeft());

        round.addEntry(entry(second, "second", 100.0));

        assertTrue(round.countdownStarted());
        assertTrue(round.secondsLeft() > 0);
    }

    @Test
    void reclaimingBeforeCountdownResetsRoundToWaiting() {
        JackpotRound round = new JackpotRound(definition());
        UUID player = UUID.randomUUID();
        round.addEntry(entry(player, "player", 100.0));

        List<JackpotEntry> removed = round.removeEntries(player);

        assertEquals(1, removed.size());
        assertFalse(round.countdownStarted());
        assertEquals(0, round.entries().size());
        assertEquals(60, round.secondsLeft());
    }

    private JackpotEntry entry(UUID playerUuid, String playerName, double amount) {
        return new JackpotEntry(UUID.randomUUID(), "default", playerUuid, playerName,
                EntryType.MONEY, amount, amount, null, System.currentTimeMillis());
    }

    private JackpotDefinition definition() {
        return new JackpotDefinition(
                "default",
                true,
                "Default",
                JackpotMode.HYBRID,
                true,
                1.0,
                1000.0,
                100.0,
                List.of(100.0),
                0.0,
                true,
                1.0,
                1,
                9,
                true,
                true,
                Map.of(),
                60,
                2,
                1.0,
                25,
                0,
                true,
                10,
                false,
                24,
                1.0,
                false,
                0.0,
                0.0,
                "season-1",
                List.of(),
                true,
                false,
                0,
                0.0,
                List.of(),
                false,
                0.0,
                List.of(),
                true,
                "PAPER",
                "Jackpot Ticket",
                100.0,
                true,
                Set.of(),
                Set.of()
        );
    }
}


