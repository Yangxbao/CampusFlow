package com.campusflow.biz.user.service;

import com.campusflow.infra.storage.LocalFileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 头像服务：文件存储 + 用户头像 URL 更新。
 * URL 统一为相对路径 /api/v1/files/{name}，由前端拼接域名（Nginx 同域下天然可用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private final LocalFileStorageService fileStorageService;
    private final UserService userService;

    /**
     * 上传并更新当前用户头像，返回可访问 URL。
     */
    public String uploadAvatar(MultipartFile file) {
        String storedName = fileStorageService.saveImage(file);
        String url = "/api/v1/files/" + storedName;
        userService.updateAvatar(userService.requireUser(
                com.campusflow.infra.auth.AuthContext.getUserId()), url);
        log.info("头像更新完成 url={}", url);
        return url;
    }
}
