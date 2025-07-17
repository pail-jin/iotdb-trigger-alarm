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

# 创建触发器
CREATE TRIGGER alarm_trigger
BEFORE INSERT
ON root.sjgd.**
AS 'com.sjgd.trigger.AlarmTrigger';
