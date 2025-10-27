package com.ssafy.test.snapshot.service;

import com.ssafy.test.snapshot.dto.DeltaDTO;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.io.GltfModelWriter;
import de.javagl.jgltf.model.impl.DefaultGltfModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
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
    private static final float VOXEL_SIZE = 1.0f;

    // Face mask bits
    private static final int FACE_FRONT  = 0b000001;
    private static final int FACE_BACK   = 0b000010;
    private static final int FACE_RIGHT  = 0b000100;
    private static final int FACE_LEFT   = 0b001000;
    private static final int FACE_TOP    = 0b010000;
    private static final int FACE_BOTTOM = 0b100000;

    public byte[] generateGLB(List<DeltaDTO> deltas) {
        try {
            log.info("GLB 생성 시작. Delta 수: {}", deltas.size());

            if (deltas == null || deltas.isEmpty()) {
                log.warn("Delta가 비어있습니다. 빈 GLB 생성");
                return new byte[0];
            }

            MeshData meshData = buildMeshData(deltas);
            GlTF gltf = createGLTF(meshData);
            byte[] glbBytes = convertToGLB(gltf, meshData);

            log.info("GLB 생성 완료. 크기: {} bytes", glbBytes.length);
            return glbBytes;

        } catch (Exception e) {
            log.error("GLB 생성 실패", e);
            throw new RuntimeException("GLB 생성 중 오류 발생", e);
        }
    }

    private MeshData buildMeshData(List<DeltaDTO> deltas) {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> colors = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        int vertexOffset = 0;

        for (DeltaDTO delta : deltas) {
            int[] coords = voxelIdToCoords(delta.voxelId());
            float x = coords[0] * VOXEL_SIZE;
            float y = coords[1] * VOXEL_SIZE;
            float z = coords[2] * VOXEL_SIZE;

            float[] voxelColors = parseColors(delta);

            vertexOffset = addVoxelFaces(
                    positions, normals, colors, indices,
                    x, y, z, delta.faceMask(), voxelColors, vertexOffset
            );
        }

        return new MeshData(positions, normals, colors, indices);
    }

    private int[] voxelIdToCoords(int voxelId) {
        int chunkSize = 32;
        int x = voxelId % chunkSize;
        int y = (voxelId / chunkSize) % chunkSize;
        int z = voxelId / (chunkSize * chunkSize);
        return new int[]{x, y, z};
    }

    private float[] parseColors(DeltaDTO delta) {
        byte[] colorBytes = delta.colorBytes();

        if (colorBytes == null || colorBytes.length == 0) {
            return new float[]{0.5f, 0.5f, 0.5f, 1.0f};
        }

        return switch (delta.colorSchema()) {
            case RGB1, RGB_FACES -> new float[]{
                    (colorBytes[0] & 0xFF) / 255.0f,
                    (colorBytes[1] & 0xFF) / 255.0f,
                    (colorBytes[2] & 0xFF) / 255.0f,
                    1.0f
            };
        };
    }

    private int addVoxelFaces(List<Float> positions, List<Float> normals,
                              List<Float> colors, List<Integer> indices,
                              float x, float y, float z, int faceMask,
                              float[] voxelColors, int vertexOffset) {

        float s = VOXEL_SIZE;

        if ((faceMask & FACE_FRONT) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y, z + s, x + s, y, z + s, x + s, y + s, z + s, x, y + s, z + s,
                    0, 0, 1, voxelColors, vertexOffset);
        }

        if ((faceMask & FACE_BACK) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x + s, y, z, x, y, z, x, y + s, z, x + s, y + s, z,
                    0, 0, -1, voxelColors, vertexOffset);
        }

        if ((faceMask & FACE_RIGHT) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x + s, y, z, x + s, y, z + s, x + s, y + s, z + s, x + s, y + s, z,
                    1, 0, 0, voxelColors, vertexOffset);
        }

        if ((faceMask & FACE_LEFT) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y, z + s, x, y, z, x, y + s, z, x, y + s, z + s,
                    -1, 0, 0, voxelColors, vertexOffset);
        }

        if ((faceMask & FACE_TOP) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y + s, z, x, y + s, z + s, x + s, y + s, z + s, x + s, y + s, z,
                    0, 1, 0, voxelColors, vertexOffset);
        }

        if ((faceMask & FACE_BOTTOM) != 0) {
            vertexOffset = addQuad(positions, normals, colors, indices,
                    x, y, z, x + s, y, z, x + s, y, z + s, x, y, z + s,
                    0, -1, 0, voxelColors, vertexOffset);
        }

        return vertexOffset;
    }

    private int addQuad(List<Float> positions, List<Float> normals,
                        List<Float> colors, List<Integer> indices,
                        float x1, float y1, float z1, float x2, float y2, float z2,
                        float x3, float y3, float z3, float x4, float y4, float z4,
                        float nx, float ny, float nz, float[] color, int vertexOffset) {

        positions.addAll(Arrays.asList(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4));

        for (int i = 0; i < 4; i++) {
            normals.addAll(Arrays.asList(nx, ny, nz));
            colors.addAll(Arrays.asList(color[0], color[1], color[2], color[3]));
        }

        indices.addAll(Arrays.asList(
                vertexOffset, vertexOffset + 1, vertexOffset + 2,
                vertexOffset, vertexOffset + 2, vertexOffset + 3
        ));

        return vertexOffset + 4;
    }

    private GlTF createGLTF(MeshData meshData) {
        GlTF gltf = new GlTF();
        gltf.setAsset(createAsset());

        Scene scene = new Scene();
        scene.addNodes(0);
        gltf.addScenes(scene);
        gltf.setScene(0);

        Node node = new Node();
        node.setMesh(0);
        gltf.addNodes(node);

        Mesh mesh = new Mesh();
        MeshPrimitive primitive = new MeshPrimitive();
        primitive.setMode(4);

        Map<String, Integer> attributes = new HashMap<>();
        attributes.put("POSITION", 0);
        attributes.put("NORMAL", 1);
        attributes.put("COLOR_0", 2);
        primitive.setAttributes(attributes);
        primitive.setIndices(3);

        mesh.addPrimitives(primitive);
        gltf.addMeshes(mesh);

        createAccessors(gltf, meshData);
        createBufferViews(gltf, meshData);

        Buffer buffer = new Buffer();
        buffer.setByteLength(calculateBufferSize(meshData));
        gltf.addBuffers(buffer);

        return gltf;
    }

    private Asset createAsset() {
        Asset asset = new Asset();
        asset.setVersion("2.0");
        asset.setGenerator("Voxel GLB Generator");
        return asset;
    }

    private void createAccessors(GlTF gltf, MeshData meshData) {
        int vertexCount = meshData.positions.size() / 3;
        int indexCount = meshData.indices.size();

        Accessor posAccessor = new Accessor();
        posAccessor.setBufferView(0);
        posAccessor.setComponentType(5126);
        posAccessor.setCount(vertexCount);
        posAccessor.setType("VEC3");
        posAccessor.setMin(calculateMin(meshData.positions, 3));
        posAccessor.setMax(calculateMax(meshData.positions, 3));
        gltf.addAccessors(posAccessor);

        Accessor normAccessor = new Accessor();
        normAccessor.setBufferView(1);
        normAccessor.setComponentType(5126);
        normAccessor.setCount(vertexCount);
        normAccessor.setType("VEC3");
        gltf.addAccessors(normAccessor);

        Accessor colorAccessor = new Accessor();
        colorAccessor.setBufferView(2);
        colorAccessor.setComponentType(5126);
        colorAccessor.setCount(vertexCount);
        colorAccessor.setType("VEC4");
        gltf.addAccessors(colorAccessor);

        Accessor indAccessor = new Accessor();
        indAccessor.setBufferView(3);
        indAccessor.setComponentType(5125);
        indAccessor.setCount(indexCount);
        indAccessor.setType("SCALAR");
        gltf.addAccessors(indAccessor);
    }

    private void createBufferViews(GlTF gltf, MeshData meshData) {
        int offset = 0;

        int posSize = meshData.positions.size() * 4;
        BufferView posView = new BufferView();
        posView.setBuffer(0);
        posView.setByteOffset(offset);
        posView.setByteLength(posSize);
        posView.setTarget(34962);
        gltf.addBufferViews(posView);
        offset += posSize;

        int normSize = meshData.normals.size() * 4;
        BufferView normView = new BufferView();
        normView.setBuffer(0);
        normView.setByteOffset(offset);
        normView.setByteLength(normSize);
        normView.setTarget(34962);
        gltf.addBufferViews(normView);
        offset += normSize;

        int colorSize = meshData.colors.size() * 4;
        BufferView colorView = new BufferView();
        colorView.setBuffer(0);
        colorView.setByteOffset(offset);
        colorView.setByteLength(colorSize);
        colorView.setTarget(34962);
        gltf.addBufferViews(colorView);
        offset += colorSize;

        int indSize = meshData.indices.size() * 4;
        BufferView indView = new BufferView();
        indView.setBuffer(0);
        indView.setByteOffset(offset);
        indView.setByteLength(indSize);
        indView.setTarget(34963);
        gltf.addBufferViews(indView);
    }

    private byte[] convertToGLB(GlTF gltf, MeshData meshData) throws Exception {
        ByteBuffer buffer = createBuffer(meshData);

        DefaultGltfModel model = new DefaultGltfModel();
        model.setGltf(gltf);
        model.getBinaryBuffers().add(buffer);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GltfModelWriter writer = new GltfModelWriter();
        writer.writeBinary(model, outputStream);

        return outputStream.toByteArray();
    }

    private ByteBuffer createBuffer(MeshData meshData) {
        int bufferSize = calculateBufferSize(meshData);
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN);

        for (Float f : meshData.positions) buffer.putFloat(f);
        for (Float f : meshData.normals) buffer.putFloat(f);
        for (Float f : meshData.colors) buffer.putFloat(f);
        for (Integer i : meshData.indices) buffer.putInt(i);

        buffer.flip();
        return buffer;
    }

    private int calculateBufferSize(MeshData meshData) {
        return (meshData.positions.size() + meshData.normals.size() +
                meshData.colors.size()) * 4 + meshData.indices.size() * 4;
    }

    private Number[] calculateMin(List<Float> data, int componentCount) {
        Number[] min = new Number[componentCount];
        Arrays.fill(min, Float.MAX_VALUE);
        for (int i = 0; i < data.size(); i++) {
            min[i % componentCount] = Math.min((Float) min[i % componentCount], data.get(i));
        }
        return min;
    }

    private Number[] calculateMax(List<Float> data, int componentCount) {
        Number[] max = new Number[componentCount];
        Arrays.fill(max, Float.MIN_VALUE);
        for (int i = 0; i < data.size(); i++) {
            max[i % componentCount] = Math.max((Float) max[i % componentCount], data.get(i));
        }
        return max;
    }

    private record MeshData(
            List<Float> positions,
            List<Float> normals,
            List<Float> colors,
            List<Integer> indices
    ) {}
}