package org.codejargon.fluentjdbc.api.integration.guicepersist.standalone;

import org.codejargon.fluentjdbc.api.FluentJdbcSqlException;
import org.codejargon.fluentjdbc.api.integration.ConnectionProvider;
import org.codejargon.fluentjdbc.api.integration.QueryConnectionReceiver;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * <p>Wraps a ConnectionProvider to support transaction management without the use of a JPA provider. 
 * Needs FluentJdbcTransactionalModule installed to enable transaction handling.</p>
 * @see org.codejargon.fluentjdbc.api.integration.guicepersist.standalone.FluentJdbcTransactionalModule
 */
public class TransactionalConnectionProvider implements ConnectionProvider {
    private final ThreadLocal<Optional<Connection>> currentTxConnection = new ThreadLocal<Optional<Connection>>() {
        @Override
        protected Optional<Connection> initialValue() {
            return Optional.empty();
        }
    };

    // Note: this connectionProvider will never close a Connection. Needs to be handled in "provide".
    private final ConnectionProvider connectionProvider;

    /**
     * Constructs TransactionalConnectionProvider based on a DataSource.
     * @param dataSource Non-transactionaware DataSource
     */
    public TransactionalConnectionProvider(DataSource dataSource) {
        this.connectionProvider = q -> q.receive(dataSource.getConnection());
    }

    /**
     * Constructs TransactionalConnectionProvider based on ConnectionProvider. The ConnectionProvider implementation
     * must keep the connection open (closing would disrupt transaction management)
     * @param connectionProvider an implementation that keeps the connection open after acquiring it
     */
    public TransactionalConnectionProvider(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void provide(QueryConnectionReceiver query) throws SQLException {
        Optional<Connection> current = currentTxConnection.get();
        if (current.isPresent()) {
            query.receive(current.get());
            // TransactionInterceptor will call removeActiveTransactionConnection() to close the connection
        } else {
            try(Connection connection = fetchNewConnection()) {
                query.receive(connection);
            }
        }
    }

    Boolean hasActiveTransaction() {
        return currentTxConnection.get().isPresent();
    }

    void startNewTransaction() {
        try {
            Connection connection = fetchNewConnection();
            connection.setAutoCommit(false);
            currentTxConnection.set(Optional.of(connection));
        } catch (SQLException e) {
            throw new FluentJdbcSqlException("Error initializing transaction", e);
        }
    }

    void commitActiveTransaction(Optional<Exception> ignoredException) {
        try {
            currentTxConnection.get().get().commit();
        } catch (SQLException e) {
            if (!ignoredException.isPresent()) {
                throw new FluentJdbcSqlException("Error committing transaction", e);
            } else {
                // todo logging of e
            }
        }
    }

    void rollbackActiveTransaction() {
        try {
            currentTxConnection.get().get().rollback();
        } catch (SQLException e) {
            // todo logging of e
        }
    }

    void removeActiveTransactionConnection() {
        if(currentTxConnection.get().isPresent()) {
            try {
                currentTxConnection.get().get().close();
            } catch(Exception e) {
                // todo logging of e
            }
            currentTxConnection.set(Optional.empty());
        }
    }

    private Connection fetchNewConnection() throws SQLException {
        ConnectionReceiver receiver = new ConnectionReceiver();
        connectionProvider.provide(receiver);
        return receiver.connection();
    }


    private static class ConnectionReceiver implements QueryConnectionReceiver {
        private Optional<Connection> connection = Optional.empty();

        @Override
        public void receive(Connection connection) throws SQLException {
            this.connection = Optional.of(connection);
        }

        Connection connection() {
            return connection.get();
        }
    }

}