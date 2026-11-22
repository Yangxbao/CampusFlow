package com.campusflow.biz.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.user.dto.LoginRequest;
import com.campusflow.biz.user.dto.LoginVO;
import com.campusflow.biz.user.dto.RegisterRequest;
import com.campusflow.biz.user.dto.UpdateProfileRequest;
import com.campusflow.biz.user.dto.UserVO;
import com.campusflow.biz.user.entity.RoleDO;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.entity.UserRoleDO;
import com.campusflow.biz.user.mapper.RoleMapper;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.biz.user.mapper.UserRoleMapper;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.infra.auth.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户与认证服务。
 * <p>事务边界：注册（用户 + 角色关联）在同一事务；登录只读。
 * 密码 BCrypt 存储；注册唯一性由 uk_username 兜底（并发注册靠 DuplicateKeyException 捕获）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterRequest request) {
        UserDO exists = userMapper.selectOne(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, request.getUsername()));
        if (exists != null) {
            throw BizException.of(ErrorCode.USER_EXISTS);
        }
        UserDO user = new UserDO();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(UserDO.STATUS_ACTIVE);
        user.setDeleted(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同名：唯一索引兜底
            throw BizException.of(ErrorCode.USER_EXISTS);
        }
        // 角色关联
        RoleDO role = roleMapper.selectOne(new LambdaQueryWrapper<RoleDO>()
                .eq(RoleDO::getCode, request.getRole()));
        if (role == null) {
            throw new IllegalStateException("角色字典缺失: " + request.getRole());
        }
        UserRoleDO userRole = new UserRoleDO();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);
        log.info("用户注册成功 userId={} username={} role={}", user.getId(), user.getUsername(), role.getCode());
        return toVO(user, List.of(role.getCode()));
    }

    public LoginVO login(LoginRequest request) {
        UserDO user = userMapper.selectOne(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, request.getUsername()));
        // 用户不存在与密码错误的对外文案统一（防用户名枚举攻击）
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw BizException.of(ErrorCode.PASSWORD_ERROR);
        }
        if (user.getStatus() != UserDO.STATUS_ACTIVE) {
            throw BizException.of(ErrorCode.USER_DISABLED);
        }
        List<String> roles = getRoleCodes(user.getId());
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), roles);
        return new LoginVO(token, toVO(user, roles));
    }

    public List<String> getRoleCodes(Long userId) {
        List<UserRoleDO> links = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, userId));
        if (links.isEmpty()) {
            return List.of();
        }
        return links.stream()
                .map(l -> roleMapper.selectById(l.getRoleId()))
                .filter(r -> r != null)
                .map(RoleDO::getCode)
                .toList();
    }

    public UserVO getProfile(Long userId) {
        UserDO user = requireUser(userId);
        return toVO(user, getRoleCodes(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(Long userId, UpdateProfileRequest request) {
        UserDO user = requireUser(userId);
        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }
        userMapper.updateById(user);
        return toVO(user, getRoleCodes(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        UserDO user = requireUser(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw BizException.of(ErrorCode.OLD_PASSWORD_ERROR);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        log.info("用户修改密码 userId={}", userId);
    }

    /**
     * 更新头像 URL（由 AvatarService 在文件保存成功后调用，同事务语义简单：单字段更新）。
     */
    public void updateAvatar(UserDO user, String avatarUrl) {
        user.setAvatar(avatarUrl);
        userMapper.updateById(user);
    }

    public UserDO requireUser(Long userId) {
        UserDO user = userMapper.selectById(userId);
        if (user == null || user.getDeleted() == 1) {
            throw BizException.of(ErrorCode.USER_NOT_EXIST);
        }
        return user;
    }

    public UserVO toVO(UserDO user, List<String> roles) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .build();
    }
}
