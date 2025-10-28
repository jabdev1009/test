package com.ssafy.test.snapshot.service;

import io.minio.*;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final MinioClient minioClient;

    @Value("${spring.cloud.aws.s3.bucket-name}")
    private String bucketName;

    public String uploadFile(String key, String jsonData) {
//        PutObjectRequest putRequest = PutObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .contentType("application/json")
//                .build();

//        s3Client.putObject(putRequest, RequestBody.fromString(jsonData, StandardCharsets.UTF_8));
        // return "temp"

        try {
            ByteArrayInputStream inputStream =
                    new ByteArrayInputStream(jsonData.getBytes(StandardCharsets.UTF_8));

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key) // S3의 key와 동일한 역할
                            .stream(inputStream, jsonData.length(), -1)
                            .contentType("application/json")
                            .build()
            );

            return "uploaded: " + key;

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("파일 업로드 실패", e);
        }
    }

    public String uploadFile(String key, byte[] glbData) {
//        PutObjectRequest putRequest = PutObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .contentType("model/gltf-binary")
//                .build();
//
//        s3Client.putObject(putRequest, RequestBody.fromBytes(glbData));
//
//        return "temp";
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(glbData)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .stream(inputStream, glbData.length, -1)
                            .contentType("model/gltf-binary")
                            .build()
            );
            return "uploaded: " + key;

        } catch (Exception e) {
            throw new RuntimeException("파일 업로드 실패: " + key, e);
        }
    }

    public String getChunkFile(String worldName, int lod, int x, int y, int z, int version) {
//        String key = String.format("%s/lod%d/x%d/y%d/z%d/v%d.json", worldName, lod, x, y, z, version);
//
//        GetObjectRequest getRequest = GetObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .build();
//
//        return s3Client.getObjectAsBytes(getRequest).asString(StandardCharsets.UTF_8);
        try {
            String key = String.format("%s/lod%d/x%d/y%d/z%d/v%d.json", worldName, lod, x, y, z, version);

            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .build()
            )) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

        } catch (Exception e) {
            throw new RuntimeException("파일 조회 실패", e);
        }
    }

//    public String getLatestChunkFile(String worldName, int lod, int x, int y, int z) {
//        String prefix = String.format("%s/lod%d/x%d/y%d/z%d/", worldName, lod, x, y, z);
//
//        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
//                .bucket(bucketName)
//                .prefix(prefix)
//                .build();
//
//        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
//
//        // 버전 숫자가 가장 큰 파일 찾기
//        return listResponse.contents().stream()
//                .map(S3Object::key)
//                .max((a, b) -> {
//                    int va = extractVersion(a);
//                    int vb = extractVersion(b);
//                    return Integer.compare(va, vb);
//                })
//                .map(latestKey -> {
//                    GetObjectRequest getRequest = GetObjectRequest.builder()
//                            .bucket(bucketName)
//                            .key(latestKey)
//                            .build();
//                    return s3Client.getObjectAsBytes(getRequest).asString(StandardCharsets.UTF_8);
//                })
//                .orElseThrow(() -> new RuntimeException("해당 좌표에 파일이 없습니다."));
//    }
//
//    private int extractVersion(String key) {
//        try {
//            // 예: worldname/lod0/x1/y1/z1/v12.json → 12
//            String fileName = key.substring(key.lastIndexOf('/') + 1); // v12.json
//            return Integer.parseInt(fileName.replace("v", "").replace(".json", ""));
//        } catch (Exception e) {
//            return 0;
//        }
//    }

    public String getLatestChunkFile(String worldName, int lod, int x, int y, int z) {
        try {
            String prefix = String.format("%s/lod%d/x%d/y%d/z%d/", worldName, lod, x, y, z);

            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix)
                            .recursive(true)
                            .build()
            );

            // 최신 버전 파일 찾기
            return StreamSupport.stream(results.spliterator(), false)
                    .map(result -> {
                        try {
                            return result.get().objectName();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .max(Comparator.comparingInt(this::extractVersion))
                    .map(latestKey -> {
                        try (InputStream stream = minioClient.getObject(
                                GetObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(latestKey)
                                        .build()
                        )) {
                            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            throw new RuntimeException("파일 읽기 실패", e);
                        }
                    })
                    .orElseThrow(() -> new RuntimeException("해당 좌표에 파일이 없습니다."));

        } catch (Exception e) {
            throw new RuntimeException("최신 파일 조회 실패", e);
        }
    }

    /**
     * 파일명에서 버전 숫자 추출 (v12.json → 12)
     */
    private int extractVersion(String key) {
        try {
            String[] parts = key.split("v");
            String versionPart = parts[1].replaceAll("\\D+", ""); // 숫자만 추출
            return Integer.parseInt(versionPart);
        } catch (Exception e) {
            return -1;
        }
    }

    
}
