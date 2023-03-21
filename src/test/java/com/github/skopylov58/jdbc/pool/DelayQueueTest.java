package com.github.skopylov58.jdbc.pool;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class DelayQueueTest {
    
    @Test
    public void test0() throws Exception {
        
        DelayQueue<DelayedInt> queue = new DelayQueue<>();
        queue.put(new DelayedInt(1, Duration.ofSeconds(3)));
        System.out.println(new Date());
        DelayedInt taken = queue.take();
        System.out.println(new Date() + " " + taken);
        
        
    }
    
    static class DelayedInt implements Delayed {

        final Duration delay;
        final Instant created;
        final Integer i;
        
        public DelayedInt(Integer i, Duration d) {
            this.i = i;
            delay = d;
            created = Instant.now();
        }
        
        @Override
        public int compareTo(Delayed other) {
            return (this == other) ? 0 :
            Long.compare(getDelay(TimeUnit.MICROSECONDS),other.getDelay(TimeUnit.MICROSECONDS));
        }

        @Override
        public long getDelay(TimeUnit unit) {
            var elapsed = Duration.between(created, Instant.now());
            var result = unit.convert(delay.minus(elapsed));
            return result;
        }
        
    }
    

}
