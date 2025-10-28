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

    /**
     * 청크에 대한 락 획득
     * @param chunkKey 청크 키
     * @return RLock 객체
     */
    public RLock getLock(String chunkKey) {
        String lockKey = LOCK_PREFIX + chunkKey;
        return redissonClient.getLock(lockKey);
    }

    /**
     * 락 획득 시도 with 자동 해제
     * @param chunkKey 청크 키
     * @param waitTime 대기 시간
     * @param leaseTime 락 유지 시간
     * @param unit 시간 단위
     * @return 락 획득 성공 여부
     */
    public boolean tryLockWithTimeout(String chunkKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = getLock(chunkKey);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, unit);
            if (acquired) {
                log.debug("락 획득 성공: {}", chunkKey);
            } else {
                log.info("락 획득 실패 (타임아웃): {}", chunkKey);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("락 획득 대기 중 인터럽트: {}", chunkKey, e);
            return false;
        }
    }

    /**
     * 안전한 락 해제
     * @param lock RLock 객체
     */
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