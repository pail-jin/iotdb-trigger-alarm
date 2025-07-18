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
- **重要**：触发器只在条件匹配时才触发告警，如果规则没有配置条件，会记录警告日志并跳过告警检查。
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

#### 7. 后端API接口

触发器会调用以下后端API接口：

**告警规则获取接口**
```
GET /api/v1/alarm/rules/get/{rule_id}
Headers: 
  X-API-Key-ID: {api_key_id}
  X-API-Key-Secret: {api_key_secret}
```

**告警创建或更新接口**
```
POST /api/v1/alarm/history/createupdate
Headers:
  Content-Type: application/json
  X-API-Key-ID: {api_key_id}
  X-API-Key-Secret: {api_key_secret}

Body:
{
  "rule_id": 1,
  "device": "root.system.product1.device001",
  "measurement": "temperature", 
  "value": 85.5,
  "timestamp": 1640995200000,
  "severity": "warning",  // 可选
  "details": {  // 可选
    "source": "iotdb_trigger",
    "trigger_type": "threshold_exceeded"
  }
}
```

**接口逻辑说明：**
1. 根据`rule_id`验证告警规则是否存在
2. 从`device`路径中解析系统、产品、设备标识符（如root.system.product1.device001）
3. 根据完整路径查找对应的系统、产品、设备
4. 查找该设备该规则的激活状态告警
5. 如果存在激活告警且时间戳更新，则更新告警详情
6. 如果不存在激活告警，则创建新告警

**响应格式：**
```json
{
  "code": 200,
  "msg": "告警处理成功",
  "data": {
    "id": 123,
    "rule_id": 1,
    "device_id": 456,
    "status": "ACTIVE",
    "severity": "WARNING",
    "start_time": "2024-01-01T12:00:00Z",
    "details": {
      "measurement": "temperature",
      "value": 85.5,
      "timestamp": 1640995200000,
      "trigger_time": "2024-01-01T12:00:00Z"
    },
    "rule_name": "温度告警规则",
    "device_name": "设备001"
  }
}
```

#### 8. 测试

项目根目录提供了测试脚本 `test_createupdate_api.py`，可用于验证API接口功能：

```bash
# 修改脚本中的API配置
vim test_createupdate_api.py

# 运行测试
python test_createupdate_api.py
```

测试脚本会验证：
- 基本API调用功能
- 多次调用时的创建/更新逻辑
- 时间戳相同时的跳过更新逻辑

#### 9. 调试

##### 查看触发器日志

IoTDB触发器的日志会输出到IoTDB的日志文件中：

```bash
# 查看IoTDB容器日志
docker logs iotdb

# 过滤触发器相关日志
docker logs iotdb | grep -i trigger
docker logs iotdb | grep -i alarm

# 进入容器查看日志文件
docker exec -it iotdb bash
cd /iotdb/logs
tail -f iotdb.log | grep -i trigger
```

##### 使用调试脚本

项目根目录提供了调试脚本 `debug_trigger.py`：

```bash
# 修改脚本中的配置
vim debug_trigger.py

# 运行调试脚本
python debug_trigger.py
```

调试脚本会检查：
- IoTDB触发器状态
- 告警规则配置
- API连接状态
- IoTDB中的数据

##### 条件判断逻辑说明

触发器的条件判断逻辑如下：

1. **有配置条件时**：
   - 检查每个测点值是否满足配置的条件
   - 支持多种条件类型：GREATER_THAN、LESS_THAN、EQUAL_TO、NOT_EQUAL_TO、BETWEEN、NOT_BETWEEN
   - 支持and/or组合逻辑
   - 只有条件匹配时才触发告警

2. **没有配置条件时**：
   - 记录警告日志：`"No conditions configured for rule {}, skipping alarm check"`
   - 跳过告警检查，不触发任何告警
   - 这是为了防止误报，确保只有明确配置了条件的规则才会触发告警

3. **条件判断日志**：
   - 触发器会详细记录条件判断过程
   - 包括测点值、条件类型、阈值比较结果等
   - 可通过日志排查条件判断问题

##### 常见问题排查

1. **触发器未触发**
   - 检查触发器是否正确注册：`SHOW TRIGGERS;`
   - 确认数据路径匹配：触发器注册的路径与实际数据路径一致
   - 查看IoTDB日志中的触发器输出
   - **检查告警规则是否配置了条件**：如果没有配置条件，触发器会跳过告警检查

2. **API调用失败**
   - 检查API Key配置是否正确
   - 确认后端API服务正常运行
   - 验证网络连接和防火墙设置

3. **条件判断不准确**
   - 检查告警规则配置
   - 确认数据类型和阈值设置
   - 查看调试日志中的条件判断过程
   - 确认条件类型和关系逻辑是否正确

4. **Action Hook未调用**
   - 检查actionHookUrl配置
   - 确认Node-RED或其他hook服务正常运行
   - 查看网络连接状态
