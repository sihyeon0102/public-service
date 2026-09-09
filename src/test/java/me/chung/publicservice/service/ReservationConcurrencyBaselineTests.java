package me.chung.publicservice.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.chung.publicservice.domain.Facility;
import me.chung.publicservice.domain.Program;
import me.chung.publicservice.repository.FacilityRepository;
import me.chung.publicservice.repository.ProgramRepository;
import me.chung.publicservice.repository.ReservationRepository;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cache.type=none",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.orm.jdbc.error=OFF"
})
class ReservationConcurrencyBaselineTests {

    private static final int CAPACITY = 100;
    private static final int REQUESTS = 200;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FacilityRepository facilityRepository;

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @RepeatedTest(3)
    void lockFreeReservationReproducesBrokenConsistencyInvariant(RepetitionInfo repetitionInfo)
            throws Exception {
        Facility facility = facilityRepository.findAll(
                PageRequest.of(0, 1, Sort.by("id"))).getContent().getFirst();
        Program program = programRepository.saveAndFlush(
                new Program(facility, "Concurrency Baseline", CAPACITY));
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        Map<String, AtomicInteger> exceptions = new ConcurrentHashMap<>();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int participant = 1; participant <= REQUESTS; participant++) {
                String participantId = "concurrent-" + repetitionInfo.getCurrentRepetition()
                        + "-" + participant;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        reservationService.reserve(program.getId(), participantId);
                        successes.incrementAndGet();
                    } catch (Exception exception) {
                        exceptions.computeIfAbsent(exceptionName(exception),
                                key -> new AtomicInteger()).incrementAndGet();
                    }
                    return null;
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            Program result = programRepository.findById(program.getId()).orElseThrow();
            long reservationRows = reservationRepository.countByProgramId(program.getId());
            int failures = REQUESTS - successes.get();
            boolean overbooked = reservationRows > CAPACITY;
            boolean countMatchesRows = result.getReservedCount() == reservationRows;

            System.out.printf(
                    "CONCURRENCY_BASELINE run=%d capacity=%d requests=%d successes=%d failures=%d "
                            + "reservedCount=%d reservationRows=%d overbooked=%s "
                            + "countMatchesRows=%s exceptions=%s%n",
                    repetitionInfo.getCurrentRepetition(), CAPACITY, REQUESTS, successes.get(),
                    failures, result.getReservedCount(), reservationRows, overbooked,
                    countMatchesRows, exceptionCounts(exceptions));

            assertThat(successes.get() + failures).isEqualTo(REQUESTS);
            assertThat(reservationRows).isEqualTo(successes.get());
            assertThat(overbooked || !countMatchesRows).isTrue();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            jdbcTemplate.update("DELETE FROM reservation WHERE program_id = ?", program.getId());
            jdbcTemplate.update("DELETE FROM program WHERE id = ?", program.getId());
        }
    }

    private String exceptionName(Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return exception.getClass().getSimpleName()
                        + "(errorCode=" + sqlException.getErrorCode()
                        + ",sqlState=" + sqlException.getSQLState() + ")";
            }
            cause = cause.getCause();
        }
        if (exception instanceof ResponseStatusException statusException) {
            return exception.getClass().getSimpleName() + "(" + statusException.getStatusCode() + ")";
        }
        return exception.getClass().getSimpleName();
    }

    private String exceptionCounts(Map<String, AtomicInteger> exceptions) {
        if (exceptions.isEmpty()) {
            return "{}";
        }
        Map<String, Integer> values = new ConcurrentHashMap<>();
        exceptions.forEach((name, count) -> values.put(name, count.get()));
        return values.toString();
    }
}
