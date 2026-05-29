# Family Consumption Agent OpenClaw Plugin

这是 `family-consumption-agent` 的 OpenClaw Plugin prototype。

当前实现只做工具注册、参数校验、导入路径安全校验和 HTTP 转发，不直接访问 SQLite、Repository 或 Domain Service。真实业务规则仍由 Spring Boot 后端负责。

```text
OpenClaw
  -> OpenClaw Plugin
  -> Spring Boot REST Tool API
  -> Application Service / Domain / SQLite
```

## 工具

### family_consumption_import_file

导入本地 CSV / Excel 订单文件。

参数示例：

```json
{
  "filePath": "examples/sample_orders.csv",
  "owner": "jtxw"
}
```

`owner` 可选。插件会先校验 `filePath` 是否位于白名单目录内，再转发到：

```text
POST /api/tools/import-file
```

### family_consumption_compare_price

比较当前商品价格与本地历史价格。

参数示例：

```json
{
  "productName": "猫砂",
  "price": 89,
  "quantity": 12,
  "unit": "kg"
}
```

转发到：

```text
POST /api/tools/compare-price
```

### family_consumption_generate_report

生成指定月份的 Markdown 消费分析报告。

参数示例：

```json
{
  "month": "2026-05"
}
```

转发到：

```text
POST /api/tools/generate-report
```

## Tool Result

当前 prototype 返回 JSON text，同时带上 `structuredContent`：

```json
{
  "content": [
    {
      "type": "text",
      "text": "{...}"
    }
  ],
  "structuredContent": {}
}
```

如果后续 OpenClaw runtime 对 tool result 字段有更严格约束，可以移除 `structuredContent`，保留 JSON text 返回。

## 配置

默认后端地址：

```text
http://localhost:8080
```

可以通过插件配置或环境变量覆盖：

```bash
FAMILY_AGENT_API_BASE_URL=http://localhost:8080
FAMILY_AGENT_PROJECT_ROOT=/path/to/family-consumption-agent
```

`import_file` 默认只允许导入以下目录下的 CSV / Excel 文件：

```text
examples/
data/imports/
imports/
```

校验逻辑包括：

- `projectRoot` 解析相对路径
- `importAllowedDirs` 限制导入目录
- `realpathSync` 解析真实路径，避免符号链接逃逸
- `isPathInside` 校验文件仍在白名单目录内
- 仅允许 `.csv` / `.xlsx` / `.xls`

环境变量覆盖示例：

```bash
FAMILY_AGENT_IMPORT_ALLOWED_DIRS=examples:data/imports:imports
```

Windows：

```powershell
$env:FAMILY_AGENT_IMPORT_ALLOWED_DIRS = "examples;data/imports;imports"
```

## 本地验证

安装依赖：

```bash
npm install
```

构建：

```bash
npm run build
```

静态 smoke test：

```bash
npm test
```

`smoke-test.mjs` 是 static smoke test，只检查 manifest、package 元数据、工具声明、路径安全关键逻辑和 REST 路径，不等于 OpenClaw runtime integration test。

## 后端启动

插件调用前需要先启动 Spring Boot 后端：

```bash
mvn package
java -jar target/family-consumption-agent-0.3.0-SNAPSHOT.jar
```

## 构建产物

`dist/` 是 `npm run build` 生成的构建产物，不提交到仓库。

`package.json` 中的 OpenClaw extension 指向 `./dist/index.js`，因此本地加载插件前需要先执行 `npm run build`。

## 隐私边界

插件不会：

- 登录电商平台
- 读取 Cookie
- 爬取购物网站
- 自动下单
- 上传真实订单数据

订单文件只会传给本机运行的 Spring Boot REST Tool API。
