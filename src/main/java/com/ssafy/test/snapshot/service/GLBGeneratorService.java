package com.ssafy.test.snapshot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import de.javagl.jgltf.impl.v2.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * DeltaDTO 리스트를 GLB 파일로 변환하는 서비스
 */
@Service
@RequiredArgsConstructor
public class GLBGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(GLBGeneratorService.class);

    // 복셀 하나의 크기 (월드 좌표계 단위)
    private static final float VOXEL_SIZE = 1.0f;

    // GLB 포맷 상수
    private static final int GLB_MAGIC = 0x46546C67;      // "glTF" in little-endian
    private static final int GLB_VERSION = 2;
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_TYPE_BIN = 0x004E4942;  // "BIN\0"

    // 복셀의 각 면을 나타내는 비트 마스크 (face culling에 사용)
    private static final int FACE_FRONT  = 0b000001;  // +Z 방향
    private static final int FACE_BACK   = 0b000010;  // -Z 방향
    private static final int FACE_RIGHT  = 0b000100;  // +X 방향
    private static final int FACE_LEFT   = 0b001000;  // -X 방향
    private static final int FACE_TOP    = 0b010000;  // +Y 방향
    private static final int FACE_BOTTOM = 0b100000;  // -Y 방향

    /**
     * 메인 진입점: Delta 리스트를 GLB 바이너리로 변환
     *
     * 처리 흐름:
     * 1. Delta → 메시 데이터 (정점, 법선, 색상, 인덱스)
     * 2. 메시 데이터 → glTF 구조체
     * 3. glTF + 바이너리 → GLB 포맷 (직접 구현)
     *
     * @param deltas 복셀 변경 정보 리스트 (위치, 색상, 표시할 면 정보 포함)
     * @return GLB 형식의 바이트 배열
     */
    public byte[] generateGLB(List<DeltaDTO> deltas) {
        try {
            log.info("GLB 생성 시작. Delta 수: {}", deltas.size());

            // 빈 입력 처리
            if (deltas == null || deltas.isEmpty()) {
                log.warn("Delta가 비어있습니다. 빈 GLB 생성");
                return createEmptyGLB();
            }

            // 1단계: 복셀 데이터 → 메시 데이터 (정점, 법선, 색상, 인덱스)
            MeshData meshData = buildMeshData(deltas);

            // 2단계: 메시 데이터 → glTF 구조체
            GlTF gltf = createGLTF(meshData);

            // 3단계: glTF + 바이너리 → GLB 포맷 (파일 I/O 없이 직접 생성)
            byte[] glbBytes = createGLBDirect(gltf, meshData);

            log.info("GLB 생성 완료. 크기: {} bytes, 정점: {}, 삼각형: {}",
                    glbBytes.length,
                    meshData.positions.size() / 3,
                    meshData.indices.size() / 3);
            return glbBytes;

        } catch (Exception e) {
            log.error("GLB 생성 실패", e);
            throw new RuntimeException("GLB 생성 중 오류 발생", e);
        }
    }

    /**
     * Delta 리스트로부터 3D 메시 데이터를 생성
     * 각 복셀을 월드 좌표로 변환하고, faceMask에 따라 필요한 면만 생성 (최적화)
     *
     * @param deltas 복셀 변경 정보 리스트
     * @return 정점 위치, 법선, 색상, 인덱스가 포함된 메시 데이터
     */
    private MeshData buildMeshData(List<DeltaDTO> deltas) {
        // 예상 크기로 초기 용량 설정 (성능 최적화)
        int estimatedFaces = deltas.size() * 3;  // 평균 3면 가정
        List<Float> positions = new ArrayList<>(estimatedFaces * 12);  // 4 vertices * 3 components
        List<Float> normals = new ArrayList<>(estimatedFaces * 12);
        List<Float> colors = new ArrayList<>(estimatedFaces * 16);    // 4 vertices * 4 components
        List<Integer> indices = new ArrayList<>(estimatedFaces * 6);   // 2 triangles * 3 indices

        int vertexOffset = 0;  // 현재 정점 인덱스 추적

        for (DeltaDTO delta : deltas) {
            // voxelId를 3D 좌표로 변환 (32x32x32 청크 가정)
            int[] coords = voxelIdToCoords(delta.voxelId());
            float x = coords[0] * VOXEL_SIZE;
            float y = coords[1] * VOXEL_SIZE;
            float z = coords[2] * VOXEL_SIZE;

            // 색상 데이터 파싱 (RGB 바이트 → 0~1 범위 float)
            float[] voxelColors = parseColors(delta);

            // faceMask에 따라 보이는 면만 메시에 추가
            vertexOffset = addVoxelFaces(
                    positions, normals, colors, indices,
                    x, y, z, delta.faceMask(), voxelColors, vertexOffset
            );
        }

        return new MeshData(positions, normals, colors, indices);
    }

    /**
     * 1D voxelId를 3D 좌표 (x, y, z)로 변환
     * 32x32x32 청크 구조를 가정: voxelId = x + y*32 + z*32*32
     *
     * @param voxelId 1차원 복셀 인덱스
     * @return [x, y, z] 좌표 배열
     */
    private int[] voxelIdToCoords(int voxelId) {
        int chunkSize = 32;
        int x = voxelId % chunkSize;
        int y = (voxelId / chunkSize) % chunkSize;
        int z = voxelId / (chunkSize * chunkSize);
        return new int[]{x, y, z};
    }

    /**
     * Delta의 색상 바이트를 정규화된 float 배열로 변환
     * RGB 각 채널 (0-255) → float (0.0-1.0)
     *
     * @param delta 복셀 정보
     * @return [r, g, b, a] 형식의 float 배열 (0.0 ~ 1.0 범위)
     */
    private float[] parseColors(DeltaDTO delta) {
        byte[] colorBytes = delta.colorBytes();

        // 색상 데이터 없을 경우 기본 회색 반환
        if (colorBytes == null || colorBytes.length == 0) {
            return new float[]{0.5f, 0.5f, 0.5f, 1.0f};
        }

        // colorSchema에 따라 파싱 (현재는 RGB1, RGB_FACES 동일 처리)
        return switch (delta.colorSchema()) {
            case RGB1, RGB_FACES -> new float[]{
                    (colorBytes[0] & 0xFF) / 255.0f,  // Red
                    (colorBytes[1] & 0xFF) / 255.0f,  // Green
                    (colorBytes[2] & 0xFF) / 255.0f,  // Blue
                    1.0f                               // Alpha (불투명)
            };
        };
    }

    /**
     * 복셀의 각 면(6면)을 faceMask에 따라 선택적으로 메시에 추가
     * Face culling 최적화: 보이지 않는 면은 생성하지 않음
     *
     * @param positions 정점 좌표 리스트 (누적)
     * @param normals 법선 벡터 리스트 (누적)
     * @param colors 색상 리스트 (누적)
     * @param indices 삼각형 인덱스 리스트 (누적)
     * @param x, y, z 복셀의 월드 좌표
     * @param faceMask 렌더링할 면을 나타내는 비트 마스크
     * @param voxelColors 복셀 색상
     * @param vertexOffset 현재 정점 인덱스 오프셋
     * @return 업데이트된 정점 오프셋
     */
    private int addVoxelFaces(List<Float> positions, List<Float> normals,
                              List<Float> colors, List<Integer> indices,
                              float x, float y, float z, int faceMask,
                              float[] voxelColors, int vertexOffset) {

        float s = VOXEL_SIZE;

        // FRONT 면 (+Z 방향): 비트 마스크 체크 후 추가
        if ((faceMask & FACE_FRONT) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y, z + s, x + s, y, z + s, x + s, y + s, z + s, x, y + s, z + s,
                    0, 0, 1, voxelColors, vertexOffset);  // 법선: (0, 0, 1)
        }

        // BACK 면 (-Z 방향)
        if ((faceMask & FACE_BACK) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x + s, y, z, x, y, z, x, y + s, z, x + s, y + s, z,
                    0, 0, -1, voxelColors, vertexOffset);  // 법선: (0, 0, -1)
        }

        // RIGHT 면 (+X 방향)
        if ((faceMask & FACE_RIGHT) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x + s, y, z, x + s, y, z + s, x + s, y + s, z + s, x + s, y + s, z,
                    1, 0, 0, voxelColors, vertexOffset);  // 법선: (1, 0, 0)
        }

        // LEFT 면 (-X 방향)
        if ((faceMask & FACE_LEFT) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y, z + s, x, y, z, x, y + s, z, x, y + s, z + s,
                    -1, 0, 0, voxelColors, vertexOffset);  // 법선: (-1, 0, 0)
        }

        // TOP 면 (+Y 방향)
        if ((faceMask & FACE_TOP) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y + s, z, x, y + s, z + s, x + s, y + s, z + s, x + s, y + s, z,
                    0, 1, 0, voxelColors, vertexOffset);  // 법선: (0, 1, 0)
        }

        // BOTTOM 면 (-Y 방향)
        if ((faceMask & FACE_BOTTOM) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y, z, x + s, y, z, x + s, y, z + s, x, y, z + s,
                    0, -1, 0, voxelColors, vertexOffset);  // 법선: (0, -1, 0)
        }

        return vertexOffset;
    }

    /**
     * 사각형(Quad)을 두 개의 삼각형으로 메시에 추가
     * 4개의 정점과 6개의 인덱스(2 삼각형) 생성
     *
     * @param positions, normals, colors, indices 메시 데이터 리스트 (누적)
     * @param x1~z4 사각형의 4개 정점 좌표
     * @param nx, ny, nz 법선 벡터 (4개 정점 모두 동일)
     * @param color 정점 색상 (RGBA)
     * @param vertexOffset 현재 정점 인덱스
     * @return 업데이트된 정점 오프셋 (vertexOffset + 4)
     */
    private int addQuad(List<Float> positions, List<Float> normals,
                        List<Float> colors, List<Integer> indices,
                        float x1, float y1, float z1, float x2, float y2, float z2,
                        float x3, float y3, float z3, float x4, float y4, float z4,
                        float nx, float ny, float nz, float[] color, int vertexOffset) {

        // 4개 정점의 위치 추가
        positions.addAll(Arrays.asList(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4));

        // 각 정점에 동일한 법선과 색상 할당
        for (int i = 0; i < 4; i++) {
            normals.addAll(Arrays.asList(nx, ny, nz));
            colors.addAll(Arrays.asList(color[0], color[1], color[2], color[3]));
        }

        // 두 개의 삼각형으로 사각형 구성
        // 삼각형 1: (v0, v1, v2)
        // 삼각형 2: (v0, v2, v3)
        indices.addAll(Arrays.asList(
                vertexOffset, vertexOffset + 1, vertexOffset + 2,
                vertexOffset, vertexOffset + 2, vertexOffset + 3
        ));

        return vertexOffset + 4;
    }

    /**
     * 메시 데이터로부터 glTF 구조체 생성
     * glTF 2.0 스펙에 따라 Scene, Node, Mesh, Accessor, BufferView, Buffer 설정
     *
     * @param meshData 정점, 법선, 색상, 인덱스 데이터
     * @return 완성된 glTF 객체
     */
    private GlTF createGLTF(MeshData meshData) {
        GlTF gltf = new GlTF();
        gltf.setAsset(createAsset());  // 버전 정보 등 메타데이터

        // Scene 설정 (노드 0 참조)
        Scene scene = new Scene();
        scene.addNodes(0);
        gltf.addScenes(scene);
        gltf.setScene(0);  // 기본 씬으로 설정

        // Node 설정 (메시 0 참조)
        Node node = new Node();
        node.setMesh(0);
        gltf.addNodes(node);

        // Mesh 및 Primitive 설정
        Mesh mesh = new Mesh();
        MeshPrimitive primitive = new MeshPrimitive();
        primitive.setMode(4);  // TRIANGLES 모드

        // Attribute 매핑 (Accessor 인덱스)
        Map<String, Integer> attributes = new HashMap<>();
        attributes.put("POSITION", 0);  // Accessor 0
        attributes.put("NORMAL", 1);    // Accessor 1
        attributes.put("COLOR_0", 2);   // Accessor 2
        primitive.setAttributes(attributes);
        primitive.setIndices(3);        // Accessor 3

        mesh.addPrimitives(primitive);
        gltf.addMeshes(mesh);

        // Accessor와 BufferView 생성
        createAccessors(gltf, meshData);
        createBufferViews(gltf, meshData);

        // Buffer 정의 (실제 데이터는 GLB 생성 시 BIN chunk에 포함됨)
        Buffer buffer = new Buffer();
        buffer.setByteLength(calculateBufferSize(meshData));
        gltf.addBuffers(buffer);

        return gltf;
    }

    /**
     * glTF Asset 메타데이터 생성
     *
     * @return glTF 버전 2.0 정보
     */
    private Asset createAsset() {
        Asset asset = new Asset();
        asset.setVersion("2.0");
        asset.setGenerator("Voxel GLB Generator v2.0");
        return asset;
    }

    /**
     * glTF Accessor 생성 (데이터 접근 방법 정의)
     * Accessor는 BufferView의 데이터를 어떻게 해석할지 정의
     *
     * - Accessor 0: POSITION (VEC3, FLOAT) + 바운딩 박스
     * - Accessor 1: NORMAL (VEC3, FLOAT)
     * - Accessor 2: COLOR_0 (VEC4, FLOAT)
     * - Accessor 3: Indices (SCALAR, UNSIGNED_INT)
     *
     * @param gltf glTF 객체
     * @param meshData 메시 데이터 (min/max 계산용)
     */
    private void createAccessors(GlTF gltf, MeshData meshData) {
        int vertexCount = meshData.positions.size() / 3;
        int indexCount = meshData.indices.size();

        // Accessor 0: POSITION (바운딩 박스 계산 포함)
        Accessor posAccessor = new Accessor();
        posAccessor.setBufferView(0);
        posAccessor.setComponentType(5126);  // FLOAT (GL_FLOAT)
        posAccessor.setCount(vertexCount);
        posAccessor.setType("VEC3");
        posAccessor.setMin(calculateMin(meshData.positions, 3));
        posAccessor.setMax(calculateMax(meshData.positions, 3));
        gltf.addAccessors(posAccessor);

        // Accessor 1: NORMAL
        Accessor normAccessor = new Accessor();
        normAccessor.setBufferView(1);
        normAccessor.setComponentType(5126);  // FLOAT
        normAccessor.setCount(vertexCount);
        normAccessor.setType("VEC3");
        gltf.addAccessors(normAccessor);

        // Accessor 2: COLOR_0
        Accessor colorAccessor = new Accessor();
        colorAccessor.setBufferView(2);
        colorAccessor.setComponentType(5126);  // FLOAT
        colorAccessor.setCount(vertexCount);
        colorAccessor.setType("VEC4");
        gltf.addAccessors(colorAccessor);

        // Accessor 3: Indices
        Accessor indAccessor = new Accessor();
        indAccessor.setBufferView(3);
        indAccessor.setComponentType(5125);  // UNSIGNED_INT (GL_UNSIGNED_INT)
        indAccessor.setCount(indexCount);
        indAccessor.setType("SCALAR");
        gltf.addAccessors(indAccessor);
    }

    /**
     * glTF BufferView 생성 (버퍼의 특정 영역 정의)
     * 각 BufferView는 Buffer의 일부 영역을 가리키며, offset과 length로 정의됨
     *
     * 메모리 레이아웃: [POSITION][NORMAL][COLOR][INDICES]
     *
     * - BufferView 0: POSITION 데이터
     * - BufferView 1: NORMAL 데이터
     * - BufferView 2: COLOR 데이터
     * - BufferView 3: Index 데이터
     *
     * @param gltf glTF 객체
     * @param meshData 메시 데이터 (크기 계산용)
     */
    private void createBufferViews(GlTF gltf, MeshData meshData) {
        int offset = 0;

        // BufferView 0: POSITION
        int posSize = meshData.positions.size() * 4;  // float = 4 bytes
        BufferView posView = new BufferView();
        posView.setBuffer(0);
        posView.setByteOffset(offset);
        posView.setByteLength(posSize);
        posView.setTarget(34962);  // ARRAY_BUFFER (GL_ARRAY_BUFFER)
        gltf.addBufferViews(posView);
        offset += posSize;

        // BufferView 1: NORMAL
        int normSize = meshData.normals.size() * 4;
        BufferView normView = new BufferView();
        normView.setBuffer(0);
        normView.setByteOffset(offset);
        normView.setByteLength(normSize);
        normView.setTarget(34962);  // ARRAY_BUFFER
        gltf.addBufferViews(normView);
        offset += normSize;

        // BufferView 2: COLOR
        int colorSize = meshData.colors.size() * 4;
        BufferView colorView = new BufferView();
        colorView.setBuffer(0);
        colorView.setByteOffset(offset);
        colorView.setByteLength(colorSize);
        colorView.setTarget(34962);  // ARRAY_BUFFER
        gltf.addBufferViews(colorView);
        offset += colorSize;

        // BufferView 3: Indices
        int indSize = meshData.indices.size() * 4;  // int = 4 bytes
        BufferView indView = new BufferView();
        indView.setBuffer(0);
        indView.setByteOffset(offset);
        indView.setByteLength(indSize);
        indView.setTarget(34963);  // ELEMENT_ARRAY_BUFFER (GL_ELEMENT_ARRAY_BUFFER)
        gltf.addBufferViews(indView);
    }

    /**
     * GLB 포맷을 직접 구현하여 바이너리 생성 (파일 I/O 없음)
     *
     * GLB 구조:
     * ┌─────────────────────────────────────────┐
     * │ 12-byte Header                          │
     * │  - magic: 0x46546C67 ("glTF")          │
     * │  - version: 2                           │
     * │  - length: total file size              │
     * ├─────────────────────────────────────────┤
     * │ JSON Chunk                              │
     * │  - chunkLength (4-byte aligned)         │
     * │  - chunkType: 0x4E4F534A ("JSON")      │
     * │  - chunkData: glTF JSON + padding       │
     * ├─────────────────────────────────────────┤
     * │ BIN Chunk                               │
     * │  - chunkLength (4-byte aligned)         │
     * │  - chunkType: 0x004E4942 ("BIN\0")     │
     * │  - chunkData: binary buffer + padding   │
     * └─────────────────────────────────────────┘
     *
     * @param gltf glTF 구조체
     * @param meshData 메시 데이터
     * @return GLB 포맷의 바이트 배열
     * @throws Exception JSON 직렬화 오류
     */
    private byte[] createGLBDirect(GlTF gltf, MeshData meshData) throws Exception {
        // 1️⃣ JSON chunk 데이터 생성
        byte[] jsonBytes = new ObjectMapper().writeValueAsBytes(gltf);
        int jsonLength = alignTo4(jsonBytes.length);  // 4-byte 정렬

        // 2️⃣ Binary chunk 데이터 생성
        ByteBuffer binBuffer = createBuffer(meshData);
        int binLength = alignTo4(binBuffer.remaining());

        // 3️⃣ 전체 GLB 크기 계산
        int headerSize = 12;
        int jsonChunkHeaderSize = 8;
        int binChunkHeaderSize = 8;
        int totalLength = headerSize + jsonChunkHeaderSize + jsonLength + binChunkHeaderSize + binLength;

        // 4️⃣ GLB 버퍼 할당 (Little Endian)
        ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);

        // 5️⃣ GLB 헤더 작성 (12 bytes)
        glb.putInt(GLB_MAGIC);      // magic: "glTF" (0x46546C67)
        glb.putInt(GLB_VERSION);    // version: 2
        glb.putInt(totalLength);    // total file length

        // 6️⃣ JSON chunk 작성
        glb.putInt(jsonLength);          // chunk length (aligned)
        glb.putInt(CHUNK_TYPE_JSON);     // chunk type: "JSON"
        glb.put(jsonBytes);              // JSON data
        // JSON padding (space characters: 0x20)
        while ((glb.position() - headerSize - jsonChunkHeaderSize) < jsonLength) {
            glb.put((byte) 0x20);
        }

        // 7️⃣ BIN chunk 작성
        glb.putInt(binLength);           // chunk length (aligned)
        glb.putInt(CHUNK_TYPE_BIN);      // chunk type: "BIN\0"
        glb.put(binBuffer);              // binary data
        // BIN padding (null bytes: 0x00)
        while ((glb.position() - headerSize - jsonChunkHeaderSize - jsonLength - binChunkHeaderSize) < binLength) {
            glb.put((byte) 0x00);
        }

        return glb.array();
    }

    /**
     * 메시 데이터를 ByteBuffer로 직렬화 (Little Endian)
     * BufferView의 offset/length에 맞춰 순서대로 데이터 배치
     *
     * 메모리 레이아웃: [POSITION][NORMAL][COLOR][INDICES]
     *
     * @param meshData 메시 데이터
     * @return 직렬화된 ByteBuffer (Little Endian)
     */
    private ByteBuffer createBuffer(MeshData meshData) {
        int bufferSize = calculateBufferSize(meshData);
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN);

        // 순서 중요: BufferView의 offset 순서와 일치해야 함
        for (Float f : meshData.positions) buffer.putFloat(f);
        for (Float f : meshData.normals) buffer.putFloat(f);
        for (Float f : meshData.colors) buffer.putFloat(f);
        for (Integer i : meshData.indices) buffer.putInt(i);

        buffer.flip();
        return buffer;
    }

    /**
     * 전체 버퍼 크기 계산 (바이트 단위)
     *
     * @param meshData 메시 데이터
     * @return 총 바이트 크기
     */
    private int calculateBufferSize(MeshData meshData) {
        return (meshData.positions.size() + meshData.normals.size() +
                meshData.colors.size()) * 4 +  // float = 4 bytes
                meshData.indices.size() * 4;    // int = 4 bytes
    }

    /**
     * 데이터의 최소값 계산 (바운딩 박스용)
     * GPU 최적화에 사용 (frustum culling, LOD 등)
     *
     * @param data float 리스트 (연속된 벡터 데이터)
     * @param componentCount 벡터 크기 (3 = VEC3)
     * @return 각 컴포넌트의 최소값 배열
     */
    private Number[] calculateMin(List<Float> data, int componentCount) {
        Number[] min = new Number[componentCount];
        Arrays.fill(min, Float.MAX_VALUE);
        for (int i = 0; i < data.size(); i++) {
            int component = i % componentCount;
            min[component] = Math.min((Float) min[component], data.get(i));
        }
        return min;
    }

    /**
     * 데이터의 최대값 계산 (바운딩 박스용)
     *
     * @param data float 리스트
     * @param componentCount 벡터 크기
     * @return 각 컴포넌트의 최대값 배열
     */
    private Number[] calculateMax(List<Float> data, int componentCount) {
        Number[] max = new Number[componentCount];
        Arrays.fill(max, Float.MIN_VALUE);
        for (int i = 0; i < data.size(); i++) {
            int component = i % componentCount;
            max[component] = Math.max((Float) max[component], data.get(i));
        }
        return max;
    }

    /**
     * 값을 4의 배수로 올림 (4-byte 정렬)
     * GLB 스펙에서 chunk 데이터는 4-byte 경계에 정렬되어야 함
     *
     * @param value 정렬할 값
     * @return 4의 배수로 올림된 값
     */
    private int alignTo4(int value) {
        return ((value + 3) / 4) * 4;
    }

    /**
     * 빈 GLB 파일 생성 (Delta가 없을 경우)
     * 최소한의 유효한 GLB 구조를 반환
     *
     * @return 빈 GLB 바이트 배열
     */
    private byte[] createEmptyGLB() throws Exception {
        // 빈 메시 데이터 생성
        MeshData emptyMesh = new MeshData(
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );

        // glTF 구조 생성 (노드는 있지만 메시 데이터 없음)
        GlTF gltf = new GlTF();
        gltf.setAsset(createAsset());

        Scene scene = new Scene();
        gltf.addScenes(scene);
        gltf.setScene(0);

        Buffer buffer = new Buffer();
        buffer.setByteLength(0);
        gltf.addBuffers(buffer);

        // 빈 GLB 생성
        byte[] jsonBytes = new ObjectMapper().writeValueAsBytes(gltf);
        int jsonLength = alignTo4(jsonBytes.length);

        int headerSize = 12;
        int jsonChunkHeaderSize = 8;
        int totalLength = headerSize + jsonChunkHeaderSize + jsonLength;

        ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);

        // GLB 헤더
        glb.putInt(GLB_MAGIC);
        glb.putInt(GLB_VERSION);
        glb.putInt(totalLength);

        // JSON chunk
        glb.putInt(jsonLength);
        glb.putInt(CHUNK_TYPE_JSON);
        glb.put(jsonBytes);

        // JSON padding
        while ((glb.position() - headerSize - jsonChunkHeaderSize) < jsonLength) {
            glb.put((byte) 0x20);
        }

        return glb.array();
    }

    /**
     * 메시 데이터 컨테이너
     *
     * @param positions 정점 좌표 (x, y, z 반복) - 전체 크기는 3의 배수
     * @param normals 법선 벡터 (nx, ny, nz 반복) - 전체 크기는 3의 배수
     * @param colors 정점 색상 (r, g, b, a 반복) - 전체 크기는 4의 배수
     * @param indices 삼각형 인덱스 (0-based) - 전체 크기는 3의 배수
     */
    private record MeshData(
            List<Float> positions,
            List<Float> normals,
            List<Float> colors,
            List<Integer> indices
    ) {}
}