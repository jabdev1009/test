package com.ssafy.test.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import software.amazon.awssdk.services.s3.model.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;

    @Value("${spring.cloud.aws.s3.bucket-name}")
    private String bucketName;

    public void saveChunkFile(String worldName, int lod, int x, int y, int z, int version, String jsonData) {
        String key = String.format("%s/lod%d/x%d/y%d/z%d/v%d.json", worldName, lod, x, y, z, version);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("application/json")
                .build();

        s3Client.putObject(putRequest, RequestBody.fromString(jsonData, StandardCharsets.UTF_8));
        System.out.println("✅ 파일 저장 완료: " + key);
    }

    public String getChunkFile(String worldName, int lod, int x, int y, int z, int version) {
        String key = String.format("%s/lod%d/x%d/y%d/z%d/v%d.json", worldName, lod, x, y, z, version);

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return s3Client.getObjectAsBytes(getRequest).asString(StandardCharsets.UTF_8);
    }

    public String getLatestChunkFile(String worldName, int lod, int x, int y, int z) {
        String prefix = String.format("%s/lod%d/x%d/y%d/z%d/", worldName, lod, x, y, z);

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);

        // 버전 숫자가 가장 큰 파일 찾기
        return listResponse.contents().stream()
                .map(S3Object::key)
                .max((a, b) -> {
                    int va = extractVersion(a);
                    int vb = extractVersion(b);
                    return Integer.compare(va, vb);
                })
                .map(latestKey -> {
                    GetObjectRequest getRequest = GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(latestKey)
                            .build();
                    return s3Client.getObjectAsBytes(getRequest).asString(StandardCharsets.UTF_8);
                })
                .orElseThrow(() -> new RuntimeException("해당 좌표에 파일이 없습니다."));
    }

    private int extractVersion(String key) {
        try {
            // 예: worldname/lod0/x1/y1/z1/v12.json → 12
            String fileName = key.substring(key.lastIndexOf('/') + 1); // v12.json
            return Integer.parseInt(fileName.replace("v", "").replace(".json", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    
}
