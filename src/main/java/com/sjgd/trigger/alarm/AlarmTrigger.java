package com.sjgd.trigger.alarm;

import org.apache.iotdb.trigger.api.Trigger;
import org.apache.iotdb.trigger.api.TriggerAttributes;
import org.apache.iotdb.trigger.api.enums.FailureStrategy;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.utils.BitMap;
import org.apache.tsfile.utils.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;
import java.util.HashMap;
import java.util.Map;
import org.apache.tsfile.write.schema.IMeasurementSchema;


/**
 * IoTDB告警触发器，参考官方示例风格
 */
public class AlarmTrigger implements Trigger {
    private static final Logger logger = LoggerFactory.getLogger(AlarmTrigger.class);

    // 触发器参数
    private String apiBaseUrl;
    private String ruleId;
    private String apiKeyId;
    private String apiKeySecret;
    private String actionHookUrl;
    private AlarmRule rule;

    @Override
    public void onCreate(TriggerAttributes attributes) throws Exception {
        // 打印JAR包信息
        printJarInfo();
        
        // 初始化参数、资源
        this.apiBaseUrl = attributes.getString("apiBaseUrl");
        this.ruleId = attributes.getString("rule_id");
        this.apiKeyId = attributes.getString("X-API-Key-ID");
        this.apiKeySecret = attributes.getString("X-API-Key-Secret");
        this.actionHookUrl = attributes.getString("actionHookUrl");
        logger.info("AlarmTrigger created with apiBaseUrl={}, rule_id={}, actionHookUrl={}", apiBaseUrl, ruleId, actionHookUrl);
        // 拉取本rule配置，失败只打印日志不抛异常
        this.rule = fetchRuleFromApi();
        if (rule == null) {
            logger.error("Failed to fetch rule from API, rule_id={}", ruleId);
        }
    }

    @Override
    public void onDrop() throws Exception {
        // 资源释放、清理
        logger.info("AlarmTrigger dropped");
    }

    @Override
    public void restore() throws Exception {
        // 状态恢复（如有需要）
        logger.info("AlarmTrigger restore called");
    }

