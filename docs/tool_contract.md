# Tool Contract

本文定义 Family Repurchase Agent 当前版本暴露给 Agent Host 的 **MCP tools 契约**。

Family Repurchase Agent 的标准工具出口是 Java MCP stdio Server。Claude Code、Codex、OpenClaw 等 Agent Host 应通过 MCP Server 调用工具，而不是直接访问 SQLite、Repository、Domain Service 或 Spring Boot REST endpoint。

当前 MCP tools：

* `import_file`
* `compare_price`
* `generate_report`

本文重点说明：

* MCP tool name
* inputSchema
* structuredContent
* warnings
* evidence
* Agent Host 输出要求
* 当前错误形态与后续建议错误码

Spring Boot REST Tool API 是当前 MCP Server 的后端实现映射，不作为 Agent Host 的直接调用契约。REST endpoint 可以用于本地调试和分层排障，但 Agent Host 不应依赖 REST endpoint。

---

## 1. 通用约定

### 1.1 成功结果

MCP tool 成功时返回：

* `content`：面向 LLM / 用户可读的 JSON 文本；
* `structuredContent`：MCP tool 的主结构化输出对象。当前实现中，该对象由 Spring Boot 后端计算后经 MCP Server 透传；
* `isError = false`。

Agent Host 应优先基于 `structuredContent` 理解结果，不应只解析自然语言文本。

### 1.2 错误结果

MCP tool 失败时返回：

* `content`：错误说明文本；
* `isError = true`。

当前版本的错误返回以可读错误信息为主，尚未提供稳定的结构化 `error.code` 字段。

推荐后续演进为：

```json
{
  "error": {
    "code": "file_not_found",
    "message": "filePath does not exist: examples/orders.csv"
  }
}
```

### 1.3 Agent Host 使用原则

Agent Host 应遵守以下原则：

* 不直接访问 SQLite；
* 不直接访问 Repository 或 Domain Service；
* 不绕过 Spring Boot 后端自行计算价格结论；
* 不凭模型常识判断价格是否划算；
* 判断当前价格是否值得买时，必须基于 `compare_price` 返回的 `current`、`baseline`、`decision`、`evidence` 和 `warnings`；
* 查询历史最低价、历史中位价、历史平均价或价格基准线时，必须使用 `compare_price` 且只传 `productName`，不应误用 `generate_report`。

---

## 2. Tool: `import_file`

### 2.1 作用

导入本地 CSV / Excel 订单文件，并生成购买记录和待复核记录。

该工具只读取用户主动提供的本地文件，不访问电商平台、不读取 Cookie、不爬取购物网站。

### 2.2 当前实现映射

当前 MCP Server 内部转发到 Spring Boot REST Tool API：

```text
POST /api/tools/import-file
```

### 2.3 MCP tool name

```text
import_file
```

### 2.4 输入参数

| 字段         | 类型     | 必填 | 说明                  |
| ---------- | ------ | -: | ------------------- |
| `filePath` | string |  是 | 本地 CSV / Excel 文件路径 |
| `owner`    | string |  否 | 订单归属人；为空时由后端按导入规则处理 |

示例：

```json
{
  "filePath": "examples/sample_orders.csv",
  "owner": "demo"
}
```

### 2.5 输入约束

`filePath` 必须满足：

* 非空字符串；
* 文件真实存在；
* 指向普通文件；
* 文件扩展名为 `.csv`、`.xlsx` 或 `.xls`；
* 文件位于 `FAMILY_AGENT_IMPORT_ALLOWED_DIRS` 配置的安全导入目录内。

### 2.6 structuredContent

```json
{
  "batchId": 1,
  "totalCount": 10,
  "importedCount": 8,
  "reviewCount": 2,
  "message": "导入完成"
}
```

字段说明：

| 字段              | 类型     | 说明             |
| --------------- | ------ | -------------- |
| `batchId`       | number | 导入批次 ID        |
| `totalCount`    | number | 文件中解析到的总记录数    |
| `importedCount` | number | 成功写入本地数据库的记录数  |
| `reviewCount`   | number | 导入后生成的待人工复核记录数 |
| `message`       | string | 面向调用方展示的导入结果说明 |

