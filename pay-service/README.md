# pay-service：独立支付宝沙箱支付微服务

Spring Boot 3.5 / JDK 17 / 官方 `alipay-sdk-java`，嵌入式 H2 文件库，JVM `-Xmx256m`。

## 接口
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/payments` | `{orderNo, amount, subject}`，调用 `alipay.trade.precreate` 返回 `qrCode`；同一 `orderNo` 幂等，金额不同返回 409 |
| GET | `/internal/payments/{orderNo}` | 返回状态 `PENDING/PAID/CLOSED`；PENDING 时主动 `alipay.trade.query`，超时自动 `alipay.trade.close` |
| POST | `/api/alipay/notify` | 支付宝异步通知：RSA2 验签、校验 app_id / out_trade_no / total_amount / trade_status，事务提交后才返回 `success` |

### /internal/** 鉴权（HMAC-SHA256）
请求头：`X-Pay-Timestamp`（Unix 秒）、`X-Pay-Nonce`（8-64 位 `[A-Za-z0-9-]`）、`X-Pay-Signature`：
```
hex(HMAC_SHA256(PAY_INTERNAL_TOKEN, METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + BODY))
```
时间偏差 > 5 分钟或 nonce 重复一律 401。

## 本地运行
```bash
mvn test
mvn -DskipTests package
ALIPAY_APP_ID=... ALIPAY_PRIVATE_KEY=... ALIPAY_PUBLIC_KEY=... PAY_INTERNAL_TOKEN=... java -Xmx256m -jar target/pay-service-*.jar
```

## 线上部署（已完成，2026-10-31）

- **主机**：`ubuntu@124.220.12.254`（腾讯云上海）
- **域名**：`pay.研途学堂.cn`（punycode `pay.xn--ckst2fk89agjv.cn`），主域 `研途学堂.cn` 已 ICP 备案；DNS A 记录 `pay` → `124.220.12.254`（TTL 600）
- **入口**：nginx 监听 80/443；域名 HTTP → HTTPS 301；HTTPS 仅代理 `/internal/` 与 `/api/alipay/notify`，其余 404。IP 直连 `:80` 仍可访问上述路径（兼容过渡）
- **证书**：Let's Encrypt（certbot + nginx 插件），约 90 天自动续期；首次签发约 2027-01-28 过期
- **容器**：`eclipse-temurin:17-jre` + 挂载预构建 jar（`deploy/compose.yaml`），绑定 `127.0.0.1:8081`，`mem_limit: 400m`
- **通知 URL**：`https://pay.研途学堂.cn/api/alipay/notify`
- **防火墙**：需放行 TCP **80**（ACME/跳转）与 **443**

### 部署步骤（复现/迁移参考）
1. 本机 `mvn -DskipTests package`，将 `target/pay-service-*.jar` 上传为 `/home/ubuntu/pay-service/pay-service.jar`（勿在服务器跑 Maven）。
2. `cd pay-service/deploy && cp .env.example .env && chmod 600 .env`，填写密钥与 `PAY_INTERNAL_TOKEN`（`openssl rand -hex 32`），`ALIPAY_NOTIFY_URL` 用 HTTPS 域名。
3. 将本目录 `compose.yaml` 放到与 jar 同级（或改路径），`docker compose up -d`。确认 `curl 127.0.0.1:8081/internal/payments/x` 返回 401。
4. 安装 nginx + certbot：`sudo apt-get install -y nginx certbot python3-certbot-nginx`。配置见 `nginx-pay.conf`（含 HTTPS）。签发：`sudo certbot --nginx -d pay.xn--ckst2fk89agjv.cn --agree-tos --register-unsafely-without-email --non-interactive`。
5. 腾讯云安全组/防火墙放行 TCP 80、443；DNS A：`pay` → 服务器 IP。
6. 主后端：`PAYMENT_CHANNEL=remote`、`PAYMENT_REMOTE_URL=https://pay.研途学堂.cn`、`PAYMENT_REMOTE_TOKEN=<同上>`。
