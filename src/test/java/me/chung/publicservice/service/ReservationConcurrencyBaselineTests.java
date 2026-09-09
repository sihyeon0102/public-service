package me.chung.publicservice.service;

import java.sql.SQLException;
import io.micrometer.core.instrument.MeterRegistry;
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

    @Autowired
    private MeterRegistry metrics;

    @RepeatedTest(3)
    void boundedRetryPreservesCommittedCountInvariants(RepetitionInfo repetitionInfo)
            throws Exception {
        Facility facility = facilityRepository.findAll(
                PageRequest.of(0, 1, Sort.by("id"))).getContent().getFirst();
        Program program = programRepository.saveAndFlush(
                new Program(facility, "Concurrency Baseline", CAPACITY));
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Map<String, AtomicInteger> exceptions = new ConcurrentHashMap<>();
        Map<String, Double> before = retryMetrics();

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
                    } catch (ResponseStatusException exception) {
                        if (exception.getStatusCode().value() == 409) {
                            conflicts.incrementAndGet();
                        } else {
                            exceptions.computeIfAbsent(exceptionName(exception),
                                    key -> new AtomicInteger()).incrementAndGet();
                        }
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
            int unexpectedFailures = exceptions.values().stream()
                    .mapToInt(AtomicInteger::get)
                    .sum();

            System.out.printf(
                    "OPTIMISTIC_RETRY run=%d capacity=%d requests=%d successes=%d conflicts=%d "
                            + "unexpectedFailures=%d reservedCount=%d reservationRows=%d "
                            + "exceptions=%s%n",
                    repetitionInfo.getCurrentRepetition(), CAPACITY, REQUESTS, successes.get(),
                    conflicts.get(), unexpectedFailures, result.getReservedCount(),
                    reservationRows, exceptionCounts(exceptions));
            Map<String, Double> delta = retryMetrics();
            delta.replaceAll((key, value) -> value - before.getOrDefault(key, 0.0));
            System.out.println("RETRY_METRICS run=" + repetitionInfo.getCurrentRepetition()
                    + " counters=" + delta);

            assertThat(successes.get()).isBetween(1, CAPACITY);
            assertThat(successes.get() + conflicts.get() + unexpectedFailures)
                    .isEqualTo(REQUESTS);
            assertThat(reservationRows).isEqualTo(successes.get());
            assertThat(result.getReservedCount()).isEqualTo(reservationRows);
            assertThat(result.getVersion()).isEqualTo(reservationRows);
            assertThat(exceptions.keySet()).allMatch(name ->
                    name.contains("OptimisticLock") || name.contains("errorCode=1213"));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            jdbcTemplate.update("DELETE FROM reservation WHERE program_id = ?", program.getId());
            jdbcTemplate.update("DELETE FROM program WHERE id = ?", program.getId());
        }
    }

    private Map<String, Double> retryMetrics() {
        Map<String, Double> result = new java.util.TreeMap<>();
        metrics.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("reservation."))
                .forEach(meter -> meter.measure().forEach(measurement ->
                        result.put(meter.getId().toString(), measurement.getValue())));
        return result;
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
        List<String> chain = new ArrayList<>();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            chain.add(current.getClass().getSimpleName());
        }
        return String.join(" -> ", chain);
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