### 2.7 warnings

当前 `import_file` 不单独返回 `warnings` 字段。

异常样本会进入待复核流程，并通过 `reviewCount` 体现。

### 2.8 evidence

当前 `import_file` 不返回 `evidence` 字段。

导入结果只返回批次、数量和复核统计。

### 2.9 常见错误

当前版本错误结果以 `isError = true` 和文本错误信息为主，尚未提供稳定的结构化 `error.code`。下表中的错误码为后续演进建议，不属于当前稳定契约。

| 错误场景          | 当前错误信息特征                                               | 建议错误码                      |
| ------------- | ------------------------------------------------------ | -------------------------- |
| `filePath` 为空 | `filePath must be a non-empty string`                  | `invalid_input`            |
| 文件不存在         | `filePath does not exist`                              | `file_not_found`           |
| 路径解析失败        | `failed to resolve filePath`                           | `file_path_resolve_failed` |
| 文件不在允许目录      | `filePath is outside allowed import directories`       | `file_path_not_allowed`    |
| 不是普通文件        | `filePath must be a file`                              | `invalid_file_path`        |
| 文件类型不支持       | `filePath must be a CSV or Excel file`                 | `unsupported_file_type`    |
| 后端不可用         | `Failed to connect to Family Repurchase Agent backend` | `backend_unavailable`      |

---

## 3. Tool: `compare_price`

### 3.1 作用

统一价格分析工具：只传 productName 时返回历史价格基准线；同时传 price、quantity、unit 时比较当前价格与本地历史购买记录。

如果用户只是查询历史最低价、历史中位价、历史平均价或价格基准线，而没有提供当前价格，应调用 `compare_price` 且只传 `productName`。

这是当前项目用于判断“当前价格是否值得买”的核心 tool。

### 3.2 当前实现映射

当前 MCP Server 内部转发到 Spring Boot REST Tool API：

```text
POST /api/tools/compare-price
```

### 3.3 MCP tool name

```text
compare_price
```

### 3.4 输入参数

| 字段            | 类型     | 必填 | 说明                           |
| ------------- | ------ | -: | ---------------------------- |
| `productName` | string |  是 | 原始商品名称                       |
| `price`       | number |  否 | 当前总价或实付金额；必须与 quantity、unit 同时提供或同时省略 |
| `quantity`    | number |  否 | 当前数量；必须与 price、unit 同时提供或同时省略 |
| `unit`        | string |  否 | 计量单位；必须与 price、quantity 同时提供或同时省略 |

示例：

```json
{
  "productName": "膨润土猫砂",
  "price": 10.3,
  "quantity": 5,
  "unit": "kg"
}
```

### 3.5 输入约束

* `productName` 必须是非空字符串；
* `price`、`quantity`、`unit` 全部省略时进入 baseline-only 模式；
* `price`、`quantity`、`unit` 全部提供时进入 compare 模式；
* `price` 必须大于 0；
* `quantity` 必须大于 0；
* `unit` 必须是非空字符串；
* 不允许只提供 price、quantity、unit 中的一部分；半完整请求必须返回参数错误。

### 3.6 structuredContent

```json
{
  "mode": "compare",
  "productName": "膨润土猫砂",
  "normalizedName": "猫砂",
  "current": {
    "price": 10.3,
    "quantity": 5.0,
    "unit": "kg",
    "unitPrice": 2.06,
    "formula": "10.3 / 5 = 2.06"
  },
  "baseline": {
    "sampleSize": 11,
    "unit": "kg",
    "historicalMin": 2.842222222222222,
    "historicalMedian": 3.533333333333333,
    "historicalAverage": 6.458363902179692,
    "dateRange": {
      "from": "2023-11-06",
      "to": "2025-11-09"
    }
  },
  "decision": {
    "code": "good_price",
    "text": "好价",
    "reason": "当前单价 2.06 元/kg，历史最低单价 2.84 元/kg，历史中位数 3.53 元/kg，参与统计样本 11 条，本次判断为低于历史最低价或明显低于历史中位数。",
    "confidence": "medium"
  },
  "evidence": {
    "source": "local_purchase_history",
    "sourceRecords": [],
    "excludedRecordCount": 1,
    "excludedReasons": [
      "单位不一致：存在 1 条历史记录不是 kg，未参与本次价格判断。"
    ],
    "outliers": []
  },
  "warnings": [
    "存在 1 条历史记录单位不是 kg，已排除，不参与本次价格判断。"
  ]
}
```

