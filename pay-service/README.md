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

## 部署（124.220.12.254，未执行）
1. `git clone` 仓库到 `/opt/GraduationProject`（或仅上传 `pay-service/`）。
2. `cd pay-service/deploy && cp .env.example .env && chmod 600 .env`，填写密钥与 `PAY_INTERNAL_TOKEN`（`openssl rand -hex 32`）。
3. `docker compose up -d --build`，确认 `curl -s -o /dev/null -w '%{http_code}' 127.0.0.1:8081/internal/payments/x` 返回 401。
4. `sudo apt-get install -y nginx`，`sudo cp nginx-pay.conf /etc/nginx/conf.d/pay-service.conf`，删除 `/etc/nginx/sites-enabled/default`，`sudo nginx -t && sudo systemctl reload nginx`。
5. 腾讯云安全组放行 TCP 80；验证 `curl http://124.220.12.254/` 为 404、`/api/alipay/notify` 返回 `failure`。
6. 主后端配置 `PAYMENT_CHANNEL=remote`、`PAYMENT_REMOTE_URL=http://124.220.12.254`、`PAYMENT_REMOTE_TOKEN=<同上>`。
