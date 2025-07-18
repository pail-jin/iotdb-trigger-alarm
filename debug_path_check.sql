-- 检查IoTDB中的数据路径
-- 在IoTDB CLI中执行以下命令

-- 1. 查看所有时间序列
SHOW TIMESERIES;

-- 2. 查看特定路径下的时间序列
SHOW TIMESERIES root.system.snnb.*;

-- 3. 查看数据存储组
SHOW STORAGE GROUP;

-- 4. 查看设备列表
SHOW DEVICES;

-- 5. 查看特定设备下的测点
SHOW TIMESERIES root.system.snnb.device001.*;

-- 6. 查看最近的数据
SELECT * FROM root.system.snnb.*.temperature LIMIT 10;

-- 7. 查看数据写入时间
SELECT __endTime, temperature FROM root.system.snnb.*.temperature ORDER BY TIME DESC LIMIT 5; 