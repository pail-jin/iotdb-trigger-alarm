### 在Codespaces中

# 编译项目
mvn clean package

# 查看生成的JAR包
ls -la target/*.jar

# 下载JAR包到本地（右键下载或使用命令行）
cp target/iotdb-alarm-trigger-1.0-jar-with-dependencies.jar ./

### 部署到IoTDB

# 将JAR包放到IoTDB容器中
docker cp iotdb-alarm-trigger-1.0-jar-with-dependencies.jar iotdb:/iotdb/ext/trigger/

# 进入IoTDB容器
docker exec -it iotdb bash

# 启动CLI
/iotdb/sbin/start-cli.sh

### IoTDB Alarm Trigger 使用说明

本Trigger支持高性能、单规则、单测点/组合测点的告警检测，注册时可通过WITH参数传递API地址、规则ID、API Key等信息。

#### 1. 编译步骤

在GitHub Codespaces或本地终端，进入项目目录：

```sh
cd ./iotdb-trigger-alarm
mvn clean package -pl . -Pget-jar-with-dependencies
```

编译成功后，`target/` 目录下会生成：
- `iotdb-alarm-trigger-1.0.jar`：主JAR包，包含所有代码和依赖，**部署到IoTDB时请使用此文件**。
- `original-iotdb-alarm-trigger-1.0.jar`：Maven Shade插件生成的中间产物，仅包含项目自身代码，不包含依赖，**一般无需使用**。

#### 2. 注册触发器方法

IoTDB 支持两种触发器JAR包注册方式：

**方式一：本地 ext/trigger 目录（适合本地/单机/有文件系统权限场景）**

1. 将 `iotdb-alarm-trigger-1.0.jar` 拷贝到 IoTDB 服务器的 `/iotdb/ext/trigger/` 目录下。
   - Docker容器可用 `docker cp` 拷贝。
2. 通过IoTDB CLI注册触发器：
   - 进入IoTDB容器或服务器后，执行：

```sh
# 进入IoTDB CLI
/iotdb/sbin/start-cli.sh
```

   - 在CLI中执行如下SQL注册触发器（**注意：IoTDB 2.x及以上必须加STATELESS或STATEFUL**）：

```sql
CREATE STATELESS TRIGGER alarm_trigger_rule_1
AFTER INSERT
ON root.system.snnb.*.temperature
AS 'com.sjgd.trigger.alarm.AlarmTrigger'
WITH (
  'apiBaseUrl'='http://192.168.3.11:48080',
  'rule_id'='1',
  'X-API-Key-ID'='2579a69ad34651d216d31bce3968f716',
  'X-API-Key-Secret'='JH_lwI6wbvO0Wo-nfbO5D8nzldFw5TfCdRLPm52Q4nA',
  'actionHookUrl'='http://192.168.3.11:1880/iotdb-trigger-hook'
);
```

**方式二：USING URI 自动分发（推荐/适合云端、集群、自动化）**

1. 将 JAR 包上传到可访问的HTTP服务器（如OSS、Nginx、GitHub Release等）。
2. 通过IoTDB CLI注册触发器：
   - 进入IoTDB CLI：

```sh
/iotdb/sbin/start-cli.sh
```

   - 在CLI中执行如下SQL注册触发器（**注意：IoTDB 2.x及以上必须加STATELESS或STATEFUL**）：

```sql
CREATE STATELESS TRIGGER alarm_trigger_rule_1
AFTER INSERT
ON root.system.snnb.*.temperature
AS 'com.sjgd.trigger.alarm.AlarmTrigger'
USING URI 'http://your-server/path/iotdb-alarm-trigger-1.0.jar'
WITH (
  'apiBaseUrl'='http://192.168.3.11:48080',
  'rule_id'='1',
  'X-API-Key-ID'='2579a69ad34651d216d31bce3968f716',
  'X-API-Key-Secret'='JH_lwI6wbvO0Wo-nfbO5D8nzldFw5TfCdRLPm52Q4nA',
  'actionHookUrl'='http://192.168.3.11:1880/iotdb-trigger-hook'
);
```

- 推荐生产环境使用 URI 方式，便于集群分发和自动化。
- 具体可参考[IoTDB官方文档注册示例](https://iotdb.apache.org/zh/UserGuide/latest/User-Manual/Trigger.html#_2-3-示例)

#### 3. 触发器特性
- 启动时自动拉取本rule配置，支持多条件、and/or组合、区间等复杂判断，条件判断逻辑与后端保持一致。
- fire时只处理本测点/本规则，极致高效。
- 支持命中时自动调用后端API和actionHookUrl。

#### 4. 典型应用场景
- 精确到单测点/单规则的高性能告警
- 复杂条件组合（如区间、等于、不等于、and/or等）
- 支持第三方联动动作

#### 5. 代码结构
- `AlarmTrigger.java`：主触发器逻辑
- `AlarmRule.java`：规则结构，支持fromJson
- `AlarmCondition.java`：条件结构，支持fromJsonNode

#### 6. 注意事项
- WITH参数只能传字符串，如需复杂结构请用JSON字符串传递并在Java端解析。
- 触发器注册ON建议精确到实际需要的测点，避免无谓性能消耗。
