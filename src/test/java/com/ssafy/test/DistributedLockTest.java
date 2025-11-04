package com.ssafy.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


class DistributedLockTest {

    @Test
    void testDistributedLock() throws InterruptedException {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient client = Redisson.create(config);

        RLock lock = client.getLock("myTestLock");

        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        Runnable task = () -> {
            try {
                if (lock.tryLock(5, 2, TimeUnit.SECONDS)) {
                    System.out.println(Thread.currentThread().getName() + " got lock!");
                    Thread.sleep(1000);
                } else {
                    System.out.println(Thread.currentThread().getName() + " could NOT get lock...");
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
                latch.countDown();
            }
        };

        executor.submit(task);
        executor.submit(task);

        latch.await();
        client.shutdown();
    }
}