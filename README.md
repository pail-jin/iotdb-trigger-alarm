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

#### 1. 注册触发器SQL示例

```sql
CREATE TRIGGER alarm_trigger_101
AFTER INSERT
ON root.sg.device1.a
AS 'com.sjgd.trigger.alarm.AlarmTrigger'
WITH (
  'apiBaseUrl'='http://your-api:8000',
  'rule_id'='101',
  'X-API-Key-ID'='your-key-id',
  'X-API-Key-Secret'='your-key-secret',
  'actionHookUrl'='http://third-party-action:9000/hook'
);
```

- **apiBaseUrl**：后端API基础地址（如：http://your-api:8000）
- **rule_id**：本Trigger对应的告警规则ID
- **X-API-Key-ID / X-API-Key-Secret**：访问API的鉴权参数
- **actionHookUrl**：告警触发时回调的第三方URL（可选）

#### 2. 触发器特性
- 启动时自动拉取本rule配置，支持多条件、and/or组合、区间等复杂判断，条件判断逻辑与后端保持一致。
- fire时只处理本测点/本规则，极致高效。
- 支持命中时自动调用后端API和actionHookUrl。

#### 3. 典型应用场景
- 精确到单测点/单规则的高性能告警
- 复杂条件组合（如区间、等于、不等于、and/or等）
- 支持第三方联动动作

#### 4. 代码结构
- `AlarmTrigger.java`：主触发器逻辑
- `AlarmRule.java`：规则结构，支持fromJson
- `AlarmCondition.java`：条件结构，支持fromJsonNode

#### 5. 注意事项
- WITH参数只能传字符串，如需复杂结构请用JSON字符串传递并在Java端解析。
- 触发器注册ON建议精确到实际需要的测点，避免无谓性能消耗。
