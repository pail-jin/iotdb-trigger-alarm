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
import java.nio.charset.StandardCharsets;
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

            List<IMeasurementSchema> schemaList = tablet.getSchemas();
            BitMap[] bitMaps = tablet.getBitMaps();
            long[] timestamps = tablet.getTimestamps();
            Object[] values = tablet.getValues();
            int rowSize = tablet.getRowSize();

            for (int i = 0; i < rowSize; i++) {
                long timestamp = timestamps[i];
                logger.info("=== Processing timestamp[{}]: {} ===", i, timestamp);
                Map<String, Object> telemetryDict = new HashMap<>();

                for (int j = 0; j < schemaList.size(); j++) {
                    // 跳过空值
                    if (bitMaps != null && bitMaps[j] != null && bitMaps[j].isMarked(i)) {
                        logger.info("Column[{}]: schema={}, value=null (marked as null)", j, schemaList.get(j).getType());
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
                    logger.info("Column[{}]: schema={}, value={}", j, type, value);
                    if (value != null) {
                        // 直接使用列索引作为key，与官方示例保持一致
                        // String key = "col_" + j;
                        String key = schemaList.get(j).getMeasurementName();
                        telemetryDict.put(key, value);
                        logger.info("Added to telemetryDict: {} = {}", key, value);
                    }
                }

                logger.info("Final telemetryDict: {}", telemetryDict);
                // 检查条件
                if (!telemetryDict.isEmpty()) {
                    boolean triggered = checkConditions(rule.getConditions(), telemetryDict);
                    if (triggered) {
                        Map<String, Object> triggeredTelemetry = new HashMap<>();
                        for (AlarmCondition cond : rule.getConditions()) {
                            String propId = cond.getPropertyIdentifier();
                            if (telemetryDict.containsKey(propId)) {
                                triggeredTelemetry.put(propId, telemetryDict.get(propId));
                            }
                        }
                        if (!triggeredTelemetry.isEmpty()) {
                            logger.info("*** ALARM TRIGGERED *** Device: {}, Timestamp: {}", devicePath, timestamp);
                            triggerAlarmHistory(devicePath, triggeredTelemetry, timestamp);
                            triggerActionHook(devicePath, triggeredTelemetry, timestamp);
                        }
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
        String version = AlarmTrigger.class.getPackage().getImplementationVersion();
        return version != null ? version : "unknown";
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
                return rowIndex < binaryArray.length ? binaryArray[rowIndex].getStringValue(StandardCharsets.UTF_8) : null;
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
            boolean result = false;
            if (value instanceof Number) {
                double numericValue = ((Number) value).doubleValue();
                double th = threshold != null && !threshold.isEmpty() ? Double.parseDouble(threshold) : 0;
                double th2 = threshold2 != null && !threshold2.isEmpty() ? Double.parseDouble(threshold2) : 0;
                switch (type.toLowerCase()) {
                    case "greater_than": result = numericValue > th; break;
                    case "less_than": result = numericValue < th; break;
                    case "equal_to": result = numericValue == th; break;
                    case "not_equal_to": result = numericValue != th; break;
                    case "between": result = th2 != 0 ? (numericValue >= th && numericValue <= th2) : false; break;
                    case "not_between": result = th2 != 0 ? !(numericValue >= th && numericValue <= th2) : false; break;
                    default: logger.warn("Unknown condition type: {}", type); return false;
                }
            } else if (value instanceof Boolean) {
                boolean boolValue = (Boolean) value;
                boolean th = Boolean.parseBoolean(threshold);
                switch (type.toLowerCase()) {
                    case "equal_to": result = boolValue == th; break;
                    case "not_equal_to": result = boolValue != th; break;
                    default: logger.warn("Unsupported boolean condition type: {}", type); return false;
                }
            } else if (value instanceof String) {
                String strValue = (String) value;
                switch (type.toLowerCase()) {
                    case "equal_to": result = strValue.equals(threshold); break;
                    case "not_equal_to": result = !strValue.equals(threshold); break;
                    default: logger.warn("Unsupported string condition type: {}", type); return false;
                }
            } else {
                logger.warn("Unsupported value type: {} for property {}", value.getClass(), propId);
                return false;
            }
            logger.info("Condition result: {} {} {} = {}", value, type, threshold, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to compare value: [{}] for property [{}]", value, propId, e);
            return false;
        }
    }

    /**
     * 触发告警历史记录
     */
    private void triggerAlarmHistory(String device, Map<String, Object> telemetry, long timestamp) {
        try {
            String url = apiBaseUrl + "/api/v1/alarm/history/createupdate";
            
            // 构造details字段，包含所有触发的测点信息
            StringBuilder detailsJson = new StringBuilder("{");
            
            // 直接使用rule中的所有条件
            detailsJson.append("\"triggered_conditions\":");
            detailsJson.append(rule.getConditionsJson()).append(",");
            
            // 添加测点值信息 - 使用last_values字段，避免被后端覆盖
            detailsJson.append("\"last_values\":{");
            int valueIdx = 0;
            for (Map.Entry<String, Object> entry : telemetry.entrySet()) {
                if (valueIdx++ > 0) detailsJson.append(",");
                detailsJson.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof Number || value instanceof Boolean) {
                    detailsJson.append(value);
                } else {
                    detailsJson.append("\"").append(value).append("\"");
                }
            }
            detailsJson.append("},");
            
            // 添加时间戳信息
            detailsJson.append("\"timestamp\":").append(timestamp).append(",");
            detailsJson.append("\"last_timestamp\":").append(timestamp);
            detailsJson.append("}");
            
            // 构造主payload，使用第一个测点作为主要measurement和value
            String firstMeasurement = null;
            Object firstValue = null;
            for (Map.Entry<String, Object> entry : telemetry.entrySet()) {
                firstMeasurement = entry.getKey();
                firstValue = entry.getValue();
                break;
            }
            
            if (firstMeasurement == null) {
                logger.warn("No measurement data available for alarm trigger");
                return;
            }
            
            String payload;
            if (firstValue instanceof Number || firstValue instanceof Boolean) {
                payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"measurement\":\"%s\",\"value\":%s,\"timestamp\":%d,\"details\":%s}",
                        ruleId, device, firstMeasurement, firstValue, timestamp, detailsJson.toString());
            } else {
                payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"measurement\":\"%s\",\"value\":\"%s\",\"timestamp\":%d,\"details\":%s}",
                        ruleId, device, firstMeasurement, firstValue, timestamp, detailsJson.toString());
            }
            
            logger.info("Triggering alarm history API: {} payload={}", url, payload);
            
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
                // 读取错误响应
                try {
                    Scanner scanner = new Scanner(conn.getErrorStream(), "UTF-8").useDelimiter("\\A");
                    String errorResponse = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    logger.error("Error response: {}", errorResponse);
                } catch (Exception e) {
                    logger.error("Failed to read error response", e);
                }
            }
        } catch (Exception e) {
            logger.error("triggerAlarmHistory exception", e);
        }
    }

    /**
     * 触发动作钩子
     */
    private void triggerActionHook(String device, Map<String, Object> telemetry, long timestamp) {
        if (actionHookUrl == null || actionHookUrl.isEmpty()) {
            logger.info("Action hook URL is null or empty, skipping");
            return;
        }
        
        try {
            StringBuilder telemetryJson = new StringBuilder("{");
            int idx = 0;
            for (Map.Entry<String, Object> entry : telemetry.entrySet()) {
                if (idx++ > 0) telemetryJson.append(",");
                telemetryJson.append("\"").append(entry.getKey()).append("\":");
                Object v = entry.getValue();
                if (v instanceof Number || v instanceof Boolean) {
                    telemetryJson.append(v);
                } else {
                    telemetryJson.append("\"").append(v).append("\"");
                }
            }
            telemetryJson.append("}");
            String payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"telemetry\":%s,\"timestamp\":%d}",
                    ruleId, device, telemetryJson.toString(), timestamp);
            
            logger.info("Triggering action hook: {} payload={}", actionHookUrl, payload);
            
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