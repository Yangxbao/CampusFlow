package com.campusflow.infra.storage;

import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.infra.property.CampusflowProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘文件存储（当前仅头像等小文件）。
 * <p>安全设计：
 * 1. 存储文件名由服务端生成（UUID + 受控扩展名），拒绝客户端文件名（防路径穿越/覆盖）；</p>
 * <p>2. 读取时严格白名单校验文件名格式，双重防目录穿越；</p>
 * <p>3. 扩展名白名单 + 魔数（文件头）校验双保险，防止把脚本伪装成图片；</p>
 * <p>4. 大小限制由 Spring multipart（2MB）与服务端二次校验共同保证。</p>
 * <p>演进：生产环境替换为 OSS/MinIO 时仅需替换本类实现（接口语义不变）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileStorageService {

    /** 图片扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /** 图片魔数（文件头字节）：JPEG/PNG/WEBP/GIF */
    private static final byte[][] IMAGE_MAGIC_BYTES = {
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},                       // JPEG
            {(byte) 0x89, 'P', 'N', 'G'},                                  // PNG
            {'R', 'I', 'F', 'F'},                                          // WEBP (RIFF....WEBP)
            {'G', 'I', 'F', '8'},                                          // GIF8
    };

    private final CampusflowProperties properties;

    private Path storageDir;

    @PostConstruct
    public void init() throws IOException {
        storageDir = Paths.get(properties.getUpload().getDir()).toAbsolutePath().normalize();
        Files.createDirectories(storageDir);
        log.info("本地文件存储就绪 dir={}", storageDir);
    }

    /**
     * 保存上传图片，返回对外访问文件名（UUID.ext）。
     */
    public String saveImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请选择要上传的图片");
        }
        long maxBytes = properties.getUpload().getMaxSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "图片不能超过 " + properties.getUpload().getMaxSizeMb() + "MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "仅支持 " + ALLOWED_EXTENSIONS + " 格式");
        }
        try {
            byte[] bytes = file.getBytes();
            checkImageMagic(bytes);
            // 服务端生成文件名：UUID 32 位十六进制 + 受控扩展名（杜绝路径穿越与覆盖）
            String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Files.write(storageDir.resolve(storedName), bytes);
            log.info("文件保存成功 name={} size={}B", storedName, bytes.length);
            return storedName;
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "文件保存失败，请稍后重试");
        }
    }

    /**
     * 读取文件（文件名必须匹配 UUID32.ext 白名单，防目录穿越）。
     */
    public LoadedFile load(String storedName) {
        if (!storedName.matches("^[a-f0-9]{32}\\.(jpg|jpeg|png|webp|gif)$")) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        try {
            Path path = storageDir.resolve(storedName).normalize();
            // 双保险：规范化后必须仍在存储目录内
            if (!path.startsWith(storageDir)) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            if (!Files.exists(path)) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            String contentType = switch (storedName.substring(storedName.lastIndexOf('.') + 1)) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                case "gif" -> "image/gif";
                default -> "application/octet-stream";
            };
            return new LoadedFile(path, contentType);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件读取失败 name={}", storedName, e);
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    /** 魔数校验：内容必须是真实图片（防止改后缀上传任意文件） */
    private void checkImageMagic(byte[] bytes) {
        if (bytes.length < 12) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "文件内容不是有效图片");
        }
        for (byte[] magic : IMAGE_MAGIC_BYTES) {
            boolean matched = true;
            for (int i = 0; i < magic.length; i++) {
                if (bytes[i] != magic[i]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return;
            }
        }
        throw BizException.of(ErrorCode.PARAM_ERROR, "文件内容不是有效图片");
    }

    public record LoadedFile(Path path, String contentType) {
    }
}
