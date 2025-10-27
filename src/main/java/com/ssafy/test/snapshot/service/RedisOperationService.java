
package com.ssafy.test.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Redis 작업 처리 서비스
 */
@Service
@RequiredArgsConstructor
public class RedisOperationService {

    private static final Logger log = LoggerFactory.getLogger(RedisOperationService.class);
    private static final String DELTAS_PREFIX = "deltas:";
    private static final String TOMBSTONE_PREFIX = "tombstone:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 처리 완료된 데이터 정리 (Pipeline 사용)
     */
    public void cleanupProcessedData(String chunkKey, Set<String> opIds,
                                     Set<String> tombstoneOpIds, double maxScore) {
        try {
            redisTemplate.execute(connection -> {
                connection.openPipeline();

                if (opIds != null && !opIds.isEmpty()) {
                    redisTemplate.opsForZSet().removeRangeByScore(
                            chunkKey, Double.NEGATIVE_INFINITY, maxScore
                    );
                }

                if (opIds != null && !opIds.isEmpty()) {
                    String deltaKey = DELTAS_PREFIX + chunkKey;
                    redisTemplate.opsForHash().delete(deltaKey, opIds.toArray());
                }

                if (tombstoneOpIds != null && !tombstoneOpIds.isEmpty()) {
                    String tombKey = TOMBSTONE_PREFIX + chunkKey;
                    redisTemplate.opsForZSet().removeRangeByScore(
                            tombKey, Double.NEGATIVE_INFINITY, maxScore
                    );
                }

                connection.closePipeline();
                return null;
            });

            log.info("Redis 정리 완료. 청크: {}", chunkKey);

        } catch (Exception e) {
            log.error("Redis 정리 실패. 청크: {}", chunkKey, e);
        }
    }
}
