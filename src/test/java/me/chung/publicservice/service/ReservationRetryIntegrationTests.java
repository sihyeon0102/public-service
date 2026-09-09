package me.chung.publicservice.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.chung.publicservice.domain.Program;
import me.chung.publicservice.repository.FacilityRepository;
import me.chung.publicservice.repository.ProgramRepository;
import me.chung.publicservice.repository.ReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"spring.cache.type=none", "spring.jpa.show-sql=false"})
class ReservationRetryIntegrationTests {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @Autowired private ReservationService service;
    @Autowired private ProgramRepository programs;
    @Autowired private FacilityRepository facilities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ReservationRepository reservations;
    private Program program;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Integer> completions = new ArrayList<>();

    @BeforeEach
    void fixture() {
        program = programs.saveAndFlush(new Program(
                facilities.findAll(PageRequest.of(0, 1)).getContent().getFirst(), "Retry boundary", 100));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM reservation WHERE program_id=?", program.getId());
        jdbc.update("DELETE FROM program WHERE id=?", program.getId());
    }

    @Test
    void eachRetryStartsAfterRollbackAndReloadsCleanDatabaseState() {
        doAnswer(invocation -> {
            int attempt = calls.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(completions).hasSize(attempt - 1);
            assertThat(completions).allMatch(status -> status == TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE program_id=?",
                    Long.class, program.getId())).isZero();
            assertThat(jdbc.queryForObject("SELECT reserved_count FROM program WHERE id=?",
                    Integer.class, program.getId())).isZero();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { completions.add(status); }
            });
            // Execute a real INSERT/flush in the active MySQL transaction before fault injection.
            Object saved = invocation.getArgument(0);
            entityManager.persist(saved);
            entityManager.flush();
            if (attempt == 1) throw new ObjectOptimisticLockingFailureException(Program.class, program.getId());
            if (attempt == 2) throw new CannotAcquireLockException("injected deadlock",
                    new SQLException("deadlock", "40001", 1213));
            return saved;
        }).when(reservations).saveAndFlush(any());

        service.reserve(program.getId(), "boundary");

        assertThat(calls.get()).isEqualTo(3);
        assertThat(completions).containsExactly(1, 1, 0); // rollback, rollback, commit
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(reservations.countByProgramId(program.getId())).isEqualTo(1);
        Program result = programs.findById(program.getId()).orElseThrow();
        assertThat(result.getReservedCount()).isEqualTo(1);
        assertThat(result.getVersion()).isEqualTo(1);
    }

    @Test
    void stopsAtThreeAttemptsAndPropagatesFinalConflict() {
        doAnswer(invocation -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException(Program.class, program.getId());
        }).when(reservations).saveAndFlush(any());
        assertThatThrownBy(() -> service.reserve(program.getId(), "exhausted"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(reservations.countByProgramId(program.getId())).isZero();
        assertThat(programs.findById(program.getId()).orElseThrow().getReservedCount()).isZero();
    }

    @Test
    void retryRereadsCapacityAndReturnsConflictWhenAnotherTransactionFilledProgram() {
        jdbc.update("UPDATE program SET capacity=1 WHERE id=?", program.getId());
        doAnswer(invocation -> {
            calls.incrementAndGet();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    assertThat(status).isEqualTo(STATUS_ROLLED_BACK);
                    var other = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                    other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    other.executeWithoutResult(ignored -> {
                        jdbc.update("UPDATE program SET reserved_count=1, version=1 WHERE id=?", program.getId());
                        jdbc.update("INSERT INTO reservation(program_id,participant_id,created_at) VALUES (?, ?, NOW())",
                                program.getId(), "other-transaction");
                    });
                }
            });
            throw new ObjectOptimisticLockingFailureException(Program.class, program.getId());
        }).when(reservations).saveAndFlush(any());
        assertThatThrownBy(() -> service.reserve(program.getId(), "late-participant"))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(reservations.countByProgramId(program.getId())).isEqualTo(1);
        assertThat(programs.findById(program.getId()).orElseThrow().getReservedCount()).isEqualTo(1);
    }

    @Test
    void doesNotRetryOtherDatabaseErrors() {
        doAnswer(invocation -> {
            calls.incrementAndGet();
            throw new CannotAcquireLockException("not a deadlock", new SQLException("timeout", "HY000", 1205));
        }).when(reservations).saveAndFlush(any());
        assertThatThrownBy(() -> service.reserve(program.getId(), "timeout"))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(reservations.countByProgramId(program.getId())).isZero();
    }
}
