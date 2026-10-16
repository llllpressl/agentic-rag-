# RustFS 知识库文档存储

所有知识库的原始文档都存入 `rag-text-file`，PDF、Word、TXT 等文件均可上传。
桶里的对象示例：

```text
rag-text-file
  ├── java-guide/3a91...uuid....pdf
  ├── java-guide/701b...uuid....docx
  └── product-docs/51f0...uuid....txt
```

这里的 `collection/` 是对象 key 前缀，不是真正的文件夹。不需要在创建知识库时调用“创建文件夹”。
`collection` 取自 `KnowledgeBaseDO.getCollection()`，不是知识库 ID 或显示名称。
存储层要求它由 1 到 64 个字母、数字、下划线或短横线组成，长度上限与数据库字段一致。
原始文件名作为文档业务记录的 `docName` 保存；随机文件名避免同名文件互相覆盖。

## 连接配置

`rag/src/main/resources/application.yaml` 中的 `storage.rustfs` 配置会绑定到
`RustFsProperties`，`S3Configuration` 创建可注入的 `S3Client`。

默认连接 `http://localhost:9000`，使用原配置中的本地测试凭证。
可通过 `RUSTFS_ENDPOINT`、`RUSTFS_REGION`、`RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY`
环境变量覆盖。API 端口是 9000，控制台端口是 9001。

首次上传时会检查桶，不存在则创建。账户需要对应的桶检查、建桶、对象读写删除权限；
如果预先创建好桶，就不需要建桶权限。403 和服务端错误会向调用方抛出，不会当成桶不存在。
成功检查后会缓存结果；运行期间不要从外部删除此桶。

## 业务服务如何调用

在 `KnowledgeDocServiceImpl` 中注入 `RustFsStorageService`。上传主链路应为：

1. 校验用户权限、知识库存在且未删除、文件类型允许。
2. 从查出的知识库记录读取 `collection`，调用
   `storage.upload(knowledgeBase.getCollection(), file)`，获取 `StorageUploadResult`。
3. 插入 `KnowledgeDocDO`：`kbId`、`docName=originalFilename`、
   `fileUrl=objectKey`、`fileType=fileType`、`mimeType=contentType`、`fileSize=size`，
   并补齐状态和审计字段。`fileType` 是文件格式名称：`md`/`markdown` 映射为 `markdown`，
   `txt` 映射为 `text`，其他格式保留小写扩展名（如 `pdf`、`docx`），无有效扩展名时为 `unknown`。
   对象 key 保留原来的小写扩展名，例如 `java-guide/uuid.md`，不会因类型名称变为 `markdown` 而改变后缀。
   `size` 和数据库 `fileSize` 的单位都是字节，不进行 KB 换算。
4. 保存业务记录失败时，用 `storage.delete(objectKey)` 补偿原始文件；
   补偿也失败时需要记录待清理对象，后续重试。

文档记录的 `kbId` 仍保存知识库 ID，用于业务关联；存储前缀使用该知识库的 `collection`。
当前 `fileUrl` 字段可约定保存对象 key，例如 `java-guide/uuid.pdf`，并非公开 URL。
创建知识库后应保持 `collection` 稳定；改名不会自动移动已有对象，读取和删除应使用已保存的完整 key。
`contentType` 当前来自 `MultipartFile.getContentType()`，缺失时使用 `application/octet-stream`，
并非对文件内容进行检测后的鉴定结果。
不要保存临时签名 URL，因为它会过期。RustFS 和 PostgreSQL 不属于同一个本地事务，
仅加 `@Transactional` 不能自动回滚对象上传。

解析原文时：

```java
try (var input = storage.download(doc.getFileUrl())) {
    // 把 input 交给 Tika 等解析器。
}
```

物理删除原文时调用 `storage.delete(doc.getFileUrl())`。
业务服务必须先校验文档归属和权限，对象前缀本身不提供权限隔离。

当前实现仅提供存储能力；原来的 Controller 和文档业务服务仍是待接入的骨架，
没有完成文档数据库登记、解析、分块、向量化及业务删除流程。

## 验证

默认测试不访问 RustFS。手动设置 `RUSTFS_INTEGRATION_TEST=true` 后可运行
`RustFsStorageIntegrationTest`，验证 YAML 配置和 Bean 注册，以及中文内容上传、
读取、同名文件不覆盖和删除。测试使用独立随机前缀，只清理自己上传的对象。
首次运行可能创建 `rag-text-file`，不会删除桶。

在项目根目录使用 PowerShell 执行：

```powershell
$env:RUSTFS_INTEGRATION_TEST = 'true'
.\mvnw.cmd -B -ntp -pl rag -am '-Dmaven.compiler.proc=full' '-Dtest=RustFsStorageServiceTest,RustFsStorageIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本机使用 JDK 25，需显式开启注解处理让项目现有的 Lombok 注解生效；
以上命令包含此选项。此次接入没有修改根 POM 的编译设置。
