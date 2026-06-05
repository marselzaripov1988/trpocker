package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.dto.TournamentRescheduleResult;
import com.truholdem.model.Tournament;
import com.truholdem.model.User;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.UserRepository;
import com.truholdem.service.EmailService;
import com.truholdem.service.TournamentService;
import com.truholdem.service.notification.TournamentNotificationService;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Admin reschedule of an under-filled tournament + registrant e-mail notification")
class TournamentRescheduleIT {

    @Autowired private TournamentService tournamentService;
    @Autowired private TournamentNotificationService notificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private TournamentRegistrationRepository registrationRepository;
    @Autowired private TournamentRepository tournamentRepository;

    @MockitoBean private EmailService emailService;

    private UUID tournamentId;
    private Instant originalSlot;

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        tournamentRepository.deleteAll();
        // freezeout(max=4) → minPlayers = 4 (the required head-count for a non-full-required tournament).
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.freezeout("Reschedule " + System.currentTimeMillis(), 0, 4));
        tournamentId = t.getId();
        originalSlot = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        tournamentService.scheduleStart(tournamentId, originalSlot);
    }

    private User realUser(String base) {
        String unique = base + "-" + UUID.randomUUID();
        User user = userRepository.save(new User(unique, unique + "@example.com", "x"));
        tournamentService.registerPlayer(tournamentId, user.getId(), unique);
        return user;
    }

    @Test
    @DisplayName("under-filled: start is postponed and only the registered real users are e-mailed")
    void underfilledReschedulesAndNotifiesUsers() {
        User alice = realUser("alice");
        User bob = realUser("bob");
        // A bot registrant (no owning user) must be skipped by the notifier.
        tournamentService.registerPlayer(tournamentId, UUID.randomUUID(), "Bot_1");

        Instant newSlot = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        TournamentRescheduleResult result = tournamentService.rescheduleIfUnderfilled(tournamentId, newSlot);

        assertThat(result.previousStart()).isEqualTo(originalSlot);
        assertThat(result.newStart()).isEqualTo(newSlot);
        assertThat(tournamentRepository.findById(tournamentId).orElseThrow().getScheduledStart())
                .isEqualTo(newSlot);

        int notified = notificationService.notifyRescheduled(result);

        assertThat(notified).isEqualTo(2);
        verify(emailService).sendTournamentRescheduledEmail(
                eq(alice.getEmail()), eq(alice.getUsername()), any(), any(), any());
        verify(emailService).sendTournamentRescheduledEmail(
                eq(bob.getEmail()), eq(bob.getUsername()), any(), any(), any());
        verify(emailService, times(2)).sendTournamentRescheduledEmail(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("required head-count already reached → rejected (409 IllegalState), no e-mails")
    void enoughPlayersRejectsReschedule() {
        realUser("p1");
        realUser("p2");
        tournamentService.registerPlayer(tournamentId, UUID.randomUUID(), "Bot_1");
        tournamentService.registerPlayer(tournamentId, UUID.randomUUID(), "Bot_2"); // 4/4 → full

        assertThatThrownBy(() -> tournamentService.rescheduleIfUnderfilled(
                tournamentId, Instant.now().plus(1, ChronoUnit.DAYS)))
                .isInstanceOf(IllegalStateException.class);
        verify(emailService, never()).sendTournamentRescheduledEmail(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a non-future start time is rejected (400 IllegalArgument)")
    void pastStartRejected() {
        realUser("alice");
        assertThatThrownBy(() -> tournamentService.rescheduleIfUnderfilled(
                tournamentId, Instant.now().minus(1, ChronoUnit.HOURS)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
