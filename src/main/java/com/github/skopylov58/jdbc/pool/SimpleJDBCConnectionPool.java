package com.github.skopylov58.jdbc.pool;

import java.io.PrintWriter;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.github.skopylov58.retry.Retry;

/**
 * Simple JDBC Connection pool.
 * @author skopylov
 *
 */
public class SimpleJDBCConnectionPool implements DataSource {
    
    private static final String ERROR_CLOSING_CONNECTION = "Error closing connection";
    private static final String NO_AVAILABLE_CONNECTIONS = "There are no available connections in the pool";
    
    private static final Logger logger = System.getLogger(SimpleJDBCConnectionPool.class.getName());

    private final String dbUrl;
    private final Config config = new Config();
    
    private BlockingQueue<PooledConnection> pool = new LinkedBlockingQueue<>();
    private DelayQueue<Orphanable> checkedOut;
    private ScheduledExecutorService orpansWatchDog;

    /**
     * Constructor.
     * @param url URL to the database.
     */
    public SimpleJDBCConnectionPool(String url) {
        dbUrl = url;
    }

    /**
     * Starts connection pool.
     */
    public void start() {
        for (int i = 0; i < config.poolSize; i++) {
            aquireDbConnection(dbUrl);
        }
        if (config.detectOrphanConnections) {
            checkedOut = new DelayQueue<>();
            orpansWatchDog = Executors.newScheduledThreadPool(1);
            orpansWatchDog.scheduleWithFixedDelay(this::checkOrphan, 0, 1, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Stops connection pool.
     */
    public void stop() {
        pool.forEach(c -> {
            try {
                c.getDelegate().close();
            } catch (SQLException e) {
                logger.log(Level.TRACE, ERROR_CLOSING_CONNECTION, e);
            }
        });
        pool.clear();
        if (config.detectOrphanConnections) {
            orpansWatchDog.shutdown();
            checkedOut.forEach(o -> {
                try {
                    o.connection.getDelegate().close();
                } catch (SQLException e) {
                    logger.log(Level.TRACE, ERROR_CLOSING_CONNECTION, e);
                }
            });
        }
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(config.clientTimeout);
    }
    
    public Connection getConnection(Duration timeout) throws SQLException {
        Instant startTime = Instant.now();
        Duration remain = timeout;
        while(!remain.isNegative()) {
            PooledConnection con = getConnectionFromPool(timeout);
            if (con != null) {
                if (config.validateConnectionOnCheckout && !isValid(con, config.connectionValidationTimeout)) {
                    handleInvalidConnection(con);
                } else {
                    if (config.detectOrphanConnections) {
                        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                        checkedOut.add(new Orphanable(con, Instant.now(), config.orphanTimeout, stackTrace));
                    }
                    return con;
                }
            }
            Duration elapsed = Duration.between(startTime, Instant.now());
            remain = remain.minus(elapsed);
        }
        throw new SQLException(NO_AVAILABLE_CONNECTIONS);
    }

    public void configure(Consumer<Config> cnf) {
        cnf.accept(config);
    }

    public static boolean isValid(PooledConnection c, Duration timeout) {
        boolean valid = false;
        try {
            valid = c.isValid((int)timeout.getSeconds());
        } catch (SQLException e) {
            logger.log(Level.TRACE, "Error validating connection", e);
        }
        return valid;
    }    

    /**
     * Checks if there are any orphan connections and 
     * prints stack trace to the system logger with WARNING level.
     */
    private void checkOrphan() {
        var stack = Optional.ofNullable(checkedOut.peek())
        .map(Orphanable::stackTrace)
        .stream()
        .flatMap(Arrays::stream)
        .map(Objects::toString)
        .collect(Collectors.joining("\n"));

        if (!stack.isEmpty()) {
            logger.log(Level.WARNING, "Orphaned connection detected with stack:\n" + stack);
        }
    }
    
    private PooledConnection getConnectionFromPool(Duration timeout){
        long millisTimeout = timeout.toMillis();
        PooledConnection connection = null;
        try {
            connection = pool.poll(millisTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return connection;
    }

    private void handleInvalidConnection(PooledConnection con) {
        try {
            con.getDelegate().close();
        } catch (SQLException e) {
            logger.log(Level.TRACE, ERROR_CLOSING_CONNECTION, e);
        }
        aquireDbConnection(dbUrl);
    }

    private void aquireDbConnection(String dbUrl) {
        Retry.of(() -> DriverManager.getConnection(dbUrl))
        .withFixedDelay(config.retryDelay)
        .retry(config.retryCount)
        .thenAccept(c -> pool.add(new PooledConnection(c)));
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("Is not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        DriverManager.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return DriverManager.getLoginTimeout();
    }

    /**
     * Pool configuration parameters
     */
    public static class Config {
        public int poolSize = 10;
        
        public int retryCount = 10;
        public Duration retryDelay = Duration.ofSeconds(1);
        
        public  Duration clientTimeout = Duration.ofSeconds(10);
        
        public boolean validateConnectionOnCheckout = true;
        public Duration connectionValidationTimeout = Duration.ofSeconds(10);

        public boolean detectOrphanConnections = false;
        public Duration orphanTimeout = Duration.ofSeconds(30);
    }

    /**
     * Potentially orphaned connections. 
     */
    record Orphanable(PooledConnection connection, 
            Instant checkedOut,
            Duration timeout,
            StackTraceElement[] stackTrace)
            implements Delayed {
        @Override
        public int compareTo(Delayed other) {
            return (this == other) ? 0
                    : Long.compare(getDelay(TimeUnit.MICROSECONDS), other.getDelay(TimeUnit.MICROSECONDS));
        }

        @Override
        public long getDelay(TimeUnit unit) {
            var elapsed = Duration.between(checkedOut, Instant.now());
            return unit.convert(timeout.minus(elapsed));
        }
    }
    
    /**
     * Wrapper class for the physical DB connection.
     * 
     * Delegates calls to the physical connection.
     * Overrides {@link #close()} method to return connection to the pool.
     * 
     * @author skopylov@gmail.com
     *
     */
    class PooledConnection implements Connection {
        
        private final Connection delegate;
        
        /**
         * Constructor
         * @param c physical DB connection
         */
        PooledConnection(Connection c) {
            delegate = c;
        }
        
        /**
         * Gets physical connection
         * @return physical connection
         */
        public Connection getDelegate() {
            return delegate;
        }

        @SuppressWarnings("unchecked")
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (isWrapperFor(iface)) {
                return (T) delegate;
            }
            throw new SQLException("Not a wrapper for " + iface.getClass().getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface != null && iface.isAssignableFrom(delegate.getClass());
        }

        @Override
        public Statement createStatement() throws SQLException {
            return delegate.createStatement();
        }

        @Override
        public PreparedStatement prepareStatement(String sql) throws SQLException {
            return delegate.prepareStatement(sql);
        }

        @Override
        public CallableStatement prepareCall(String sql) throws SQLException {
            return delegate.prepareCall(sql);
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return delegate.nativeSQL(sql);
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            delegate.setAutoCommit(autoCommit);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return delegate.getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            delegate.rollback();
        }

        @Override
        public void close() throws SQLException {
            if (config.detectOrphanConnections) {
                var removed = checkedOut.removeIf(o ->  o.connection == this);
                if (!removed) {
                    throw new IllegalStateException();
                }
            }
            pool.add(this);
        }

        @Override
        public boolean isClosed() throws SQLException {
            return delegate.isClosed();
        }

        @Override
        public DatabaseMetaData getMetaData() throws SQLException {
            return delegate.getMetaData();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            delegate.setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return delegate.isReadOnly();
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
            delegate.setCatalog(catalog);
        }

        @Override
        public String getCatalog() throws SQLException {
            return delegate.getCatalog();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            delegate.setTransactionIsolation(level);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return delegate.getTransactionIsolation();
        }

        @Override
        public SQLWarning getWarnings() throws SQLException {
            return delegate.getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            delegate.clearWarnings();
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return delegate.createStatement(resultSetType, resultSetConcurrency);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return delegate.prepareCall(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public Map<String, Class<?>> getTypeMap() throws SQLException {
            return delegate.getTypeMap();
        }

        @Override
        public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
            delegate.setTypeMap(map);
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            delegate.setHoldability(holdability);
        }

        @Override
        public int getHoldability() throws SQLException {
            return delegate.getHoldability();
        }

        @Override
        public Savepoint setSavepoint() throws SQLException {
            return delegate.setSavepoint();
        }

        @Override
        public Savepoint setSavepoint(String name) throws SQLException {
            return delegate.setSavepoint(name);
        }

        @Override
        public void rollback(Savepoint savepoint) throws SQLException {
            delegate.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
            delegate.releaseSavepoint(savepoint);
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                int resultSetHoldability) throws SQLException {
            return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
                int resultSetHoldability) throws SQLException {
            return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return delegate.prepareStatement(sql, autoGeneratedKeys);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return delegate.prepareStatement(sql, columnIndexes);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return delegate.prepareStatement(sql, columnNames);
        }

        @Override
        public Clob createClob() throws SQLException {
            return delegate.createClob();
        }

        @Override
        public Blob createBlob() throws SQLException {
            return delegate.createBlob();
        }

        @Override
        public NClob createNClob() throws SQLException {
            return delegate.createNClob();
        }

        @Override
        public SQLXML createSQLXML() throws SQLException {
            return delegate.createSQLXML();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return delegate.isValid(timeout);
        }

        @Override
        public void setClientInfo(String name, String value) throws SQLClientInfoException {
            delegate.setClientInfo(name, value);
        }

        @Override
        public void setClientInfo(Properties properties) throws SQLClientInfoException {
            delegate.setClientInfo(properties);
        }

        @Override
        public String getClientInfo(String name) throws SQLException {
            return delegate.getClientInfo(name);
        }

        @Override
        public Properties getClientInfo() throws SQLException {
            return delegate.getClientInfo();
        }

        @Override
        public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return delegate.createArrayOf(typeName, elements);
        }

        @Override
        public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return delegate.createStruct(typeName, attributes);
        }

        @Override
        public void setSchema(String schema) throws SQLException {
            delegate.setSchema(schema);
        }

        @Override
        public String getSchema() throws SQLException {
            return delegate.getSchema();
        }

        @Override
        public void abort(Executor executor) throws SQLException {
            delegate.abort(executor);
        }

        @Override
        public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            delegate.setNetworkTimeout(executor, milliseconds);
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return delegate.getNetworkTimeout();
        }
    }
}
