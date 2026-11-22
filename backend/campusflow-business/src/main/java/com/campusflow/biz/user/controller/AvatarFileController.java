package com.campusflow.biz.user.controller;

import com.campusflow.biz.user.service.AvatarService;
import com.campusflow.common.result.ApiResult;
import com.campusflow.infra.storage.LocalFileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

/**
 * 头像上传与访问接口。
 * <p>上传：登录用户（multipart/form-data，字段名 file，<=2MB，仅图片，魔数校验）。
 * <p>访问：公开 GET（文件名为服务端 UUID，不可猜测；生产环境建议加 CDN/签名 URL）。
 */
@Tag(name = "02-个人信息-头像")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AvatarFileController {

    private final LocalFileStorageService fileStorageService;
    private final AvatarService avatarService;

    @Operation(summary = "上传头像（返回可访问 URL）")
    @PostMapping("/my/profile/avatar")
    public ApiResult<String> upload(@RequestPart("file") MultipartFile file) {
        return ApiResult.ok(avatarService.uploadAvatar(file));
    }

    @Operation(summary = "获取头像文件（公开访问，7 天浏览器缓存）")
    @GetMapping("/files/{filename}")
    public ResponseEntity<FileSystemResource> file(@PathVariable String filename) {
        LocalFileStorageService.LoadedFile loaded = fileStorageService.load(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(loaded.contentType()))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(new FileSystemResource(loaded.path()));
    }
}
