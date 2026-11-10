# CraftEnginePackOptimizer

CraftEngine 资源包优化附属。把非原版资源归入 `minecraft`，将原命名空间作为路径前缀，减少客户端按命名空间重复扫描 ZIP 的开销。服务端通过 PacketEvents 和 CraftEngine 物品接口转译客户端资源 ID，普通玩家无需安装 Mod，需接受服务器资源包。

归并在 CraftEngine 原生保护之前完成，最终保护 ZIP 原样上传，不解包重打。不保证固定加载时间，实际效果取决于资源包和客户端。

## 许可证

采用 [Apache License 2.0 + Commons Clause 1.0](LICENSE)，两部分须同时遵守：

- 允许遵守条款后使用、修改和免费分发；分发时保留许可与归属信息并标明修改。
- 不授予 Commons Clause 定义的“Sell”权利：收费产品或服务的价值全部或主要来自本插件功能时，不在授权范围内，涵盖符合该定义的托管、咨询和支持服务。
- 这不是纯 Apache 2.0 授权，不能笼统理解为所有盈利服务器或收费服务都可用。
- 第三方依赖和资源仍遵守各自条款。完整授权以 `LICENSE` 为准。

## 环境

- Java 25。
- 开发基线：Minecraft 26.3、Leaf API `26.3.build.2-alpha`、CraftEngine `26.9.2`、PacketEvents `2.14.0`。
- 必需插件：CraftEngine、PacketEvents。依赖 CraftEngine 内部托管字段及物品接口，其他版本不能仅凭 `api-version` 判断兼容。
- 当前正式链路使用 CraftEngine 命名包 `default` 的 S3 托管：HTTPS、虚拟主机寻址、显式凭据；不支持 path-style、环境变量凭据或 CDN 配置。

## 构建

安装 Bash、JDK 25；运行测试另需 Python 3。将依赖 JAR 放入忽略提交的 `libs/` 目录：

| 依赖 | 构建基线 |
| --- | --- |
| Leaf API | 26.3.build.2-alpha |
| CraftEngine Paper 插件 | 26.9.2 |
| PacketEvents Spigot 插件 | 2.14.0 |
| Gson | 2.13.2 |
| Adventure API、Adventure Key | 5.2.0 |
| JetBrains Annotations | 26.0.2 |
| BungeeCord Chat | 1.21-R0.2-deprecated+build.21 |
| Guava | 33.6.0-jre |
| Netty buffer、common、transport、resolver | 4.2.17.Final |
| Fastutil（测试） | 8.5.18 |

从对应项目发行版或服务端依赖目录取得 JAR，不要放入多个冲突版本。依赖不会打入本插件。

```sh
bash build.sh
bash build.sh test
```

也可通过 `CEPO_CLASSPATH` 指定完整 Java classpath，代替 `libs/*`：

```sh
CEPO_CLASSPATH='/path/to/dependencies/*' bash build.sh test
```

产物为 `build/dist/CraftEnginePackOptimizer-<version>.jar`，版本读取 `src/main/resources/plugin.yml`，JAR 内含完整许可证。构建会清理旧编译类，不清理已生成的其他版本 JAR。

测试使用合成资源和真实 PacketEvents 编解码器；可选追加本机保护绑定 JSON 与 Dialog 数据包路径，执行额外往返检查：

```sh
bash build.sh test /path/to/protected-binding.json /path/to/show-dialog.bin
```

这些可选文件不随源码分发。离线测试不等于客户端渲染、跨服通知或性能验收。

## 安装与配置

1. 备份已有插件配置、资源包及发布状态，正常停服后安装 JAR，不要同时保留多个版本。
2. 编辑 `plugins/CraftEnginePackOptimizer/config.yml`，确认发布状态目录。首次安装可在启动前手动建立该文件。
3. 调整 CraftEngine 的保护与工作流配置，再启动服务器。插件只在内存中接管 `default` S3，不改写 CE 配置或源资源目录。
4. 执行 `ce reload all` 完整生成、保护、上传并发包。普通 `ce reload` 不生成资源包。
5. 玩家重新接收资源包，分别检查模型、字体、声音、装备与菜单。升级 JAR 或修改附属配置后需正常重启，不使用热卸载。

附属默认配置：

```yaml
namespace-state-directory: state

# 仅旧 sicilia:slim 工具使用，不影响 protected_prefix_zip。
flatten-exact-version-overlays: true
keep: []
```

