package com.ssafy.test.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
public class RedisDummyMaker {

    private final RedisTemplate<String, String> redisTemplate;

    private final Random random = new Random();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void startProducer() {
        // Redis 연결 확인
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            System.out.println("✅ Redis Ping Response: " + pong);
        } catch (Exception e) {
            System.out.println("❌ Redis 연결 실패");
            return;
        }

//        // 별도 스레드에서 더미 데이터 주기적으로 넣기
        executor.submit(() -> {
            int cnt = 0;
            while (cnt < 30) {
                try {
                    // 1~5 사이의 랜덤 좌표
                    int x = 0;
                    int y = 0;
                    int z = 0;

                    // chunkId 생성
                    String chunkId = "{world:exampleWorld}:l0:x" + x + ":y" + y + ":z" + z;
                    String sortedSetKey = "op_ids:" + chunkId;
                    String hashKey = "deltas:" + chunkId;

                    // ObjectMapper 준비 (Instant 등 JavaTimeModule 자동 등록)
                    ObjectMapper objectMapper = JsonMapper.builder()
                            .findAndAddModules()  // JavaTimeModule 포함
                            .build();

                    // DeltaDTO 생성 (Builder 사용)
                    DeltaDTO dto = new DeltaDTO(
                            UUID.randomUUID(),
                            random.nextInt(Integer.MAX_VALUE),
                            63,
                            DeltaDTO.ColorSchema.RGB_FACES,
                            new byte[]{
                                    (byte) random.nextInt(256),
                                    (byte) random.nextInt(256),
                                    (byte) random.nextInt(256)
                            },
                            "system",
                            "test",
                            Instant.now()
                    );

                    String json = objectMapper.writeValueAsString(dto);

                    // Sorted Set에 op_id 저장 (score = timestamp)
                    double score = (double) dto.timestamp().toEpochMilli();
                    redisTemplate.opsForZSet().add(sortedSetKey, dto.opId().toString(), score);

                    // Hash에 DTO 직렬화 바이트 저장
                    redisTemplate.opsForHash().put(hashKey, dto.opId().toString(), json);

                    System.out.printf("[+] %s 에 더미 데이터 추가 (op_id=%s)%n", chunkId, dto.opId());
                    cnt++;
                    // 1초 대기
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
