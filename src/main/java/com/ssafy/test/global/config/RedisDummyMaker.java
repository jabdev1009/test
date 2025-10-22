package com.ssafy.test.global.config;

import com.ssafy.test.snapshot.dto.DeltaDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
public class RedisDummyMaker {

    private final RedisTemplate<String, byte[]> redisTemplate; // DTO 직렬화용
    private final Random random = new Random();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void startProducer() {
        // Redis 연결 확인
        try {
            String pong = new String(redisTemplate.getConnectionFactory().getConnection().ping());
            System.out.println("✅ Redis Ping Response: " + pong);
        } catch (Exception e) {
            System.out.println("❌ Redis 연결 실패");
            e.printStackTrace();
            return;
        }

//        // 별도 스레드에서 더미 데이터 주기적으로 넣기
        executor.submit(() -> {
            while (true) {
                try {
                    // 1~5 사이의 랜덤 좌표
                    int x = random.nextInt(5) + 1;
                    int y = random.nextInt(5) + 1;
                    int z = random.nextInt(5) + 1;

                    // chunkId 생성
                    String chunkId = "{world:exampleWorld}:l0:x" + x + ":y" + y + ":z" + z;
                    String sortedSetKey = "op_ids:" + chunkId;
                    String hashKey = "deltas:" + chunkId;

                    // DeltaDTO 생성
                    DeltaDTO dto = new DeltaDTO();
                    dto.setOpId(UUID.randomUUID());
                    dto.setVoxelId(random.nextInt(999999));
                    dto.setFaceMask(random.nextInt(64));
                    dto.setColorSchema(DeltaDTO.ColorSchema.RGB1);
                    dto.setColorBytes(new byte[]{(byte) random.nextInt(256), (byte) random.nextInt(256), (byte) random.nextInt(256)});
                    dto.setActor("system");
                    dto.setPolicyTags("test");
                    dto.setTimestamp(Instant.now());

                    // DTO 자체 직렬화
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    oos.writeObject(dto);
                    oos.flush();
                    byte[] serialized = bos.toByteArray();

                    // Sorted Set에 op_id 저장 (score = timestamp)
                    double score = (double) dto.getTimestamp().toEpochMilli();
                    redisTemplate.opsForZSet().add(sortedSetKey, dto.getOpId().toString().getBytes(), score);

                    // Hash에 DTO 직렬화 바이트 저장
                    redisTemplate.opsForHash().put(hashKey, dto.getOpId().toString(), serialized);

                    System.out.printf("[+] %s 에 더미 데이터 추가 (op_id=%s)%n", chunkId, dto.getOpId());

                    // 1초 대기
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
