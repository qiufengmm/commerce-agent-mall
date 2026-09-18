package com.macro.mall.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.dto.BucketPolicyConfigDto;
import com.macro.mall.dto.MinioUploadDto;
import io.minio.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * MinIO对象存储管理Controller
 */
@Controller
@Tag(name = "MinioController", description = "MinIO对象存储管理")
@RequestMapping("/minio")
public class MinioController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinioController.class);
    @Value("${minio.endpoint}")
    private String ENDPOINT;
    /**
     * 浏览器 / 手机可访问的公开地址（可选，默认空）。
     * MinIO SDK 连接始终使用 ENDPOINT（容器网络内部地址），
     * 只有返回给前端的 URL 使用该地址，二者可以不同。
     * 未配置时回退到 ENDPOINT，兼容宿主机直接运行模式。
     *
     * 环境变量名说明：Spring Boot 宽松绑定会把 minio.publicEndpoint 折叠成
     * MINIO_PUBLICENDPOINT，因此这里再显式兜底读取下划线风格 MINIO_PUBLIC_ENDPOINT，
     * 两种写法都能生效（已用本地测试验证）。
     */
    @Value("${minio.publicEndpoint:${MINIO_PUBLIC_ENDPOINT:}}")
    private String PUBLIC_ENDPOINT;
    @Value("${minio.bucketName}")
    private String BUCKET_NAME;
    @Value("${minio.accessKey}")
    private String ACCESS_KEY;
    @Value("${minio.secretKey}")
    private String SECRET_KEY;

    /**
     * MinIO SDK 连接地址：始终使用容器网络可达的内部地址，不受公开地址影响。
     */
    String sdkEndpoint() {
        return ENDPOINT;
    }

    /**
     * 返回给前端的访问地址：优先使用公开地址，未配置时回退到内部地址。
     */
    String publicEndpoint() {
        return resolvePublicEndpoint(ENDPOINT, PUBLIC_ENDPOINT);
    }

    /**
     * 解析对外访问地址：公开地址为空 / 空白时回退到内部地址。
     */
    static String resolvePublicEndpoint(String internalEndpoint, String publicEndpoint) {
        if (publicEndpoint == null || publicEndpoint.trim().isEmpty()) {
            return internalEndpoint;
        }
        return publicEndpoint.trim();
    }

    /**
     * 拼接对象访问 URL，去掉各段首尾多余的斜杠，避免出现双斜杠。
     */
    static String buildObjectUrl(String baseEndpoint, String bucketName, String objectName) {
        String base = trimTrailingSlash(baseEndpoint);
        String bucket = trimSlashes(bucketName);
        String object = trimLeadingSlash(objectName);
        StringBuilder url = new StringBuilder(base);
        if (!bucket.isEmpty()) {
            if (url.length() > 0) {
                url.append('/');
            }
            url.append(bucket);
        }
        if (!object.isEmpty()) {
            if (url.length() > 0) {
                url.append('/');
            }
            url.append(object);
        }
        return url.toString();
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trimLeadingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private static String trimSlashes(String value) {
        return trimLeadingSlash(trimTrailingSlash(value));
    }

    @Operation(summary = "文件上传")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public CommonResult upload(@RequestPart("file") MultipartFile file) {
        try {
            //创建一个MinIO的Java客户端（内部地址，容器内可直接解析）
            MinioClient minioClient =MinioClient.builder()
                    .endpoint(sdkEndpoint())
                    .credentials(ACCESS_KEY,SECRET_KEY)
                    .build();
            boolean isExist = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET_NAME).build());
            if (isExist) {
                LOGGER.info("存储桶已经存在！");
            } else {
                //创建存储桶并设置只读权限
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_NAME).build());
                BucketPolicyConfigDto bucketPolicyConfigDto = createBucketPolicyConfigDto(BUCKET_NAME);
                SetBucketPolicyArgs setBucketPolicyArgs = SetBucketPolicyArgs.builder()
                        .bucket(BUCKET_NAME)
                        .config(JSONUtil.toJsonStr(bucketPolicyConfigDto))
                        .build();
                minioClient.setBucketPolicy(setBucketPolicyArgs);
            }
            String filename = file.getOriginalFilename();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            // 设置存储对象名称
            String objectName = sdf.format(new Date()) + "/" + filename;
            // 使用putObject上传一个文件到存储桶中
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                    .bucket(BUCKET_NAME)
                    .object(objectName)
                    .contentType(file.getContentType())
                    .stream(file.getInputStream(), file.getSize(), ObjectWriteArgs.MIN_MULTIPART_SIZE).build();
            minioClient.putObject(putObjectArgs);
            LOGGER.info("文件上传成功!");
            MinioUploadDto minioUploadDto = new MinioUploadDto();
            minioUploadDto.setName(filename);
            // 返回给前端的 URL 使用公开地址，避免浏览器拿到容器内部主机名
            minioUploadDto.setUrl(buildObjectUrl(publicEndpoint(), BUCKET_NAME, objectName));
            return CommonResult.success(minioUploadDto);
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.info("上传发生错误: {}！", e.getMessage());
        }
        return CommonResult.failed();
    }

    /**
     * 创建存储桶的访问策略，设置为只读权限
     */
    private BucketPolicyConfigDto createBucketPolicyConfigDto(String bucketName) {
        BucketPolicyConfigDto.Statement statement = BucketPolicyConfigDto.Statement.builder()
                .Effect("Allow")
                .Principal("*")
                .Action("s3:GetObject")
                .Resource("arn:aws:s3:::"+bucketName+"/*.**").build();
        return BucketPolicyConfigDto.builder()
                .Version("2012-10-17")
                .Statement(CollUtil.toList(statement))
                .build();
    }

    @Operation(summary = "文件删除")
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult delete(@RequestParam("objectName") String objectName) {
        try {
            MinioClient minioClient = MinioClient.builder()
                    .endpoint(ENDPOINT)
                    .credentials(ACCESS_KEY,SECRET_KEY)
                    .build();
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());
            return CommonResult.success(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return CommonResult.failed();
    }
}
