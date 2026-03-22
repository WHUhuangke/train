# train-ticket-gateway

基于 Spring Cloud Gateway 的网关层，提供如下能力：

- 统一入口：将业务服务对外统一暴露为 `/gateway/**`
- 全局追踪：自动透传/生成 `X-Trace-Id`
- JWT 鉴权：支持 `Authorization: Bearer <token>` 校验
- 单点登录（SSO）：同一用户只保留最新会话（Redis 记录 sessionId）
- 限流：基于 Redis 的请求限流（用户维度）
- 熔断降级：票务查询与写操作均配置了 fallback
- 重试：查询接口对 500 错误自动重试 2 次
- 监控：暴露 `health/info/metrics/gateway` 端点

## 启动

```bash
cd gateway
mvn spring-boot:run
```

默认端口 `8090`，下游业务服务默认地址 `http://127.0.0.1:8080`。

## JWT+SSO 接口

### 1) 登录获取 Token

```bash
curl -X POST "http://127.0.0.1:8090/gateway/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"userId":"10001","username":"alice","password":"123456"}'
```

### 2) 携带 Bearer Token 访问业务接口

```bash
curl "http://127.0.0.1:8090/gateway/ticket/list" \
  -H "Authorization: Bearer <accessToken>"
```

### 3) 刷新 Token

```bash
curl -X POST "http://127.0.0.1:8090/gateway/auth/refresh" \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refreshToken>"}'
```

### 4) 登出

```bash
curl -X POST "http://127.0.0.1:8090/gateway/auth/logout" \
  -H "Content-Type: application/json" \
  -d '{"userId":"10001"}'
```

> 说明：示例中 `login` 仅演示网关签发 JWT + SSO 会话管理。生产环境应接入用户中心/认证服务校验用户名密码。

## 配置建议

- 请通过环境变量 `GATEWAY_JWT_SECRET` 覆盖默认密钥，且不少于 32 位。
- `gateway.jwt.expire-minutes` 控制 access token 有效期。
- `gateway.jwt.refresh-expire-minutes` 控制 refresh token 有效期。