    @Override
    public boolean fire(Tablet tablet) throws Exception {
        try {
            String devicePath = tablet.getDeviceId();
            logger.info("AlarmTrigger.fire() called for device: {}", devicePath);

            // 若rule为null，重试fetchRuleFromApi一次
            if (rule == null) {
                logger.warn("Rule is null in fire, retrying fetchRuleFromApi, rule_id={}", ruleId);
                rule = fetchRuleFromApi();
                if (rule == null) {
                    logger.error("Still failed to fetch rule from API in fire, rule_id={}, skip this fire.", ruleId);
                    return false;
                }
            }

            // 检查是否有配置条件
            if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
                logger.warn("No conditions configured for rule {}, skipping alarm check", ruleId);
                return true;
            }

            String[] measurements = tablet.getMeasurements();
            List<IMeasurementSchema> schemaList = tablet.getSchemas();
            BitMap[] bitMaps = tablet.getBitMaps();
            long[] timestamps = tablet.getTimestamps();
            Object[] values = tablet.getValues();
            int rowSize = tablet.rowSize;

            for (int i = 0; i < rowSize; i++) {
                long timestamp = timestamps[i];
                logger.info("=== Processing timestamp[{}]: {} ===", i, timestamp);
                Map<String, Object> telemetryDict = new HashMap<>();

                for (int j = 0; j < schemaList.size(); j++) {
                    // 跳过空值
                    if (bitMaps != null && bitMaps[j] != null && bitMaps[j].isMarked(i)) {
                        logger.info("Column[{}]: name={}, schema={}, value=null (marked as null)", j, measurements[j], schemaList.get(j).getType());
                        continue;
                    }
                    TSDataType type = schemaList.get(j).getType();
                    Object col = values[j];
                    Object value = null;
                    if (type == TSDataType.DOUBLE) {
                        value = ((double[]) col)[i];
                    } else if (type == TSDataType.FLOAT) {
                        value = ((float[]) col)[i];
                    } else if (type == TSDataType.INT64) {
                        value = ((long[]) col)[i];
                    } else if (type == TSDataType.INT32) {
                        value = ((int[]) col)[i];
                    } else if (type == TSDataType.BOOLEAN) {
                        value = ((boolean[]) col)[i];
                    } else if (type == TSDataType.TEXT) {
                        value = ((Binary[]) col)[i].getStringValue(StandardCharsets.UTF_8);
                    }
                    logger.info("Column[{}]: name={}, schema={}, value={}", j, measurements[j], type, value);
                    if (value != null) {
                        telemetryDict.put(measurements[j], value);
                        logger.info("Added to telemetryDict: {} = {}", measurements[j], value);
                    }
                }

                logger.info("Final telemetryDict: {}", telemetryDict);
                // 检查条件
                if (!telemetryDict.isEmpty()) {
                    boolean triggered = checkConditions(rule.getConditions(), telemetryDict);
                    if (triggered) {
                        logger.info("*** ALARM TRIGGERED *** Device: {}, Timestamp: {}", devicePath, timestamp);
                        // 触发告警，使用第一个测点作为主要测点
                        String firstMeasurement = telemetryDict.keySet().iterator().next();
                        Object firstValue = telemetryDict.get(firstMeasurement);
                        triggerAlarmHistory(devicePath, firstMeasurement, firstValue, timestamp);
                        triggerActionHook(devicePath, firstMeasurement, firstValue, timestamp);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("Error in fire method", e);
            return false;
        }
    }

    /**
     * 打印JAR包信息
     */
    private void printJarInfo() {
        try {
            // 获取当前类的JAR包信息
            String jarPath = AlarmTrigger.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            logger.info("=== IoTDB Alarm Trigger JAR Info ===");
            logger.info("JAR Path: {}", jarPath);
            logger.info("JAR Name: {}", jarPath.substring(jarPath.lastIndexOf('/') + 1));
            
            // 从manifest获取版本信息
            String version = getVersionFromManifest();
            logger.info("Version: {}", version);
            
            logger.info("Build Time: {}", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            logger.info("Java Version: {}", System.getProperty("java.version"));
            logger.info("IoTDB Version: {}", getIotdbVersion());
            logger.info("=====================================");
        } catch (Exception e) {
            logger.warn("Failed to print JAR info: {}", e.getMessage());
        }
    }
    
    /**
     * 从manifest获取版本信息
     */
    private String getVersionFromManifest() {
        try {
            java.util.jar.Manifest manifest = new java.util.jar.Manifest(
                AlarmTrigger.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF"));
            java.util.jar.Attributes attributes = manifest.getMainAttributes();
            String version = attributes.getValue("Implementation-Version");
            return version != null ? version : "unknown";
        } catch (Exception e) {
            logger.warn("Failed to get version from manifest: {}", e.getMessage());
            return "unknown";
        }
    }
    
    /**
     * 获取IoTDB版本
     */
    private String getIotdbVersion() {
        try {
            // 尝试从trigger-api包获取版本
            Package triggerApiPackage = org.apache.iotdb.trigger.api.Trigger.class.getPackage();
            String version = triggerApiPackage.getImplementationVersion();
            return version != null ? version : "2.0.3";
        } catch (Exception e) {
            logger.warn("Failed to get IoTDB version: {}", e.getMessage());
            return "2.0.3";
        }
    }

    /**
     * 从Tablet中安全获取指定列和行的值
     */
    private Object getValueFromTablet(Tablet tablet, int columnIndex, int rowIndex, TSDataType dataType) {
        try {
            Object[] values = tablet.getValues();
            if (values == null || columnIndex >= values.length) {
                return null;
            }
            
            Object columnData = values[columnIndex];
            if (columnData == null) {
                return null;
            }
            
            // 根据数据类型安全地获取值（位图检查已在fire方法中处理）
            if (dataType.equals(TSDataType.BOOLEAN)) {
                boolean[] boolArray = (boolean[]) columnData;
                return rowIndex < boolArray.length ? boolArray[rowIndex] : null;
            } else if (dataType.equals(TSDataType.INT32)) {
                int[] intArray = (int[]) columnData;
                return rowIndex < intArray.length ? intArray[rowIndex] : null;
            } else if (dataType.equals(TSDataType.INT64)) {
                long[] longArray = (long[]) columnData;
                return rowIndex < longArray.length ? longArray[rowIndex] : null;
            } else if (dataType.equals(TSDataType.FLOAT)) {
                float[] floatArray = (float[]) columnData;
                return rowIndex < floatArray.length ? floatArray[rowIndex] : null;
            } else if (dataType.equals(TSDataType.DOUBLE)) {
                double[] doubleArray = (double[]) columnData;
                return rowIndex < doubleArray.length ? doubleArray[rowIndex] : null;
            } else if (dataType.equals(TSDataType.TEXT)) {
                Binary[] binaryArray = (Binary[]) columnData;
                return rowIndex < binaryArray.length ? binaryArray[rowIndex].getStringValue() : null;
            } else {
                logger.warn("Unsupported data type: {}", dataType);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error getting value from tablet: column={}, row={}, type={}", columnIndex, rowIndex, dataType, e);
            return null;
        }
    }

    /**
     * 拉取本rule配置
     */
    private AlarmRule fetchRuleFromApi() {
        try {
            String url = apiBaseUrl + "/api/v1/alarm/rules/get/" + ruleId;
            logger.info("Fetching rule from API: {}", url);
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-API-Key-ID", apiKeyId);
            conn.setRequestProperty("X-API-Key-Secret", apiKeySecret);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            
            int code = conn.getResponseCode();
            logger.info("API response code: {}", code);
            
            if (code == 200) {
                Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                String json = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                
                logger.info("API response JSON: {}", json);
                
                AlarmRule rule = AlarmRule.fromJson(json);
                logger.info("Rule parsed successfully: {}", rule != null);
                if (rule != null) {
                    logger.info("Rule ID: {}, Name: {}", rule.getId(), rule.getName());
                    logger.info("Conditions count: {}", rule.getConditions() != null ? rule.getConditions().size() : 0);
                    if (rule.getConditions() != null) {
                        for (int i = 0; i < rule.getConditions().size(); i++) {
                            AlarmCondition cond = rule.getConditions().get(i);
                            logger.info("Condition[{}]: propId={}, type={}, threshold={}, relation={}", 
                                i, cond.getPropertyIdentifier(), cond.getConditionType(), 
                                cond.getThresholdValue(), cond.getRelation());
                        }
                    }
                }
                return rule;
            } else {
                logger.error("fetchRuleFromApi failed, code={}, url={}", code, url);
                // 读取错误响应
                try {
                    Scanner scanner = new Scanner(conn.getErrorStream(), "UTF-8").useDelimiter("\\A");
                    String errorResponse = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    logger.error("Error response: {}", errorResponse);
                } catch (Exception e) {
                    logger.error("Failed to read error response", e);
                }
                return null;
            }
        } catch (IOException e) {
            logger.error("fetchRuleFromApi exception", e);
            return null;
        }
    }

    /**
     * 多条件组合判断，支持and/or
     */
    private boolean checkConditions(List<AlarmCondition> conditions, Map<String, Object> telemetryDict) {
        if (conditions == null || conditions.isEmpty()) return false;
        
        AlarmCondition first = conditions.get(0);
        boolean result = checkSingleCondition(first, telemetryDict);
        
        for (int i = 1; i < conditions.size(); i++) {
            AlarmCondition cond = conditions.get(i);
            boolean current = checkSingleCondition(cond, telemetryDict);
            if ("and".equalsIgnoreCase(cond.getRelation())) {
                result = result && current;
            } else {
                result = result || current;
            }
        }
        return result;
    }

    /**
     * 单条件判断
     */
    private boolean checkSingleCondition(AlarmCondition cond, Map<String, Object> telemetryDict) {
        String propId = cond.getPropertyIdentifier();
        logger.info("Checking condition: propId={}, type={}, threshold={}", 
            propId, cond.getConditionType(), cond.getThresholdValue());
        
        if (!telemetryDict.containsKey(propId)) {
            logger.info("Property {} not found in telemetry data", propId);
            return false;
        }
        
        Object value = telemetryDict.get(propId);
        if (value == null) {
            logger.info("Property {} value is null", propId);
            return false;
        }
        
        String type = cond.getConditionType();
        String threshold = cond.getThresholdValue();
        String threshold2 = cond.getThresholdValue2();
        
        try {
            double numericValue = Double.parseDouble(value.toString());
            double th = threshold != null ? Double.parseDouble(threshold) : 0;
            double th2 = threshold2 != null ? Double.parseDouble(threshold2) : 0;
            
            boolean result = false;
            switch (type.toLowerCase()) {
                case "greater_than": 
                    result = numericValue > th;
                    break;
                case "less_than": 
                    result = numericValue < th;
                    break;
                case "equal_to": 
                    result = numericValue == th;
                    break;
                case "not_equal_to": 
                    result = numericValue != th;
                    break;
                case "between": 
                    result = th2 != 0 ? (numericValue >= th && numericValue <= th2) : false;
                    break;
                case "not_between": 
                    result = th2 != 0 ? !(numericValue >= th && numericValue <= th2) : false;
                    break;
                default: 
                    logger.warn("Unknown condition type: {}", type);
                    return false;
            }
            
            logger.info("Condition result: {} {} {} = {}", numericValue, type, threshold, result);
            return result;
        } catch (NumberFormatException e) {
            logger.error("Failed to parse numeric value: {}", value, e);
            return false;
        }
    }

    /**
     * 触发告警历史记录
     */
    private void triggerAlarmHistory(String device, String measurement, Object value, long timestamp) {
        try {
            String url = apiBaseUrl + "/api/v1/alarm/history/createupdate";
            String payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"measurement\":\"%s\",\"value\":%s,\"timestamp\":%d}",
                    ruleId, device, measurement, value, timestamp);
            
            logger.info("Triggering alarm history API: {}", url);
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key-ID", apiKeyId);
            conn.setRequestProperty("X-API-Key-Secret", apiKeySecret);
            conn.setDoOutput(true);
            conn.getOutputStream().write(payload.getBytes("UTF-8"));
            
            int code = conn.getResponseCode();
            logger.info("Alarm history API response code: {}", code);
            
            if (code == 200) {
                logger.info("Alarm history created/updated successfully for rule {}", ruleId);
            } else {
                logger.warn("Failed to create/update alarm history, code={}", code);
            }
        } catch (Exception e) {
            logger.error("triggerAlarmHistory exception", e);
        }
    }

    /**
     * 触发动作钩子
     */
    private void triggerActionHook(String device, String measurement, Object value, long timestamp) {
        if (actionHookUrl == null || actionHookUrl.isEmpty()) {
            logger.info("Action hook URL is null or empty, skipping");
            return;
        }
        
        try {
            String payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"measurement\":\"%s\",\"value\":%s,\"timestamp\":%d}",
                    ruleId, device, measurement, value, timestamp);
            
            logger.info("Triggering action hook: {}", actionHookUrl);
            
            HttpURLConnection conn = (HttpURLConnection) new URL(actionHookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(payload.getBytes("UTF-8"));
            
            int code = conn.getResponseCode();
            logger.info("Action hook response code: {}", code);
            
            if (code == 200) {
                logger.info("Action hook triggered successfully for rule {}", ruleId);
            } else {
                logger.warn("Failed to trigger action hook, code={}", code);
            }
        } catch (Exception e) {
            logger.error("triggerActionHook exception", e);
        }
    }
} 