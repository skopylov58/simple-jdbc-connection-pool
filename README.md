# simple-jdbc-connection-pool
Yet another JDBC connection pool


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
