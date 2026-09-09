package me.chung.publicservice.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import me.chung.publicservice.domain.Program;
import me.chung.publicservice.domain.Reservation;
import me.chung.publicservice.dto.ReservationResponse;
import me.chung.publicservice.repository.ProgramRepository;
import me.chung.publicservice.repository.ReservationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationService {

    private static final int MAX_ATTEMPTS = 3;
    private final ProgramRepository programRepository;
    private final ReservationRepository reservationRepository;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;

    public ReservationService(ProgramRepository programRepository,
                              ReservationRepository reservationRepository,
                              PlatformTransactionManager transactionManager,
                              MeterRegistry metrics) {
        this.programRepository = programRepository;
        this.reservationRepository = reservationRepository;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ReservationResponse reserve(Long programId, String participantId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ReservationResponse response = transaction.execute(
                        status -> reserveAttempt(programId, participantId));
                recordAttempt(attempt, "success");
                return response;
            } catch (RuntimeException exception) {
                String outcome = failureKind(exception);
                recordAttempt(attempt, outcome);
                boolean retryable = outcome.equals("optimistic") || outcome.equals("deadlock");
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                // execute() has completed rollback before backoff or the next transaction.
                long minimumMillis = 10L << (attempt - 1);
                long delayMillis = ThreadLocalRandom.current().nextLong(
                        minimumMillis, minimumMillis * 3 + 1);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Reservation retry interrupted", interrupted);
                }
                metrics.counter("reservation.retries", "reason", outcome).increment();
            }
        }
        throw new IllegalStateException("Reservation attempts exhausted");
    }

    private void recordAttempt(int attempt, String outcome) {
        metrics.counter("reservation.attempts", "attempt", Integer.toString(attempt),
                "outcome", outcome).increment();
    }

    private String failureKind(RuntimeException exception) {
        if (exception instanceof ResponseStatusException) {
            return "business_rejection";
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException) {
                return "optimistic";
            }
            if (cause instanceof SQLException sql
                    && sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) {
                return "deadlock";
            }
        }
        return "error";
    }

    private ReservationResponse reserveAttempt(Long programId, String participantId) {
        if (participantId == null || participantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "participantId is required");
        }

        Program program = programRepository.findById(programId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Program not found: " + programId));

        if (program.isFull()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Program is full");
        }

        program.reserve();
        try {
            Reservation reservation = reservationRepository.saveAndFlush(
                    new Reservation(program, participantId));
            return ReservationResponse.from(reservation, program);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Participant already reserved this program", exception);
        }
    }
}
