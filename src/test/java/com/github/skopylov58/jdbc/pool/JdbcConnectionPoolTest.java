package com.github.skopylov58.jdbc.pool;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import org.junit.Test;

public class JdbcConnectionPoolTest {

    String h2 = "jdbc:h2:mem:test_mem";
    
    @Test
    public void test0() throws Exception {
        JDBCConnectionPool pool = new JDBCConnectionPool(h2);
        pool.start();
        Connection connection = pool.getConnection();
        assertNotNull(connection);
        connection.close();
        pool.stop();
    }

    @Test
    public void test1() throws Exception {
        JDBCConnectionPool pool = new JDBCConnectionPool(h2);
        pool.configure(c -> c.poolSize = 2);
        pool.start();
        
        Connection connection = pool.getConnection();
        assertNotNull(connection);
        
        Connection connection2 = pool.getConnection();
        assertNotNull(connection2);

        try {
            Connection connection3 = pool.getConnection();
            fail();
        } catch (SQLException e) {
            //expected
            System.out.println(e.getMessage());
        }
        
        connection.close();
        connection2.close();
        pool.stop();
    }
    
    @Test
    public void testWrapper() throws Exception {
        JDBCConnectionPool pool = new JDBCConnectionPool(h2);
        pool.start();
        Connection connection = pool.getConnection();
        Connection unwraped = connection.unwrap(Connection.class);
        assertEquals("org.h2.jdbc.JdbcConnection", unwraped.getClass().getName());
        connection.close();
        pool.stop();
    }
    
    @Test
    public void testOrphan() throws Exception {
        JDBCConnectionPool pool = new JDBCConnectionPool(h2);
        pool.configure(c -> {
            c.detectOrphanConnections = true;
            c.orphanTimeout = Duration.ofSeconds(1);
        });
        pool.start();
        Connection connection = pool.getConnection();
        Thread.sleep(3000);
        connection.close();
        pool.stop();
    }
    
    public void usage() {
        JDBCConnectionPool pool = new JDBCConnectionPool("jdbc:mysql:///");
        pool.configure(c -> {
            c.poolSize = 10;
            c.clientTimeout = Duration.ofSeconds(30);
            c.retryCount = 1000;
            c.retryDelay = Duration.ofSeconds(1);
            c.validateConnectionOnCheckout = true;
            c.connectionValidationTimeout = Duration.ofSeconds(3);
            c.detectOrphanConnections = true;
            c.orphanTimeout = Duration.ofSeconds(1);
        });
        pool.start();
        //...
        try(Connection connection = pool.getConnection()) {
            //use connection here
        } catch (SQLException e) {
            
        }
        //connection will be closed and returned to the pool after curly brace above
        //...
        pool.stop();
    }
    
    
}
