package com.ssafy.test.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedissonLockService {

    private static final Logger log = LoggerFactory.getLogger(RedissonLockService.class);
    private static final String LOCK_PREFIX = "lock:snapshot:";

    private final RedissonClient redissonClient;

    public RLock getLock(String chunkKey) {
        String lockKey = LOCK_PREFIX + chunkKey;
        return redissonClient.getLock(lockKey);
    }

    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
                log.debug("락 해제 성공");
            } catch (Exception e) {
                log.error("락 해제 중 오류", e);
            }
        }
    }
}