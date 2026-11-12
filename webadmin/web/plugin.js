// FHIR 数据类型 - Web 管理器的属性面板（对应 Swing
// DataTypeClientPlugin 属性）。与内置数据类型插件结构相同。
const PKG = "com.mirth.connect.plugins.datatypes.fhir";

const bool = (key, label, def, hint) => ({ key, label, type: "checkbox", default: def, hint });
const text = (key, label, def, hint) => ({ key, label, type: "text", default: def, hint });
const opt = (key, label, values, def, hint) => ({ key, label, type: "select", options: values.map((value) => ({ value, label: value })), default: def, hint });
const code = (key, label, def, hint) => ({ key, label, type: "code", default: def, hint });

const BATCH_SCRIPT_HINT =
  "用于拆分批处理并返回下一条报文的 JavaScript 脚本。可访问 'reader'（一个 Java BufferedReader）；返回 null 或空值表示输入结束。仅在连接器启用“处理批处理”时使用。";

const DEF = {
  name: "FHIR",
  label: "FHIR",
  order: 73,
  propertiesClass: `${PKG}.FhirDataTypeProperties`,
  groups: [
    {
      key: "serializationProperties",
      label: "序列化",
      class: `${PKG}.FhirSerializationProperties`,
      fields: [
        opt("fhirVersion", "FHIR 版本", ["R4", "R4B", "R5"], "R4", "报文的 FHIR 版本：R4（4.0.1）、R4B（4.3.0）或 R5（5.0.0）。用于验证以及 JSON 和 XML 之间的转换。Profile 包必须为相同版本。"),
        text("resourceTypes", "资源类型", "", "此连接器接受的资源类型，用逗号分隔，例如 Patient、Observation、Bundle。其他类型的报文，或包含其他类型条目的 Bundle，将被判定为无效：由“无效报文处理”决定拒绝还是接受。名称区分大小写。留空则接受所有类型。这不会减少验证器的内存占用。"),
        opt("transformerFormat", "转换器格式", ["XML", "JSON"], "XML", "XML：转换器使用 E4X 处理 FHIR XML（msg.name.family.@value）。JSON：msg 是 FHIR JSON 的 JavaScript 对象（msg.name[0].family），出站报文由它生成。使用 JSON 时，小数会丢失末尾的零（1.50 变为 1.5）。在出站属性中，它设置出站模板（tmp）的格式。"),
        bool("stripNamespaces", "去除命名空间", true, "如果勾选，FHIR 命名空间（http://hl7.org/fhir）将从转换器处理的 XML 中移除，使您可以编写 msg.name.family.@value。报文离开转换器时命名空间会被还原。叙事文本（text.div）的 XHTML 命名空间会被保留。"),
        opt("outputFormat", "输出格式", ["JSON", "XML"], "JSON", "出站报文的格式（编码数据）：FHIR JSON 或 FHIR XML。入站报文可以是任意格式；转换器始终使用 FHIR XML。经过转换器后，FHIR 引擎按规范的元素顺序写入报文。"),
        bool("prettyPrint", "格式化输出", true, "如果勾选，FHIR 引擎生成的出站 JSON 和 XML 将被缩进格式化。"),
        bool("validateInbound", "验证入站", true, "如果勾选，每条入站报文都将根据 FHIR 规范和下方配置的 Profile 进行验证：结构、数据类型、基数、必需元素、值集绑定和约束规则。"),
        bool("validateOutbound", "验证出站", false, "如果勾选，出站报文在转换器处理后也将被验证。无效的出站报文始终会被拒绝。"),
        opt("invalidMessages", "无效报文处理", ["Reject", "Accept"], "Reject", "Reject：无效的入站报文将被标记为 ERROR 状态，验证问题作为错误信息。Accept：报文正常处理，连接器映射中将包含 fhirValid（true/false）、fhirIssueCount 和 fhirIssues（问题文本）。"),
        opt("failOn", "无效判定级别", ["Errors", "Warnings"], "Errors", "Errors：验证发现错误时报文无效。Warnings：警告也会使报文被判定为无效。"),
        text("profiles", "Profile 包", "", "服务器上存放 Profile 的文件或文件夹路径，用 ';' 分隔。支持 FHIR 包（.tgz，例如 nictiz.fhir.nl.r4.zib2020 及其依赖项）以及 StructureDefinition、ValueSet 和 CodeSystem 文件（.json 或 .xml）。报文根据其 meta.profile 中的 Profile 进行验证。"),
        text("requiredProfile", "必需 Profile", "", "每条报文必须符合的 Profile 规范 URL，无论其 meta.profile 如何声明。留空则仅使用 meta.profile。"),
        opt("unknownCodeSystems", "未知代码系统", ["Warning", "Error", "Information"], "Warning", "当验证器遇到不认识的代码系统（如 SNOMED CT 或 LOINC）中的代码时的报告方式。"),
        bool("anyExtensionsAllowed", "允许未知扩展", true, "如果勾选，验证器不认识的扩展定义将被接受。否则，它们将被视为错误。"),
        text("terminologyServer", "术语服务器", "", "可选的 FHIR 术语服务器基础 URL，用于检查验证器自身无法验证的代码。留空则离线验证。")
      ]
    },
    {
      key: "batchProperties",
      label: "批处理",
      class: `${PKG}.FhirBatchProperties`,
      fields: [
        {
          key: "splitType",
          label: "批处理拆分方式",
          type: "select",
          options: [
            { value: "Bundle_Entry", label: "Bundle 条目" },
            { value: "JavaScript", label: "JavaScript" }
          ],
          default: "Bundle_Entry",
          hint: "Bundle 条目：每个 Bundle.entry.resource 成为独立报文，保留原始格式（JSON 或 XML）。源映射中将包含 fhirBundleType、fhirBundleId、fhirEntryIndex（第一个条目为 0）、fhirEntryCount、fhirEntryFullUrl、fhirEntryRequestMethod 和 fhirEntryRequestUrl。没有 resource 的条目将被跳过；非 Bundle 报文保持为一条报文。仅在连接器启用“处理批处理”时使用。"
        },
        code("batchScript", "JavaScript", null, BATCH_SCRIPT_HINT)
      ]
    }
  ]
};

DEF.defaults = (version) => {
  const props = { "@class": DEF.propertiesClass, "@version": version };
  for (const group of DEF.groups) {
    const obj = { "@class": group.class, "@version": version };
    for (const f of group.fields) obj[f.key] = f.default ?? null;
    props[group.key] = obj;
  }
  return props;
};

export function register(platform) {
  platform.registerDataType(DEF.name, DEF);
}