### 3.7 字段说明

#### 顶层字段

| 字段               | 类型     | 说明        |
| ---------------- | ------ | --------- |
| `mode`           | string | `baseline_only` 或 `compare` |
| `productName`    | string | 原始商品名称    |
| `normalizedName` | string | 归一化后的商品名称 |
| `current`        | object / null | 当前价格计算结果；baseline-only 模式为 null |
| `baseline`       | object | 历史价格基准线   |
| `decision`       | object / null | 价格判断结论；baseline-only 模式为 null |
| `evidence`       | object | 支撑判断的证据   |
| `warnings`       | array  | 风险提示      |

#### `current`

| 字段          | 类型     | 说明       |
| ----------- | ------ | -------- |
| `price`     | number | 当前总价     |
| `quantity`  | number | 当前数量     |
| `unit`      | string | 当前单位     |
| `unitPrice` | number | 当前单位价格   |
| `formula`   | string | 单位价格计算公式 |

#### `baseline`

| 字段                  | 类型            | 说明          |
| ------------------- | ------------- | ----------- |
| `sampleSize`        | number        | 参与统计的历史样本数量 |
| `unit`              | string        | 历史统计统一单位    |
| `historicalMin`     | number / null | 历史最低单位价格    |
| `historicalMedian`  | number / null | 历史中位数单位价格   |
| `historicalAverage` | number / null | 历史平均单位价格    |
| `dateRange`         | object / null | 历史样本日期范围    |

`dateRange` 结构：

```json
{
  "from": "2023-11-06",
  "to": "2025-11-09"
}
```

#### `decision`

| 字段           | 类型     | 说明       |
| ------------ | ------ | -------- |
| `code`       | string | 机器可读判断编码 |
| `text`       | string | 用户可读判断文本 |
| `reason`     | string | 判断原因     |
| `confidence` | string | 判断置信度    |

当前 `decision.code`：

| code                | text | 说明                       |
| ------------------- | ---- | ------------------------ |
| `good_price`        | 好价   | 当前单位价格低于历史最低价，或明显低于历史中位数 |
| `normal_price`      | 正常价格 | 当前单位价格接近历史中位数            |
| `expensive`         | 偏贵   | 当前单位价格明显高于历史中位数          |
| `insufficient_data` | 数据不足 | 没有足够历史样本形成可靠判断           |

当前价格判断阈值为 MVP 阶段的启发式规则，后续可根据真实样本分布和用户反馈调整。默认配置如下：

- `goodPriceMedianFactor = 0.92`：当前单位价格低于或等于历史中位数的 92%，即比历史中位数低约 8% 或更多时，可判断为 `good_price`
- `expensivePriceMedianFactor = 1.12`：当前单位价格高于或等于历史中位数的 112%，即比历史中位数高约 12% 或更多时，可判断为 `expensive`

未配置时使用默认值；如需调整，可通过 `application.yml` 配置覆盖，例如：

```yaml
family-agent:
  price-decision:
    good-price-median-factor: 0.86
    expensive-price-median-factor: 1.21
```

当前 `confidence`：

| confidence | 说明                     |
| ---------- | ---------------------- |
| `low`      | 历史样本不足，或无可用历史数据        |
| `medium`   | 有可用历史样本，但当前版本暂不输出 high |

### 3.8 warnings

`warnings` 是字符串数组，用于提示价格判断的可靠性风险。

当前可能出现的 warnings：

