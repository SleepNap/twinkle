package org.gms.persistence.migrate;

import org.gms.persistence.SimpleDriverDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationRunnerConcurrencyTest {

    @Test
    void concurrentSqliteMigratorsSerializeAndRecheckVersions(@TempDir Path tempDir) throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("concurrent.db");
        DataSource firstDataSource = new SimpleDriverDataSource(url, "", "");
        DataSource secondDataSource = new SimpleDriverDataSource(url, "", "");
        CyclicBarrier startTogether = new CyclicBarrier(2);

        Callable<Integer> first = () -> {
            startTogether.await(10, TimeUnit.SECONDS);
            return MigrationRunner.applyMigrations(firstDataSource, "sqlite");
        };
        Callable<Integer> second = () -> {
            startTogether.await(10, TimeUnit.SECONDS);
            return MigrationRunner.applyMigrations(secondDataSource, "sqlite");
        };

        List<Integer> appliedCounts;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(first);
            var secondResult = executor.submit(second);
            appliedCounts = List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        }

        int recordedVersions;
        try (var conn = firstDataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_version")) {
            rs.next();
            recordedVersions = rs.getInt(1);
        }

        assertThat(appliedCounts).contains(0);
        assertThat(appliedCounts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(recordedVersions);
        assertThat(recordedVersions).isPositive();
        assertThat(MigrationRunner.applyMigrations(firstDataSource, "sqlite")).isZero();
    }
}
