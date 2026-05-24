package com.truholdem.dto;

import com.truholdem.model.TournamentRegistration;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.stream.IntStream;

public record TournamentRegistrationPageResponse(
        List<LeaderboardEntryDto> entries,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static TournamentRegistrationPageResponse from(Page<TournamentRegistration> page) {
        int rankBase = page.getNumber() * page.getSize();
        List<LeaderboardEntryDto> entries = IntStream.range(0, page.getContent().size())
                .mapToObj(i -> LeaderboardEntryDto.from(
                        page.getContent().get(i),
                        rankBase + i + 1))
                .toList();
        return new TournamentRegistrationPageResponse(
                entries,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