| warning 场景 | 示例                                        |
| ---------- | ----------------------------------------- |
| 历史样本不足     | `历史记录不足 3 条，判断置信度较低。`                     |
| 平均值明显高于中位数 | `历史平均值明显高于中位数，可能存在异常值，建议优先参考历史中位数和历史最低价。` |
| 单位不一致样本被排除 | `存在 1 条历史记录单位不是 kg，已排除，不参与本次价格判断。`        |
| 无历史记录      | `历史记录不足，无法形成可靠价格判断。`                      |

Agent Host 在回答用户时必须保留 warnings 的含义，不应只输出“好价”或“偏贵”。

### 3.9 evidence

`evidence` 用于说明价格判断基于哪些历史样本。

结构：

```json
{
  "source": "local_purchase_history",
  "sourceRecords": [],
  "excludedRecordCount": 1,
  "excludedReasons": [],
  "outliers": []
}
```

字段说明：

| 字段                    | 类型     | 说明                                |
| --------------------- | ------ | --------------------------------- |
| `source`              | string | 证据来源，当前为 `local_purchase_history` |
| `sourceRecords`       | array  | 代表性历史样本                           |
| `excludedRecordCount` | number | 被排除的历史记录数量                        |
| `excludedReasons`     | array  | 排除原因                              |
| `outliers`            | array  | 异常高价样本                            |

`sourceRecords` 中的 `role`：

| role             | 说明        |
| ---------------- | --------- |
| `historical_min` | 历史最低价样本   |
| `median_sample`  | 历史中位数附近样本 |
| `latest`         | 最近购买样本    |

`outliers` 中的 `role`：

| role           | 说明             |
| -------------- | -------------- |
| `high_outlier` | 明显高于历史中位数的高价样本 |

`sourceRecord` 结构：

```json
{
  "recordId": 34,
  "role": "historical_min",
  "purchaseDate": "2025-08-07",
  "productName": "某品牌膨润土猫砂 4.5kg * 2",
  "price": 25.58,
  "quantity": 9.0,
  "unit": "kg",
  "unitPrice": 2.842222222222222,
  "unitPriceUnit": "kg",
  "originalQuantity": 9.0,
  "originalUnit": "kg"
}
```

Agent Host 可以引用 evidence 总结判断依据，但面向公开展示时应避免暴露真实订单标题、订单号、账号、地址等敏感信息。

### 3.10 常见错误

当前版本错误结果以 `isError = true` 和文本错误信息为主，尚未提供稳定的结构化 `error.code`。下表中的错误码为后续演进建议，不属于当前稳定契约。

| 错误场景             | 当前错误信息特征                                               | 建议错误码                 |
| ---------------- | ------------------------------------------------------ | --------------------- |
| `productName` 为空 | `productName must be a non-empty string`               | `invalid_input`       |
| `price` 不是正数     | `price must be a positive number`                      | `invalid_price`       |
| `quantity` 不是正数  | `quantity must be a positive number`                   | `invalid_quantity`    |
| `unit` 为空        | `unit must be a non-empty string`                      | `invalid_unit`        |
| 后端不可用            | `Failed to connect to Family Repurchase Agent backend` | `backend_unavailable` |

---

## 4. Tool: `generate_report`

### 4.1 作用

根据指定月份生成本地 Markdown 复购品价格报告。

报告文件写入本地 `reports/` 目录。

### 4.2 当前实现映射

当前 MCP Server 内部转发到 Spring Boot REST Tool API：

```text
POST /api/tools/generate-report
```

### 4.3 MCP tool name

```text
generate_report
```

### 4.4 输入参数

| 字段      | 类型     | 必填 | 说明                 |
| ------- | ------ | -: | ------------------ |
| `month` | string |  是 | 报告月份，格式为 `yyyy-MM` |

示例：

```json
{
  "month": "2026-05"
}
```

### 4.5 输入约束

* `month` 必须是非空字符串；
* 推荐格式为 `yyyy-MM`；
* 当前版本主要按月份生成本地 Markdown 报告。

### 4.6 structuredContent

示例：

