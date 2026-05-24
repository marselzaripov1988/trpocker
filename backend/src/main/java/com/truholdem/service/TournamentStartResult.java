package com.truholdem.service;

import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentTable;

import java.util.List;

public record TournamentStartResult(
        Tournament tournament,
        int playerCount,
        int tableCount,
        List<TournamentTable> tables
) {
}
