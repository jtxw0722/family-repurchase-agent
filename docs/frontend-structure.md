# 前端模块目录

前端继续使用原生 HTML、CSS、JavaScript，不引入框架、npm 依赖或构建打包步骤。服务入口仍为 `/repurchase-demo/index.html`。

```
repurchase-demo/
  index.html                  页面壳：品牌、导航、加载提示
  favicon.png
  css/styles.css              原页面样式，顺序及规则保持不变
  modules/
    compare.html              价格比价
    purchases.html            购买记录查询
    record.html               记录样本及订单截图预览遮罩
    normalization.html        归一化规则管理
    order-import.html         订单导入
    inventory.html            库存复购
  js/
    app.js                    HTML挂载、初始化顺序、加载失败与重试
    compare.js                比价表单、规则选择、结果展示
    purchases.js              购买记录查询及结果展示
    record.js                 样本表单、试算与保存
    record-image.js           文件选择、拖放粘贴、识别、候选及截图预览
    normalization.js          规则库、编码建议、维护及历史重算
    order-import.js           订单文件预览、编辑、勾选、确认
    inventory.js              库存、同步、周期及排序交互
    actions.js                通用提交按钮、请求分发
    navigation.js             页签切换、使用说明
    shared.js                 公共DOM、请求、格式化与表格函数
    config.js                 原有接口地址、使用说明及常量
    state.js                  跨模块页面内存状态
```

## 加载和依赖

入口通过原生 ES Module 加载 `js/app.js`。六个静态 HTML 片段首次并行获取，全部成功后才挂载 DOM、绑定事件。页签在加载完成前禁用，失败时提供明确提示和重新加载按钮。

所有模块只初始化一次；导航切换只改变原有激活状态，不重新请求模板或清空表单、结果、图片、未提交预览。截图预览遮罩仍挂载到 `body`，保持原定位和层级。

公共依赖采用显式 import/export；函数声明之间允许互相引用，但业务模块顶层不绑定DOM或发请求。跨模块可变数据由 `state.js` 保存，仍为内存状态，不写 localStorage 或 IndexedDB。不要在模块顶层新增依赖其他模块已初始化状态的调用，统一放到初始化函数或入口编排。

修改模块页面结构时编辑对应 `modules/*.html`；修改业务交互时编辑对应 `js/*.js`。共享格式化、请求处理放在 `shared.js`；接口地址在 `config.js`。本轮样式只从入口原样移出，未改变选择器、声明和层叠顺序。

## 运行与检查

- 使用原 Spring Boot 启动方式，通过HTTP访问入口；原生模块及模板请求不支持直接以 `file://` 打开HTML。
- `mvn resources:resources` 将静态文件同步至本地运行目录；发布时沿用原 Maven 打包流程。
- 入口URL可以加查询参数刷新旧入口缓存；首次模块化改用新的 `js/`、`css/`、`modules/` 资源路径。
- 本次不修改任何后端接口、购买或库存业务规则，不自动提交Git，不生成ZIP。

前端回归命令：`node --test src/test/js/*.test.cjs`。

`helpers/frontend-source.cjs` 从真实模块读取函数与模板，供既有DOM桩测试继续验证原业务断言；不保留旧版脚本副本。新增测试另外用Node原生ES模块链接器检查真实import/export依赖、循环引用、挂载时序和加载失败重试。测试进程为VM模块使用实验开关，生产浏览器无需该开关，也无新增运行依赖。

本轮本地验证：105项前端测试通过（包含工作区已有的比价、记录样本测试）；146个原业务函数只迁移位置及共享状态引用，CSS内容与顺序保持一致。浏览器检查了六个模块、比价和购买记录查询、记录样本只读试算、规则筛选、导入文件校验、库存排序，以及模块切换后的输入和结果保留。未执行购买或库存确认写操作。Java源码及接口未修改，本轮未重跑Java测试。