```json
{
  "month": "2026-05",
  "recordCount": 18,
  "totalAmount": 1280.50,
  "pendingReviewCount": 2,
  "reportPath": "reports/2026-05.md",
  "message": "报告生成完成"
}
```

字段说明：

| 字段                   | 类型     | 说明                  |
| -------------------- | ------ | ------------------- |
| `month`              | string | 报告月份                |
| `recordCount`        | number | 纳入本次价格报告的记录数        |
| `totalAmount`        | number | 纳入统计的金额合计           |
| `pendingReviewCount` | number | 当前仍待人工复核的记录数        |
| `reportPath`         | string | 生成的 Markdown 报告文件路径 |
| `message`            | string | 面向调用方展示的报告生成结果说明 |

### 4.7 warnings

当前 `generate_report` 不单独返回 `warnings` 字段。

如有待复核记录，通过 `pendingReviewCount` 体现。

### 4.8 evidence

当前 `generate_report` 不返回 `evidence` 字段。

报告内容本身保存在 `reportPath` 指向的本地 Markdown 文件中。

### 4.9 常见错误

当前版本错误结果以 `isError = true` 和文本错误信息为主，尚未提供稳定的结构化 `error.code`。下表中的错误码为后续演进建议，不属于当前稳定契约。

| 错误场景       | 当前错误信息特征                                               | 建议错误码                  |
| ---------- | ------------------------------------------------------ | ---------------------- |
| `month` 为空 | `month must be a non-empty string`                     | `invalid_month`        |
| 月份格式非法     | 由后端报告服务或校验逻辑返回                                         | `invalid_month_format` |
| 报告写入失败     | 文件写入异常                                                 | `report_write_failed`  |
| 后端不可用      | `Failed to connect to Family Repurchase Agent backend` | `backend_unavailable`  |

---

## 5. Agent Host 输出要求

Agent Host 最终回复用户时，应遵守以下要求。

### 5.1 `compare_price` compare 场景

必须体现：

* 当前单位价格；
* 历史最低价；
* 历史中位价；
* 样本数量；
* 判断结果；
* 置信度；
* warnings；
* 必要时引用 evidence 中的代表性样本。

不应只回答：

```text
可以买。
```

推荐回答结构：

```text
当前单价为 2.06 元/kg，低于历史最低价 2.84 元/kg，也低于历史中位价 3.53 元/kg。
本次参与统计的历史样本为 11 条，判断结果为“好价”，置信度 medium。
需要注意：存在 1 条单位不一致的历史记录已被排除。
```

### 5.2 `compare_price` baseline-only 场景

必须体现：

* 归一化商品名称；
* 统计单位；
* 历史最低价；
* 历史中位价；
* 历史平均价；
* 样本数量；
* 日期范围；
* warnings；
* 必要时引用 evidence 中的代表性样本。

不应只回答：

```text
历史最低价是 2.84。
```

推荐回答结构：

```text
猫砂的历史价格基准线如下：统计单位为 kg，历史样本数为 11 条。
历史最低单价为 2.84 元/kg，历史中位单价为 3.53 元/kg，历史平均单价为 6.46 元/kg。
样本日期范围为 2023-11-06 至 2025-11-09。
需要注意：存在 1 条单位不一致的历史记录已被排除。
```

### 5.3 `import_file` 场景

必须体现：

* 文件是否导入成功；
* 总记录数；
* 成功导入记录数；
* 待复核记录数；
* 如果有待复核记录，应提醒用户后续检查。

### 5.4 `generate_report` 场景

必须体现：

* 报告月份；
* 统计记录数；
* 总金额；
* 待复核记录数；
* 报告文件路径。

---

## 当前限制

- 错误返回尚未提供稳定的结构化 `error.code`；
- `warnings` 目前是文本数组，尚未提供机器可读 warning code；
- `import_file` 和 `generate_report` 当前不返回独立 `warnings` / `evidence` 字段；
- `generate_report` 当前主要返回报告路径，暂不返回报告摘要。
- `compare_price` 在 baseline-only 模式下返回历史基准线和代表性 evidence，暂不返回完整历史明细列表。