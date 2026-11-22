package com.campusflow.infra.auth;

import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.infra.property.CampusflowProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 令牌工具（HMAC-SHA256）。
 * <p>Claims 携带：sub=userId、username、roles（逗号分隔）。无状态鉴权，
 * 拦截器解析后写入 AuthContext；角色变更需重新登录生效（也可后续加黑名单）。
 */
@Slf4j
@Component
public class JwtUtils {

    private final CampusflowProperties properties;
    private volatile SecretKey key;

    public JwtUtils(CampusflowProperties properties) {
        this.properties = properties;
    }

    private SecretKey key() {
        if (key == null) {
            String secret = properties.getJwt().getSecret();
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("JWT secret 长度必须 >= 32 字节");
            }
            key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        return key;
    }

    public String generateToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getJwt().getExpireHours() * 3600_000L);
        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", String.join(",", roles))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验 token，失败抛业务异常（过期/篡改/格式错误区分日志级别，对外统一 401）。
     */
    public UserPrincipal parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .requireIssuer(properties.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            String rolesStr = claims.get("roles", String.class);
            List<String> roles = rolesStr == null || rolesStr.isEmpty()
                    ? List.of() : List.of(rolesStr.split(","));
            return new UserPrincipal(userId, username, roles);
        } catch (ExpiredJwtException e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
    }
}
