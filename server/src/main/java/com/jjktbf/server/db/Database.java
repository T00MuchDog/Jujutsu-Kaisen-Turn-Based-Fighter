package com.jjktbf.server.db;

import com.jjktbf.server.config.ServerConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Owns the server connection pool, migrations, and transaction boundaries. */
public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;

    public Database(ServerConfig config) {
        Objects.requireNonNull(config, "config");
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.databaseUrl());
        hikari.setUsername(config.databaseUsername());
        hikari.setPassword(config.databasePassword());
        hikari.setPoolName("jjktbf-server-db");
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(1);
        hikari.setAutoCommit(true);
        hikari.setInitializationFailTimeout(10_000);

        dataSource = new HikariDataSource(hikari);
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        } catch (RuntimeException exception) {
            dataSource.close();
            throw exception;
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public <T> T transaction(TransactionWork<T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    throw new DatabaseException("Database transaction failed", sqlException);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not obtain a database connection", exception);
        }
    }

    public <T> T withConnection(TransactionWork<T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw new DatabaseException("Database operation failed", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
