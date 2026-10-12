# MyBatis 与 MyBatis Generator 使用说明

## 设计与边界

本项目使用 MyBatis Spring Boot Starter 连接 MySQL，并通过 MyBatis Generator（MBG）手动生成持久层代码。
Generator 不绑定 Maven 默认生命周期，避免普通构建依赖数据库或意外覆盖生成文件。

生成器采用 `MyBatis3Simple + XMLMAPPER`：

- 实体输出到 `com.github.zaneway.agent.ca.persistence.entity`。
- Mapper 接口输出到 `com.github.zaneway.agent.ca.persistence.mapper`，并由生成器添加 `@Mapper`。
- Mapper XML 输出到 `src/main/resources/mapper`。
- 每次只允许显式指定一个表；Generator 会转义 JDBC metadata 中的 `%` 和 `_` 通配符，防止蛇形表名误匹配多表。
- Java 生成文件允许覆盖；生成目录中的文件应视为派生产物，业务扩展放在独立类或独立 XML 中。

本次集成不新增数据库表、事务或缓存策略。具体业务 Service 出现多步写操作时，必须在 Service 层明确事务边界并设计回滚行为。

## 运行时配置

| 环境变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `MYSQL_URL` | 生产必填 | `jdbc:mysql://127.0.0.1:3306/ca?...` | JDBC URL，必须明确目标数据库 |
| `MYSQL_USERNAME` | 是 | 空 | 数据库用户，建议使用最小权限业务账号 |
| `MYSQL_PASSWORD` | 是 | 空 | 数据库密码，只允许通过环境变量或密钥管理系统注入 |

Mapper XML 扫描路径为 `classpath*:/mapper/**/*.xml`，实体类型别名包为
`com.github.zaneway.agent.ca.persistence.entity`。下划线字段默认映射为驼峰属性；MyBatis 二级缓存默认关闭。

生产环境建议在 JDBC URL 中补充经过验证的 TLS 参数，并通过 Kubernetes Secret、Vault 或同类密钥管理系统注入账号密码。
不要将连接密码写入 `application.yaml`、Generator XML、Shell 脚本或版本库。

## 生成代码

Generator 与应用运行时复用 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`，并额外要求指定表名：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export MYSQL_URL='jdbc:mysql://127.0.0.1:3306/ca?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Taipei'
export MYSQL_USERNAME='<database-user>'
export MYSQL_PASSWORD='<database-password>'
export MYBATIS_GENERATOR_TABLE='<table-name>'

mvn mybatis-generator:generate
```

执行前确认工作区状态干净，并检查 `MYBATIS_GENERATOR_TABLE` 指向预期表。执行后必须审查实体字段类型、主键、可空性、
时间类型、BLOB 字段和 Mapper SQL，再运行编译与相关数据库集成测试。MySQL unsigned 数值无法由 JDBC 类型完整表达，
尤其是 `BIGINT UNSIGNED` 可能被映射为有符号 Java 类型并产生溢出；这类字段必须使用 `columnOverride` 明确 Java 类型。

## 常见问题与恢复

- `Access denied`：检查账号、密码、来源 IP 和 MySQL 授权；不要通过扩大到管理员权限解决。
- `Communications link failure`：检查 DNS、端口、防火墙、TLS 和 JDBC URL。
- `Table ... doesn't exist`：确认 URL 中的数据库与 `MYBATIS_GENERATOR_TABLE`，注意 Linux MySQL 表名大小写。
- 生成类型不符合业务语义：使用 Generator 的 `columnOverride` 明确 JDBC/Java 类型，不要在生成后反复手改。
- 生成结果覆盖了本地修改：生成文件不应承载手写逻辑；从版本控制恢复后，将扩展逻辑迁移到独立文件。

回滚本次基础设施配置时，移除 MyBatis Starter、Generator Maven Plugin、Generator XML 和 `mybatis` 配置即可；
本次变更不包含数据库 DDL 或数据迁移，不需要数据库回滚。
