package com.macro.mall.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinIO 内部地址 / 公开地址分离的最小单元测试。
 *
 * 不连接 MinIO、不启动 Spring 上下文，只验证三件事：
 * 1. SDK 连接地址仍是容器内部地址；
 * 2. 返回给前端的 URL 使用公开地址；
 * 3. 未配置公开地址时回退内部地址，且不产生双斜杠。
 */
class MinioControllerUrlTest {

    private static final String INTERNAL_ENDPOINT = "http://minio:9000";
    private static final String PUBLIC_ENDPOINT = "http://localhost:9000";
    private static final String BUCKET = "mall";
    private static final String OBJECT = "20260101/demo.png";

    private MinioController controller(String internalEndpoint, String publicEndpoint) {
        MinioController controller = new MinioController();
        ReflectionTestUtils.setField(controller, "ENDPOINT", internalEndpoint);
        ReflectionTestUtils.setField(controller, "PUBLIC_ENDPOINT", publicEndpoint);
        ReflectionTestUtils.setField(controller, "BUCKET_NAME", BUCKET);
        return controller;
    }

    @Test
    void sdkEndpointKeepsInternalAddress() {
        MinioController controller = controller(INTERNAL_ENDPOINT, PUBLIC_ENDPOINT);
        assertEquals(INTERNAL_ENDPOINT, controller.sdkEndpoint());
    }

    @Test
    void sdkEndpointIgnoresPublicEndpoint() {
        MinioController controller = controller(INTERNAL_ENDPOINT, "http://192.168.1.20:9000");
        assertEquals(INTERNAL_ENDPOINT, controller.sdkEndpoint());
    }

    @Test
    void returnedUrlUsesPublicEndpoint() {
        MinioController controller = controller(INTERNAL_ENDPOINT, PUBLIC_ENDPOINT);
        assertEquals(PUBLIC_ENDPOINT, controller.publicEndpoint());

        String url = MinioController.buildObjectUrl(controller.publicEndpoint(), BUCKET, OBJECT);
        assertEquals(PUBLIC_ENDPOINT + "/" + BUCKET + "/" + OBJECT, url);
        assertTrue(url.startsWith(PUBLIC_ENDPOINT));
    }

    @Test
    void returnedUrlNeverContainsDockerServiceName() {
        MinioController controller = controller(INTERNAL_ENDPOINT, PUBLIC_ENDPOINT);
        String url = MinioController.buildObjectUrl(controller.publicEndpoint(), BUCKET, OBJECT);
        assertFalse(url.contains("minio:9000"));
        assertFalse(url.contains("http://minio"));
    }

    @Test
    void fallsBackToInternalEndpointWhenPublicEndpointBlank() {
        assertEquals(INTERNAL_ENDPOINT, MinioController.resolvePublicEndpoint(INTERNAL_ENDPOINT, null));
        assertEquals(INTERNAL_ENDPOINT, MinioController.resolvePublicEndpoint(INTERNAL_ENDPOINT, ""));
        assertEquals(INTERNAL_ENDPOINT, MinioController.resolvePublicEndpoint(INTERNAL_ENDPOINT, "   "));

        MinioController controller = controller(INTERNAL_ENDPOINT, "   ");
        assertEquals(INTERNAL_ENDPOINT, controller.publicEndpoint());
        assertEquals(INTERNAL_ENDPOINT + "/" + BUCKET + "/" + OBJECT,
                MinioController.buildObjectUrl(controller.publicEndpoint(), BUCKET, OBJECT));
    }

    @Test
    void objectUrlHasNoDoubleSlash() {
        String expected = PUBLIC_ENDPOINT + "/" + BUCKET + "/" + OBJECT;
        assertEquals(expected, MinioController.buildObjectUrl(PUBLIC_ENDPOINT, BUCKET, OBJECT));
        assertEquals(expected, MinioController.buildObjectUrl(PUBLIC_ENDPOINT + "/", BUCKET, OBJECT));
        assertEquals(expected, MinioController.buildObjectUrl(PUBLIC_ENDPOINT + "///", BUCKET, OBJECT));
        assertEquals(expected, MinioController.buildObjectUrl(PUBLIC_ENDPOINT, "/" + BUCKET + "/", "/" + OBJECT));
        assertEquals(expected, MinioController.buildObjectUrl(PUBLIC_ENDPOINT + "/", "//" + BUCKET + "//", "//" + OBJECT));
    }
}
