package me.chung.publicservice.seed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("seed")
public class FacilitySeedRunner implements ApplicationRunner {

    private static final int BATCH_SIZE = 1000;
    private static final String LOCK_NAME = "public-service:facility-seed";
    private static final String INSERT_SQL = """
            INSERT INTO facility
                (name, region, district, type, address, latitude, longitude, operating_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;
    private final ConfigurableApplicationContext context;
    private final int count;

    public FacilitySeedRunner(DataSource dataSource, ConfigurableApplicationContext context,
                              @Value("${seed.facility.count:100000}") int count) {
        this.dataSource = dataSource;
        this.context = context;
        this.count = count;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        FacilitySeedData.validateCount(count);
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            acquireLock(connection);
            try {
                connection.setAutoCommit(false);
                requireEmptyTable(connection);
                log.info("Facility seed started: count={}, batchSize={}", count, BATCH_SIZE);
                insert(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                releaseLock(connection);
            }
        }
        log.info("Facility seed committed: {} rows, elapsed={} ms", count,
                (System.nanoTime() - started) / 1_000_000);
        // A successful seed is a one-off command, not a running web application.
        context.close();
    }

    private void insert(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (int index = 0; index < count; index++) {
                FacilitySeedData.Row row = FacilitySeedData.row(index, count);
                statement.setString(1, row.name());
                statement.setString(2, row.region());
                statement.setString(3, row.district());
                statement.setString(4, row.type().name());
                statement.setString(5, row.address());
                statement.setDouble(6, row.latitude());
                statement.setDouble(7, row.longitude());
                statement.setString(8, row.status().name());
                statement.addBatch();

                int inserted = index + 1;
                if (inserted % BATCH_SIZE == 0 || inserted == count) {
                    statement.executeBatch();
                    statement.clearBatch();
                }
                if (inserted % 100_000 == 0 || inserted == count) {
                    log.info("Facility seed progress: {} / {} (not yet committed)", inserted, count);
                }
            }
        }
    }

    private void requireEmptyTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM facility)");
             ResultSet result = statement.executeQuery()) {
            result.next();
            if (result.getBoolean(1)) {
                throw new IllegalStateException(
                        "Facility seed refused: facility must be empty. Reset test data explicitly first.");
            }
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("Facility seed refused: another seed holds the lock.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new SQLException("Could not release the facility seed lock");
                }
            }
        }
    }
}
