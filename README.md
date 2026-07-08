# 围炉 halo-plugin-fireside

围炉——把友链的动态围进你的 `/moments` 时间线，像朋友圈一样。

站长在**链接插件**中为友链填写一个"动态 RSS 地址"，本插件定时抓取这些 RSS，把友链博主的动态存下来，并将其与你自己的**瞬间**合并成统一时间线，以 Finder 接口提供给主题——主题 `/moments` 页一行改动即可呈现"朋友圈"效果。

## 特性

- **零侵入**：不修改链接插件与瞬间插件，通过 AnnotationSetting 向链接编辑表单注入"动态 RSS 地址"字段；
- **语义无关**：填什么 RSS 就聚合什么（瞬间、文章均可），未填写的友链完全不受影响；
- **数据隔离**：友链条目存独立模型 `FriendMoment`，不污染自己的瞬间数据；
- **实时混排**：自己的瞬间实时直读（发布后刷新即见），友链条目由定时同步落库（请求路径零远程 HTTP）；
- **安全**：外部 HTML 经 jsoup 白名单清洗（防 XSS）、图片/链接绝对化、SSRF 内网地址防护、未来时间戳钳制；
- **自动清理**：按 `postLink` 去重、每友链保留最新 N 条、友链删除或取消订阅时级联清理。

## 环境要求

- Halo `>= 2.22.0`
- [链接插件 PluginLinks](https://github.com/halo-sigs/plugin-links)（必需）
- [瞬间插件 PluginMoments](https://github.com/halo-sigs/plugin-moments)（可选：未安装时时间线只含友链条目）

## 使用

1. 安装并启用本插件；
2. 在 Console「链接」中编辑某个友链，元数据区填写**动态 RSS 地址**（如对方为 Halo 且装有瞬间 + RSS 插件，一般为 `https://对方域名/feed/moments/rss.xml`；普通文章 RSS 也可以）；
3. 等待定时同步（默认每 60 分钟，可在插件设置中调整），友链动态自动入库；
4. 按下文改一行主题模板，访问 `/moments` 查看混排时间线。

### 插件设置（sync 组）

| 设置项 | 默认 | 说明 |
|---|---|---|
| 启用定时同步 | 开 | 总开关 |
| 同步间隔（分钟） | 60 | 到期判断 |
| 每轮最多处理的友链数 | 20 | |
| 每个友链保留的最新条数 | 20 | 超出删最旧 |
| 抓取超时（秒） | 10 | |

## 主题接入（一行）

插件不注册任何页面路由，`/moments` 仍由瞬间插件渲染。在主题 `templates/moments.html` 中，给包住"列表循环 + 分页"的容器元素加一个 `th:with`，用聚合结果覆盖 `moments` 变量即可——容器内原有的循环、字段访问、分页代码**原样不动**：

```html
<div th:with="moments=${firesideFinder.timeline(moments.page, moments.size)}">
  <!-- 主题原有渲染代码（th:each="moment : ${moments.items}"、
       moment.spec.content.html、moment.owner.displayName、moments.nextUrl 分页等）全部照旧 -->
</div>
```

若主题瞬间页支持 `?tag=` 过滤，使用重载 `firesideFinder.timeline(moments.page, moments.size, param.tag)`（tag 非空时仅返回自身瞬间）。

### 可选增强

友链条目带扩展字段，可加来源标识：

```html
<span th:if="${moment.external}" class="fireside-badge">
  来自 <a th:href="${moment.authorUrl}" th:text="${moment.owner.displayName}"></a>
  · <a th:href="${moment.sourceUrl}" referrerpolicy="no-referrer" target="_blank">原文</a>
</span>
```

主题也可在任意位置调用 `firesideFinder.listFriends(page, size)` 或 `firesideFinder.listFriends(page, size, linkName)` 单独展示友链动态区块。

### Finder API

| 方法 | 返回 | 说明 |
|---|---|---|
| `firesideFinder.timeline(page, size)` | `UrlContextListResult<FiresideMomentVo>` | 混排时间线，形状与瞬间插件 `moments` 变量一致（items/total/hasNext/prevUrl/nextUrl 全齐，页数按混排后 total 计算） |
| `firesideFinder.timeline(page, size, tag)` | 同上 | tag 过滤重载 |
| `firesideFinder.listFriends(page, size[, linkName])` | `ListResult<FiresideMomentVo>` | 仅友链条目 |

`FiresideMomentVo` 字段路径与瞬间插件 `MomentVo` 兼容，另附加：`external`（是否友链条目）、`sourceUrl`（原文链接）、`authorUrl`（作者站点）、`title`（RSS 条目标题，可空）。

### 已知限制（均只影响友链条目）

- 主题若用 `metadata.name` 拼 `/moments/{name}` 详情链接，友链条目点入会 404——深度适配时用 `moment.external` 判断改链 `moment.sourceUrl`；
- `stats` 恒为 0：友链条目没有本站点赞/评论数据；
- `owner.name` 是 linkName：主题若用它拼作者页链接，友链条目的链接无效；
- 插件停用期间 `firesideFinder` 不存在，主题模板会渲染报错——停用插件前请先还原主题模板改动。

## 开发

```bash
# 构建（需 JDK 21）
./gradlew build

# 本地开发（需 Docker）
./gradlew haloServer
```

构建完成后，插件 jar 位于 `build/libs`。

## 许可证

[GPL-3.0](./LICENSE) © Tim0x0
