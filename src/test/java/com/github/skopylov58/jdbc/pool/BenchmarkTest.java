package com.github.skopylov58.jdbc.pool;

import static org.junit.Assert.*;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.Test;

import com.mchange.v2.c3p0.ComboPooledDataSource;

public class BenchmarkTest {

    final String H2 = "jdbc:h2:mem:test_mem";

    @Test
    public void testMy() throws Exception {
        JDBCConnectionPool pool = new JDBCConnectionPool(H2);
        pool.configure(c -> {
            c.poolSize = 5;
            c.validateConnectionOnCheckout = false;
        });
        pool.start();
        
        foo("My", pool);
        pool.stop();
    }
    
    @Test
    public void testC3p0() throws Exception {
        ComboPooledDataSource cpds = new ComboPooledDataSource();
        cpds.setJdbcUrl(H2);
        // the settings below are optional -- c3p0 can work with defaults
        cpds.setMinPoolSize(5);                                     
        cpds.setMaxPoolSize(5);
        
        foo("C3p0", cpds);
    }

    void foo(String poolName, DataSource ds) throws Exception {
        System.out.println("Running bench for pool " + poolName);
        int [] numOfThreads = new int[] {5, 10, 15};
        for (int i = 0; i < numOfThreads.length; i++) {
            Duration duration = bench(ds, numOfThreads[i], 1_000_000);
            System.out.println("Num of threads " + numOfThreads[i] + " duration=" + duration);
        }
        System.out.println("Finished bench for pool " + poolName);
    }
    
    public Duration bench(DataSource ds, int numThreads, int numCheckouts) throws Exception {
        Instant start = Instant.now();
        var threads = IntStream.range(0, numThreads)
        .mapToObj(i -> new WorkerThread(ds, numCheckouts))
        .toList();
        
        threads.forEach(Thread::start);
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        return Duration.between(start, Instant.now());
    }
    
    public class WorkerThread extends Thread {
        
        private final DataSource ds;
        private final int cnt;
        private boolean failed = false;
        
        public WorkerThread(DataSource ds, int cnt) {
            this.ds = ds;
            this.cnt = cnt;
        }
        
        @Override
        public void run() {
            for (int i = 0; i < cnt; i++) {
                try {
                    ds.getConnection().close();
                } catch (SQLException e) {
                    failed = true;
                    System.out.println("Thread " + getId() + " cnt=" + i);
                    e.printStackTrace();
                    break;
                }
            }
        }
    }
}
