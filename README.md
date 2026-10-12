# FHIR 数据类型（Open Integration Engine 版）

一个用于 [Eclipse Open Integration Engine](https://github.com/OpenIntegrationEngine)（OIE，Mirth Connect 的分支）的 HL7 FHIR 数据类型：

- **FHIR JSON 和 FHIR XML 入站**：自动逐报文检测格式。
- **转换器中使用 FHIR XML**（E4X），可选择去除 FHIR 命名空间：`msg.name.family.@value`。
- **FHIR JSON 或 FHIR XML 出站**，按连接器选择。
- 使用 [HAPI FHIR](https://hapifhir.io) 针对 FHIR R4 规范及您自定义的 Profile 和包（例如 Nictiz zib2020 / nl-core）进行**验证**。检查结构、数据类型、基数、必需元素、值集绑定和约束规则。
- **无效报文**可被拒绝（状态 ERROR，问题作为错误信息）或接受并将问题存入连接器映射；按连接器配置。
- **批处理**：Bundle 可拆分为每个条目一条报文，各自独立验证。

支持 FHIR R4（4.0.1）。设计允许后续添加 R4B 和 R5 而不改变已保存的通道。

## 在线课程

[FHIR Data Type for Eclipse OIE](https://eclipse-oie.moodiy.cloud/course/view.php?id=9) 是 Eclipse OIE 学院上关于此插件的免费课程：安装和属性配置、验证、转换器和脚本中的 FHIR，以及 17 个使用测试通道和模拟报文的实操练习，最后有一个终极测试。在该站点创建账户后在课程页面点击 *Enrol me*。

## 安装

1. 从 [releases](https://github.com/matthydehoog/fhir-datatype-Eclipse-OIE/releases) 下载 `datatype-fhir-<version>.zip`。
2. 在管理器中：*Settings > Extensions > Install Extension*，或将其解压到 `<OIE_HOME>/extensions/`。
3. 重启 OIE 服务。

**内存。** 验证器会加载整个 FHIR 规范：约 300 MB 堆内存，使用大型 Profile 包时更多。为服务器分配至少 1 GB：在 `<OIE_HOME>/conf/custom.vmoptions` 中单独一行添加 `-Xmx1g`（或更多），然后重启服务。不要编辑 `oieserver.vmoptions` 或 `oieservice.vmoptions`：两者都包含 `conf/custom.vmoptions`，因此该设置对服务和 `oieserver` 均生效。部署后的首次验证需要几秒钟；插件在通道部署时开始加载，之后一条报文只需几十毫秒。

**大小。** HAPI FHIR 及其库使 zip 约 80 MB。它们位于 `extensions/datatype-fhir/lib/` 中，在插件自己的类加载器中加载，因此永远不会与 OIE 自带的库冲突。

## 属性

| 属性 | 默认值 | 功能 |
| --- | --- | --- |
| FHIR 版本 | R4 | 报文的 FHIR 版本。 |
| 去除命名空间 | 开启 | 从转换器 XML 中移除 FHIR 命名空间（`text.div` 的 XHTML 命名空间保留），处理后还原。 |
| 输出格式 | JSON | 出站报文使用 FHIR JSON 或 FHIR XML。两者均由 FHIR 引擎按规范的元素顺序写入。 |
| 格式化输出 | 开启 | 缩进 FHIR 引擎生成的 JSON 和 XML。 |
| 验证入站 | 开启 | 验证每条入站报文。 |
| 验证出站 | 关闭 | 在转换器后验证出站报文；无效的出站报文始终被拒绝。 |
| 无效报文处理 | Reject | *Reject*：状态 ERROR 并附带问题。*Accept*：正常处理，问题存入连接器映射。 |
| 无效判定级别 | Errors | *Errors*，或 *Warnings* 将警告也视为无效。 |
| Profile 包 | 空 | 服务器上存放 Profile 的文件或文件夹，用 `;` 分隔。 |
| 必需 Profile | 空 | 每条报文必须符合的规范 URL，补充其 `meta.profile`。 |
| 未知代码系统 | Warning | 验证器不认识的代码系统（SNOMED CT、LOINC 等）中代码的报告方式。 |
| 允许未知扩展 | 开启 | 接受定义未知的扩展。 |
| 术语服务器 | 空 | 可选的 FHIR 术语服务器，用于验证器离线无法检查的代码。 |
| 批处理拆分方式（批处理） | Bundle 条目 | *Bundle 条目*：每个 `Bundle.entry.resource` 为一条报文。*JavaScript*：自定义拆分器。仅在源连接器中 *Process Batch* 开启时使用；参见[批处理](#批处理)。 |

### 连接器映射

入站验证会设置以下变量，即使无效报文被接受时也会设置：

| 变量 | 值 |
| --- | --- |
| `fhirValid` | `true` 或 `false` |
| `fhirIssueCount` | 错误和警告的数量 |
| `fhirIssues` | 每个问题一行：`error Patient.gender (line 1, column 39): ...` |
| `fhirOperationOutcome` | 问题作为 FHIR `OperationOutcome` 资源（JSON），可直接返回给发送方 |
| `mirth_type` | 资源类型，例如 `Patient` 或 `Bundle` |
| `mirth_version` | `R4` |

例如，一个仅放行有效报文的源过滤器，*无效报文处理* 设为 *Accept*：

```javascript
if ($('fhirValid') == false) {
    logger.warn('Invalid FHIR message:\n' + $('fhirIssues'));
    return false;
}
return true;
```

## 批处理

在源连接器的设置中开启 **Process Batch**，将 Bundle 拆分为每个条目一条报文，例如 Observation 的 `batch` 或 `collection` Bundle。使用 *Split Batch By: Bundle Entry*（默认值）时，每个 `Bundle.entry.resource` 成为独立报文，保留原始格式（JSON 或 XML），并独立进行验证和转换。

每条报文获得以下源映射变量：

| 变量 | 值 |
| --- | --- |
| `fhirBundleType` | `Bundle.type`，例如 `batch`、`transaction` 或 `collection` |
| `fhirBundleId` | `Bundle.id`，当 Bundle 有 id 时 |
| `fhirEntryIndex` | 条目在 Bundle 中的位置：第一个为 0，如 `Bundle.entry[0]` |
| `fhirEntryCount` | Bundle 中的条目数量 |
| `fhirEntryFullUrl` | 条目的 `fullUrl`，当有条目有该值时 |
| `fhirEntryRequestMethod` | 条目的 `request.method`（`POST`、`PUT` 等），当有条目有该值时 |
| `fhirEntryRequestUrl` | 条目的 `request.url`，当有条目有该值时 |

例如在转换器中使用 `$('fhirEntryRequestMethod')`。引擎还会添加自己的 `batchSequenceId`（第一条报文为 1）。

- 没有 resource 的条目（事务中的 `DELETE`）不生成报文；`fhirEntryIndex` 仍然计算它们。
- 非 Bundle 报文保持为一条报文。无法读取的报文也是如此：验证会报告其问题。
- 条目间的引用（事务中的 `urn:uuid:`）不会被解析：每个资源按原样验证。
- *Split Batch By: JavaScript* 的工作方式与其他数据类型相同：从 `reader` 读取并返回下一条报文的脚本，或在结束时返回空。

## 响应发送方

FHIR 客户端期望收到 `OperationOutcome`，当发送了无效内容时期望 HTTP `400`。将源连接器的 **Response** 设为 *Auto-generate (After source transformer)*（或 *Before processing* / *Destinations completed*），数据类型会以 `OperationOutcome` 响应，格式为入站报文相同的 JSON 或 XML：

| 情况 | OperationOutcome | HTTP 状态 |
| --- | --- | --- |
| 作为无效被拒绝 | 每个错误和警告及其位置和行/列 | 400 |
| 接受 | 警告（以及错误，当 *无效报文处理: Accept* 时），或 "The message was accepted." | 200 |
| 被过滤 | "The message was filtered and not processed further." | 200 |
| 任何其他错误（例如在转换器中） | 错误，代码为 `exception` | 500 |

HTTP 状态和内容类型被放入通道映射。在 **HTTP Listener** 设置中使用它们：

| HTTP Listener 设置 | 值 |
| --- | --- |
| Response Status Code | `${fhirHttpStatus}` |
| Response Content Type | `${fhirContentType}`（`application/fhir+json` 或 `application/fhir+xml`） |

没有这些设置时，监听器对被拒绝的报文返回 `500` 而不是 `400`，仍然以 OperationOutcome 作为响应体。

要构建自定义响应，使用 `$('fhirOperationOutcome')`，只要入站验证运行就会被填充。

## Profile 和包

将包或文件放在服务器上，在 *Profile 包* 中输入文件夹路径，例如 `C:\fhir\profiles` 或 `/opt/oie/fhir/profiles`。文件夹会连同子文件夹一起读取。支持：

- **FHIR 包**（`.tgz`），从 [packages.fhir.org](https://packages.fhir.org) 或 [simplifier.net](https://simplifier.net) 下载。包含包所依赖的每个包（`hl7.fhir.r4.core` 已内置）。对于荷兰 Profile：`nictiz.fhir.nl.r4.nl-core` 和 `nictiz.fhir.nl.r4.zib2020`。
- **一致性资源**：JSON 或 XML 格式的 StructureDefinition、ValueSet、CodeSystem、ConceptMap 和 NamingSystem 文件。

资源根据其 `meta.profile` 中的 Profile 进行验证。设置 *必需 Profile* 可将每条报文对照一个 Profile 验证，例如 `http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient`。未知的 Profile 会报错。

Profile 按设置组合加载一次并保留在内存中；更改文件后请重启通道。

## 转换器

开启 *去除命名空间* 后，转换器处理纯 FHIR XML：

```javascript
var family = msg.name[0].family.@value.toString();
msg.gender.@value = 'female';

// A new element: FHIR XML keeps values in the value attribute.
msg.appendChild(<birthDate value="1980-01-01"/>);
```

您无需考虑 FHIR 元素顺序：转换器处理后，FHIR 引擎会按规范顺序重新写入报文（JSON 或 XML）。因此 `birthDate` 最终会排在 `gender` 后面，无论您在哪里追加它。规范中不存在的元素会被拒绝并报错，而不是被丢弃。

### 在脚本中使用

`FhirTools` 可在任何脚本中验证和转换，例如 HTTP 响应：

```javascript
var FhirTools = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTools;

var result = FhirTools.validate(response.getMessage());
if (!result.isValid()) {
    logger.warn(result.summary());
}

var json = FhirTools.toJson(msg.toString()); // transformer XML to JSON
```

`FhirTools.validate(resource, requiredProfile, profilePackages)` 对照您自己的 Profile 验证。`validate` 和 `toJson` 接受带或不带 FHIR 命名空间的 FHIR XML，因此在 *去除命名空间* 开启时 `msg.toString()` 可在转换器中使用。

### 参考列表

脚本编辑器的参考列表中有一个 **FHIR Functions** 类别，包含 16 个拖放代码片段：`FhirTools`（验证，包括针对 Profile 验证；遍历问题；XML 与 JSON 互转），入站验证变量（`fhirValid`、`fhirIssueCount`、`fhirIssues`、`fhirOperationOutcome`）及无效报文的过滤规则，以及 FHIR XML 的 E4X 模式。在 Web 管理器中，导入 [`examples/fhir-functions-code-templates.xml`](examples/fhir-functions-code-templates.xml) 即可获得。

参见 [docs/code-templates.md](docs/code-templates.md) 了解使用方法、每个模板的功能和需要调整的内容。

## 需要注意的行为

- **无转换器**：没有过滤器或转换器步骤时，报文原样通过，除非输出格式与输入不同（JSON 入 XML 出或反之）；此时会被转换。
- **未知元素**：出站报文（经过转换器后或被转换时）拒绝规范中不存在的元素，而不是静默丢弃。入站验证会首先报告它们。
- **转换器后的 XML 输出**由 FHIR 引擎重新写入，因此其元素顺序始终符合规范。XML 注释和转换器的格式不会保留；*格式化输出* 决定布局。
- **Bundle** 是一条报文，除非源连接器中 *Process Batch* 开启（参见[批处理](#批处理)）。
- **出站不接受**：无效的出站报文始终被拒绝，因为没有后续步骤来处理它。
- **DOCTYPE 被拒绝**：带有 `<!DOCTYPE ...>` 的 FHIR XML 会以 ERROR 结束（参见[安全：XML 外部实体](#安全xml-外部实体-xxe)）。

## 安全：XML 外部实体（XXE）

XXE 攻击在 XML 报文中隐藏带有实体声明的 DTD，使解析器读取本地文件（例如含密码的配置）或调用内部地址。HAPI FHIR 和 `org.hl7.fhir.core` 中发现过多个此类漏洞：[CVE-2024-45294](https://advisories.gitlab.com/pkg/maven/ca.uhn.hapi.fhir/org.hl7.fhir.utilities/CVE-2024-45294/)、[CVE-2024-51132](https://github.com/advisories/GHSA-4cf2-cxp3-rjr7)、[CVE-2024-52007](https://github.com/advisories/GHSA-gr3c-q7xf-47vh) 和 [CVE-2026-55471](https://github.com/advisories/GHSA-2f55-g35j-5jmf)。

此数据类型不受影响：

- 附带 **HAPI FHIR 8.12.1 和 `org.hl7.fhir.core` 6.9.12**，包含全部四个漏洞的修复（最后一个在 6.9.10 中修复）。
- 其自身的 XML 处理不解析实体：Bundle 拆分器不允许 DOCTYPE，HAPI 解析器拒绝未声明的实体。
- 通过每个 XML 路径（入站验证、到转换器、JSON 和 XML 输出、`FhirTools`、Bundle 拆分器）探测外部实体、参数实体和"十亿笑声"攻击，均未泄露任何信息。

作为额外防线，自 **1.4.1** 起，数据类型在任何解析之前拒绝带有 `<!DOCTYPE ...>` 的 FHIR XML，包括入站、无转换器、出站和 `FhirTools`，错误信息为 *"FHIR XML must not contain a DOCTYPE (DTD) declaration"*。FHIR 规范不允许 DTD，因此有效的 FHIR 不受影响。这也使 DOCTYPE 远离引擎在转换器中的 XML 处理。

请保持数据类型更新：新的 HAPI FHIR 版本会包含在此插件的新发布中。

## 示例

[`examples/`](examples) 包含测试报文：有效和无效的 Patient 及事务 Bundle，加上 build.fhir.org 的 27 个 Patient 示例（JSON 和 XML 格式，[`examples/build.fhir.org-R6`](examples/build.fhir.org-R6)）。这些是 FHIR R6 示例：25 个对 R4 有效，2 个因 R6 新增元素被拒绝。

## 构建

JDK 17 和 Maven。引擎 jar（`com.mirth.connect:*:4.6.0`）必须在本地 Maven 仓库中。

```bash
mvn package
```

结果为 `target/datatype-fhir-<version>.zip`。插件 jar 目标为 Java 11；FHIR 引擎需要 Java 17（HAPI FHIR 8），OIE 自带。

参见[开发指南](docs/development.md)了解如何安装引擎 jar、架构、测试和发布。

## 许可证

[Mozilla Public License 2.0](LICENSE)。HAPI FHIR 采用 Apache License 2.0 许可证。
