# CraftEnginePackOptimizer

CraftEngine 资源包优化附属。将非原版资源统一放入 `minecraft`，以原命名空间作为路径前缀，减少客户端重复扫描资源包的开销。例如 `example:item/tool` 变为 `minecraft:example/item/tool`，CE 配置仍使用原 ID。

归并在 CE 原生保护之前完成，保护后的 ZIP 不再解包重打。物品、字体、声音等客户端资源 ID 由插件转译，不改变玩法物品身份。玩家无需安装 Mod，需接受资源包。

## 许可证

采用 **Apache License 2.0 + Commons Clause 1.0**，完整条款见仓库 `LICENSE`，JAR 内也附带许可证。

- 使用、修改和分发须同时遵守两部分条款，保留许可与归属信息。
- 不授予 Commons Clause 定义的“Sell”权利，包括价值全部或主要来自本插件功能的收费产品或服务。这不是纯 Apache 2.0 授权。
- 第三方依赖及资源仍遵守各自条款。

## 使用

开发基线：**Java 25、Minecraft 26.3 / Leaf、CraftEngine 26.9.2、PacketEvents 2.14.0**。需要 CE 的原生资源包保护功能；其他版本需另行验证。

1. 安装 CraftEngine、PacketEvents 和本插件，正常启动服务器。首次加载会自动生成 `plugins/CraftEnginePackOptimizer/config.yml`，无需手动建文件；已有配置不会覆盖。
2. **保留原有 CE 托管方式和参数。** 自托管、外链、S3 及其他 CE 托管器仍负责上传和提供下载链接，本插件不要求使用 S3，也不重新实现这些托管服务。
3. 默认处理 CE 的 `default` 包。如果你的包 ID 不同，将本插件配置中的 `pack: default` 改为对应 ID，再正常重启。
4. 在 CE 原有保护配置中调整以下两项，其他保护设置保留：

```yaml
resource-pack:
  protection:
    obfuscation:
      item-model:
        enable: false
      namespace:
        amount: 0
```

5. 在你现有的 CE 工作流中，把**最终带保护的 `zip` 步骤**替换为：

```yaml
- type: cepackoptimizer:protected_prefix_zip
  path: ./generated/resource_pack.zip
  store-png: false
```

保持原来的 `generate`、验证、合包、`upload` 和 `send_pack` 安排。归并步骤应在生成与合包之后、上传之前；不要在它之后再次压缩。原工作流若使用其他输出路径，保留该路径并与上传步骤一致。

6. 执行 `ce reload all` 重新生成资源包。支持自动上传的托管器上传成功后，插件才切换资源映射；失败不切换旧映射。玩家接收新包后检查模型、字体、声音与菜单。

**外链等手动上传方式：** 沿用 CE 原来的手动上传流程，不添加自动 `upload` 步骤。先生成成品并上传，填写 CE 的下载 URL 和该 ZIP 的 SHA-1，执行 `ce reload`，再运行原有发包工作流。插件在下载信息的 SHA-1 与已生成成品一致时启用映射；缺少或不匹配时拒绝发出错误资源包，不替你上传或验证第三方链接内容。生成与手动发包应分开，不在更新链接之前发包。

### 可选字体合并

需要时，在归并步骤之前加入：

```yaml
- type: cepackoptimizer:font_merge
```

它只合并兼容的 bitmap 字体 provider，保留字形、度量及优先级；有参数或字码冲突时保留原顺序。不添加此步骤就不会执行。

## 需要注意

- 原版 `minecraft` 路径保留；原 `custom` 也会变成路径前缀，资源冲突拒绝覆盖。原来的保护豁免规则需要检查是否覆盖归并后的路径。
- 路径混淆、图集合成、错误 CRC、假目录等仍由 CE 执行。普通解压工具可能因此报错，不要重新压缩“修复”成品；本插件不提供额外加密算法。
- 默认流程不删除资源、不做模型去重、overlay 扁平化或八包分发。归并需要读取资源树并改写引用，不是仅改目录名。
- 世界方块外观通过包内引用改写；不逐块转译世界或区块包。背包中的方块物品仍走物品转译。
- 当前维护一个优化包的全局映射，不支持不同玩家同时使用互不兼容的优化映射。其他命名包的托管入口保留。
- 发布状态默认保存在本插件的 `state/`。不要单独删除映射文件；多服共享同一优化包时，应配置相同的 `namespace-state-directory`，保留整个状态目录，且不要并发生成。
- **旧版升级：** 使用上方的新工作流名称，保留并指定已有完整状态目录。新版本恢复使用原 CE 托管器，不再使用旧版的附加 S3 对象路径；开放玩家连接前，先完整生成并上传一次新包，外链同步更新 URL/SHA-1。
- 本插件配置修改后正常重启；普通 `ce reload` 不重新生成资源包。优化效果取决于资源包和客户端，不承诺固定加载时间。

控制台可用 `cepackpreview status` 查看转译计数，`cepackpreview verify-host` 检查托管路由，`cepackpreview verify` 检查已发布物品映射的往返。这些检查不代替实际客户端显示验证。
