package com.ssafy.test.snapshot.service;

import io.minio.*;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class S3Service {

    private static final Logger log = LoggerFactory.getLogger(S3Service.class);
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
        String key = String.format("%s/l%d/x%d/y%d/z%d/v%d.json", worldName, lod, x, y, z, version);
        log.info("조회 시도 key: {}", key);
        try {
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .build());
                String result = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                log.info("파일 조회 성공: {}", result);
                return result;

        } catch (Exception e) {
            log.info("파일 조회 실패!!!!!!!!!!!");
            throw new RuntimeException("파일 조회 실패: " + key, e);
        }
//        GetObjectRequest getRequest = GetObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .build();
//
//        return s3Client.getObjectAsBytes(getRequest).asString(StandardCharsets.UTF_8);
    }


    public String getChunkFile(String worldName, int lod, int x, int y, int z, Long version) {
        String objectName = String.format("%s/l%d/x%d/y%d/z%d/v%d.json",
                worldName, lod, x, y, z, version);
        try {
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            )) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

        } catch (Exception e) {
            throw new RuntimeException("파일 조회 실패: " + objectName, e);
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



//    public String uploadFile(String key, String jsonData) {
//        PutObjectRequest putRequest = PutObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .contentType("application/json")
//                .build();
//
//        s3Client.putObject(putRequest, RequestBody.fromString(jsonData, StandardCharsets.UTF_8));
//        return generatePresignedUrl(key);
//    }
//
//    public String uploadFile(String key, byte[] glbData) {
//        PutObjectRequest putRequest = PutObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .contentType("model/gltf-binary")
//                .build();
//
//        s3Client.putObject(putRequest, RequestBody.fromBytes(glbData));
//        return generatePresignedUrl(key);
//    }

//    private String generatePresignedUrl(String key) {
//        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
//                .bucket(bucketName)
//                .key(key)
//                .build();
//
//        PresignedGetObjectRequest presigned = presigner.presignGetObject(r ->
//                r.signatureDuration(Duration.ofMinutes(10))
//                        .getObjectRequest(getObjectRequest));
//
//        return presigned.url().toString();
//    }


}