`namespace-state-directory` 相对本插件数据目录解析，默认是 `plugins/CraftEnginePackOptimizer/state/`；也可填写绝对路径。保存 `current.json`、`protected-binding.json` 和 `objects/`，不要只迁移某一个文件。多服使用同一生成 ZIP 时，各服必须指向同一个共享状态目录，且不得并发生成。

**从旧版升级时，先显式填写现有状态目录的绝对路径，再启动 1.6.1。** 本版不自动寻找或搬迁旧目录。路径留错会无法恢复旧映射；无发布清单时下载入口回退 CE 原 S3，不代表旧优化包仍然可用。

### CraftEngine 保护设置

在 CE 配置的原有对应字段上修改，不覆盖其他保护设置：

```yaml
resource-pack:
  protection:
    obfuscation:
      item-model:
        enable: false
      namespace:
        amount: 0
```

必须关闭额外命名空间混淆及 CE 原生 item-model 混淆。路径混淆、图集合成、错误 CRC、假目录等仍由 CE 按原配置执行；本插件不额外提供加密算法。

已有保护豁免规则若引用非原版路径，应同时检查归并后的前缀路径。例如 `example:item/tool` 变为 `minecraft:example/item/tool`，具体豁免字段仍按 CE 对应资源类型填写。

### CraftEngine 工作流

把最终带保护的 `zip` 步骤替换为 `sicilia:protected_prefix_zip`，放在生成、合包及验证之后，上传之前。不要在它之后再次 ZIP，也不要并用旧 `namespace_merge`、`namespace_bind` 或 `namespace_strict` 流程。最小示例：

```yaml
resource-pack:
  workflows:
    default:
      trigger: reload_pack
      steps:
        - type: generate
        - type: validate
        - type: sicilia:protected_prefix_zip
          path: ./generated/resource_pack.zip
          store-png: false
        - type: upload
          pack: default
          path: ./generated/resource_pack.zip
        - type: send_pack
          pack: default
    upload:
      steps:
        - type: upload
          pack: default
          path: ./generated/resource_pack.zip
        - type: send_pack
          pack: default
```

示例只展示工作流，需保留自己已有的 `resource-pack.packs.default` S3 参数及其他设置。已有 CE 优化、合包、`load_zip` 和二次验证步骤按需要保留在最终保护步骤之前。

上传需要生成时写出的匹配绑定，不接受任意 ZIP。上传及校验成功后才切换发布清单和资源映射；重试现有包可用 `ce workflow upload`。保护 ZIP 的错误 CRC 和非空假目录可能使普通解压检查报错，不要用重新压缩来“修复”成品。

## 资源与转译边界

- 非原版 `example:item/tool` 归并为 `minecraft:example/item/tool`，原 `custom` 也按同样规则处理；原有 `minecraft` 路径保留，冲突拒绝而非覆盖。
- 按资源类型处理物品模型、字体、声音、装备、图集 sprite 及相关展示字段；需要入站还原的物品使用逆映射。
- 不全局替换命令、文本、`custom_data` 或存档中的玩法身份。CE 配置仍使用原 ID，无需批量改其他插件调用方。
- 世界方块和区块包不由本附属逐块转译；方块外观依靠包内 blockstate → model → texture 引用改写。背包中的方块物品仍按物品处理。
- `SHOW_DIALOG` 使用可信服务端出站 NBT 读取，避免大型 Dialog 被默认读取限额拒绝；不放宽客户端入站 NBT 限制。
- 归并仍会读取资源树、改写引用并生成映射，不是仅改目录名。默认链路不启用旧资源删除、模型去重、overlay 扁平化或八包分发。

可选 `sicilia:font_merge` 在归并前合并兼容的 bitmap 字体 provider，保留字形、度量及优先级；不兼容的参数或字码冲突保留原顺序。它需显式加入工作流，不会因安装附属自动启用。

## 检查与旧工具

控制台命令：

```text
cepackpreview status
cepackpreview verify-host
cepackpreview verify
```

分别查看映射包/失败计数、验证命名及分组托管路由、检查 CE 原生物品发送与回收的映射往返。`verify` 需要已经发布非原版物品映射。

`cepackpreview start|stop <在线玩家>` 是旧单人预览工具，读取插件数据目录的 `strict-preview.json` 与 `strict-preview.zip`，不上传或自动发包，普通安装无需使用。

源码保留旧离线精简、双命名空间和分包工具用于对照，不是当前推荐发布方式。`OptimizerCli`、`NamespaceCompatibility` 的命令行可在报告路径后追加 `keep` glob；所有离线改写只应针对独立副本。工具会拒绝带 `server.properties` 的服务端目录及其子目录，但这不能代替人工确认输入副本。
