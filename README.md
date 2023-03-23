![example workflow](https://github.com/skopylov58/simple-jdbc-connection-pool/actions/workflows/gradle.yml/badge.svg)

# simple-jdbc-connection-pool
Yet another JDBC connection pool - simple, concise, performant, reliable, single file source code.

## Usage

```java
        SimpleJDBCConnectionPool pool = new SimpleJDBCConnectionPool("jdbc:mysql:///");
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

## Design decisions

### Fixed pools size

Pool has fixed size which is specified `poolSize` configuration property.
There are no any `minPoolSize` and `maxPoolSize` configuration properties. 
This is subject of load balancing feature, not a pool itself. I suggest load balancing should be implemented (if any) in some another architectural level.

### Connection validation on checkout

Connection pool uses `isValid(int timeout)` method of JDBC connection to validate connection on checkout. To enable validation, set configuration property `validateConnectionOnCheckout` to `true` and specify appropriate `connectionValidationTimeout` property.

By default `validateConnectionOnCheckout` is set to `true`, but you can set it to `false` to improve total performance if you using some embedded or internal database with guaranteed connection.

### Orphan connection detection

If you did not close connection for any reason, it will not be returned to the pool and becomes orphan (or leaked). So the detection of orphan connections is very important feature of the connection pool.

To enable orphan connection detection, set pool configuration property `detectOrphanConnections` to true, and specify appropriate `orphanTimeout` property. If connection will not be returned to the pool after this timeout, then log message will be printed to the system logger on WARNING level with stack trace including place from where connection was checked out.

By default `detectOrphanConnections` is set to `false`, do not use this feature on the production, use it if only for debugging or troubleshooting of your app.

### No extra threads

During normal operation, pool does not consume any additional threads - watchdogs, etc.

## Performance benchmarking

I've compared this connection pool with latest well known c3p0 pool version 0.9.5.5

Benchmark test uses pool size of 5 connections and 5, 10, 15 concurrently running clients. Each client checks out connections from the pool and immediately return it back repeating this 1_000_000 times. Benchmark source code could be found in the src/test directory

Table below shows results in seconds.

| Threads |  5   |  10  |  15  |
|---------|------|------|------|
|  simple | 0.83 | 1.57 | 2.54 |
|  c3p0   |11.97 |26.21 |56.12 |

We can see that simple pool is about 10-20 times faster then c3p0.

Hardware:  
  - cpu: Intel(R) Core(TM) i7-8850H CPU @ 2.60GHz, 4028 MHz, 12 cores
  - mem: 64 GB






