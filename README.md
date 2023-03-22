# simple-jdbc-connection-pool
Yet another JDBC connection pool

## Design decisions

### Fixed pools size

### Connection validation on checkout

### Orphan connection detection

### No extra threads

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






