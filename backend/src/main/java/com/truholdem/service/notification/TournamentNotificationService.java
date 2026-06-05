package com.truholdem.service.notification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.dto.TournamentRescheduleResult;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.model.User;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.UserRepository;
import com.truholdem.service.EmailService;

/**
 * Sends out-of-band notifications to a tournament's registrants. Today this is e-mail only (the
 * {@link EmailService} infrastructure already exists and is flag-gated by {@code app.mail.enabled}). A
 * registrant's e-mail is resolved by treating the registration's {@code playerId} as the owning
 * {@link User} id — which it is for real-money entrants (the wallet bridge registers them by user id).
 * Registrations with no matching user (e.g. bots / play-money randoms) or no e-mail are skipped silently.
 *
 * <p>SMS is intentionally not implemented yet: the {@code users} table has no phone column and no SMS
 * gateway is wired (would need a provider dependency + a schema change) — see TODO.
 */
@Service
public class TournamentNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TournamentNotificationService.class);
    private static final DateTimeFormatter SLOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final TournamentRegistrationRepository registrationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public TournamentNotificationService(TournamentRegistrationRepository registrationRepository,
            UserRepository userRepository, EmailService emailService) {
        this.registrationRepository = registrationRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * E-mail every resolvable registrant that the tournament's start has been postponed. Best-effort: a
     * registrant with no owning user / no e-mail is skipped. Returns the number of registrants e-mailed.
     */
    @Transactional(readOnly = true)
    public int notifyRescheduled(TournamentRescheduleResult result) {
        String previous = format(result.previousStart());
        String next = format(result.newStart());
        int notified = 0;
        for (TournamentRegistration reg : registrationRepository.findByTournamentId(result.tournamentId())) {
            User user = userRepository.findById(reg.getPlayerId()).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            emailService.sendTournamentRescheduledEmail(
                    user.getEmail(), user.getUsername(), result.tournamentName(), previous, next);
            notified++;
        }
        log.info("Tournament {} reschedule — notified {} registrant(s) by e-mail",
                result.tournamentId(), notified);
        return notified;
    }

    /**
     * E-mail every resolvable finalist (one shard winner each) that a federated pyramid's final has been
     * scheduled. Same resolution + best-effort rules as {@link #notifyRescheduled}. Returns the count e-mailed.
     */
    @Transactional(readOnly = true)
    public int notifyFederationFinalScheduled(String federationName, List<UUID> finalistPlayerIds, Instant when) {
        String slot = format(when);
        int notified = 0;
        for (UUID playerId : finalistPlayerIds) {
            User user = userRepository.findById(playerId).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            emailService.sendFederationFinalScheduledEmail(
                    user.getEmail(), user.getUsername(), federationName, slot);
            notified++;
        }
        log.info("Federated pyramid '{}' final scheduled — notified {} finalist(s) by e-mail",
                federationName, notified);
        return notified;
    }

    private static String format(Instant instant) {
        return instant == null ? "not previously scheduled" : SLOT_FORMAT.format(instant);
    }
}
