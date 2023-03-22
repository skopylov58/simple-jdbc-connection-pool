# simple-jdbc-connection-pool
Yet another JDBC connection pool

## Design decisions

### Fixed pools size

### Connection validation on checkout

### Orphan connection detection

## Usage

```java
        JDBCConnectionPool pool = new JDBCConnectionPool("jdbc:mysql:///");
        pool.configure(c -> {
            c.poolSize = 10;
            c.clientTimeout = Duration.ofSeconds(30);
            c.retryCount = 1000;
            c.retryDelay = Duration.ofSeconds(1);
            c.validateConnectionOnCheckout = true;
            c.connectionValidationTimeout = Duration.ofSeconds(3);
            c.detectOrphanConnections = true;
            c.orphanTimeout = Duration.ofSeconds(300);
        });
        pool.start();
        //...
        try(Connection connection = pool.getConnection()) {
            //use connection here
        } catch (SQLException e) {
            //handle exceptions here
        }
        //connection will be returned to the pool after curly brace above
        //...
        pool.stop();
```
## Performance benchmarking

I've compared this connection pool with well known c3p0 pool version 0.9.5.5

Benchmark test uses 5 connections in the pool and 5, 10, 15 concurrently running clients. Each client checks out connections from the pool and immediately return it back.



| Threads |  5   |  10  |  15  |
|---------|------|------|------|
|  simple | 0.83 | 1.57 | 2.54 |
|  c3p0   |11.97 |26.21 |56.12 |




