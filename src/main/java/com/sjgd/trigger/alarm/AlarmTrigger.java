package com.sjgd.trigger.alarm;

import org.apache.iotdb.trigger.api.Trigger;
import org.apache.iotdb.trigger.api.TriggerAttributes;
import org.apache.iotdb.tsfile.enums.TSDataType;
import org.apache.iotdb.tsfile.write.record.Tablet;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.binary.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;
import java.util.HashMap;
import java.util.Map;

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
            logger.info("=== AlarmTrigger.fire() called ===");
            logger.info("Device: {}, Timestamps: {}, Schemas: {}", 
                tablet.deviceId, tablet.timestamps.length, tablet.getSchemas().size());
            
            // 若rule为null，重试fetchRuleFromApi一次
            if (rule == null) {
                logger.warn("Rule is null in fire, retrying fetchRuleFromApi, rule_id={}", ruleId);
                rule = fetchRuleFromApi();
                if (rule == null) {
                    logger.error("Still failed to fetch rule from API in fire, rule_id={}, skip this fire.", ruleId);
                    return false;
                }
            }
            
            logger.info("Rule loaded: {}", rule != null ? "success" : "failed");
            if (rule != null && rule.getConditions() != null) {
                logger.info("Rule conditions count: {}", rule.getConditions().size());
            }
            
            String devicePath = tablet.deviceId;
            long[] timestamps = tablet.timestamps;
            List<MeasurementSchema> schemas = tablet.getSchemas();
            
            for (int i = 0; i < timestamps.length; i++) {
                long timestamp = timestamps[i];
                logger.info("Processing timestamp: {}", timestamp);
                
                for (int j = 0; j < schemas.size(); j++) {
                    MeasurementSchema schema = schemas.get(j);
                    String measurementName = schema.getMeasurementId();
                    TSDataType dataType = schema.getType();
                    Object value = getValue(tablet, i, j, dataType);
                    
                    logger.info("Measurement: {}, Type: {}, Value: {}", measurementName, dataType, value);
                    
                    Map<String, Object> telemetryDict = new HashMap<>();
                    telemetryDict.put(measurementName, value);
                    
                    if (rule != null && rule.getConditions() != null && !rule.getConditions().isEmpty()) {
                        logger.info("Checking conditions for measurement: {}", measurementName);
                        boolean triggered = checkConditions(rule.getConditions(), telemetryDict);
                        logger.info("Condition check result: {}", triggered);
                        
                        if (triggered) {
                            logger.info("*** ALARM TRIGGERED *** Device: {}, Measurement: {}, Value: {}", 
                                devicePath, measurementName, value);
                            triggerAlarmHistory(devicePath, measurementName, value, timestamp);
                            triggerActionHook(devicePath, measurementName, value, timestamp);
                        }
                    } else {
                        logger.warn("No conditions configured for rule {}, skipping alarm check", ruleId);
                    }
                }
            }
            logger.info("=== AlarmTrigger.fire() completed ===");
            return true;
        } catch (Exception e) {
            logger.error("Error in fire", e);
            return false;
        }
    }

    /**
     * 修正Tablet数据访问方式
     */
    private Object getValue(Tablet tablet, int rowIndex, int columnIndex, TSDataType dataType) {
        try {
            switch (dataType) {
                case INT32:
                    return ((int[]) tablet.values[columnIndex])[rowIndex];
                case INT64:
                    return ((long[]) tablet.values[columnIndex])[rowIndex];
                case FLOAT:
                    return ((float[]) tablet.values[columnIndex])[rowIndex];
                case DOUBLE:
                    return ((double[]) tablet.values[columnIndex])[rowIndex];
                case BOOLEAN:
                    return ((boolean[]) tablet.values[columnIndex])[rowIndex];
                case TEXT:
                    return ((Binary[]) tablet.values[columnIndex])[rowIndex].getStringValue();
                default:
                    return null;
            }
        } catch (Exception e) {
            logger.warn("Error getting value at row={}, column={}, type={}", rowIndex, columnIndex, dataType, e);
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
            logger.info("API Key ID: {}, Secret: {}", apiKeyId, apiKeySecret != null ? "***" : "null");
            
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
    private boolean checkConditions(java.util.List<AlarmCondition> conditions, java.util.Map<String, Object> telemetryDict) {
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
     * 单条件判断，复刻Python _check_single_condition
     */
    private boolean checkSingleCondition(AlarmCondition cond, java.util.Map<String, Object> telemetryDict) {
        String propId = cond.getPropertyIdentifier();
        logger.info("Checking condition: propId={}, type={}, threshold={}, threshold2={}", 
            propId, cond.getConditionType(), cond.getThresholdValue(), cond.getThresholdValue2());
        
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
        
        logger.info("Comparing: value={} ({}) vs threshold={}", value, value.getClass().getSimpleName(), threshold);
        
        try {
            double numericValue = Double.parseDouble(value.toString());
            double th = threshold != null ? Double.parseDouble(threshold) : 0;
            double th2 = threshold2 != null ? Double.parseDouble(threshold2) : 0;
            
            boolean result = false;
            switch (type) {
                case "GREATER_THAN": 
                    result = numericValue > th;
                    logger.info("GREATER_THAN: {} > {} = {}", numericValue, th, result);
                    break;
                case "LESS_THAN": 
                    result = numericValue < th;
                    logger.info("LESS_THAN: {} < {} = {}", numericValue, th, result);
                    break;
                case "EQUAL_TO": 
                    result = numericValue == th;
                    logger.info("EQUAL_TO: {} == {} = {}", numericValue, th, result);
                    break;
                case "NOT_EQUAL_TO": 
                    result = numericValue != th;
                    logger.info("NOT_EQUAL_TO: {} != {} = {}", numericValue, th, result);
                    break;
                case "BETWEEN": 
                    result = th2 != 0 ? (numericValue >= th && numericValue <= th2) : false;
                    logger.info("BETWEEN: {} >= {} && {} <= {} = {}", numericValue, th, numericValue, th2, result);
                    break;
                case "NOT_BETWEEN": 
                    result = th2 != 0 ? !(numericValue >= th && numericValue <= th2) : false;
                    logger.info("NOT_BETWEEN: !({} >= {} && {} <= {}) = {}", numericValue, th, numericValue, th2, result);
                    break;
                default: 
                    logger.warn("Unknown condition type: {}", type);
                    return false;
            }
            return result;
        } catch (Exception e) {
            logger.info("Numeric comparison failed, trying string comparison: {}", e.getMessage());
            // 字符串比较
            if (threshold == null) return false;
            boolean result = false;
            switch (type) {
                case "EQUAL_TO": 
                    result = value.toString().equals(threshold);
                    logger.info("EQUAL_TO (string): '{}' == '{}' = {}", value, threshold, result);
                    break;
                case "NOT_EQUAL_TO": 
                    result = !value.toString().equals(threshold);
                    logger.info("NOT_EQUAL_TO (string): '{}' != '{}' = {}", value, threshold, result);
                    break;
                default: 
                    logger.warn("Unknown string condition type: {}", type);
                    return false;
            }
            return result;
        }
    }

    /**
     * 调用后端API，更新alarm history
     */
    private void triggerAlarmHistory(String device, String measurement, Object value, long timestamp) {
        try {
            String url = apiBaseUrl + "/api/v1/alarm/history/createupdate";
            String payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"measurement\":\"%s\",\"value\":%s,\"timestamp\":%d}",
                    ruleId, device, measurement, value, timestamp);
            
            logger.info("Triggering alarm history API: {}", url);
            logger.info("Payload: {}", payload);
            
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
                // 读取成功响应
                try {
                    Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                    String response = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    logger.info("Alarm history API success response: {}", response);
                } catch (Exception e) {
                    logger.warn("Failed to read success response", e);
                }
                logger.info("Alarm history updated for rule {}", ruleId);
            } else {
                // 读取错误响应
                try {
                    Scanner scanner = new Scanner(conn.getErrorStream(), "UTF-8").useDelimiter("\\A");
                    String errorResponse = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    logger.error("Alarm history API error response: {}", errorResponse);
                } catch (Exception e) {
                    logger.error("Failed to read error response", e);
                }
                logger.warn("Failed to update alarm history, code={}", code);
            }
        } catch (Exception e) {
            logger.error("triggerAlarmHistory exception", e);
        }
    }

    /**
     * 调用actionHookUrl
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
            logger.info("Action hook payload: {}", payload);
            
            HttpURLConnection conn = (HttpURLConnection) new URL(actionHookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(payload.getBytes("UTF-8"));
            
            int code = conn.getResponseCode();
            logger.info("Action hook response code: {}", code);
            
            if (code == 200) {
                // 读取成功响应
                try {
                    Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                    String response = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    logger.info("Action hook success response: {}", response);
                } catch (Exception e) {
                    logger.warn("Failed to read action hook success response", e);
                }
                logger.info("Action hook triggered for rule {}", ruleId);
            } else {
                // 读取错误响应
                try {
                    Scanner scanner = new Scanner(conn.getErrorStream(), "UTF-8").useDelimiter("\\A");
                    String errorResponse = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    logger.error("Action hook error response: {}", errorResponse);
                } catch (Exception e) {
                    logger.error("Failed to read action hook error response", e);
                }
                logger.warn("Failed to trigger action hook, code={}", code);
            }
        } catch (Exception e) {
            logger.error("triggerActionHook exception", e);
        }
    }
} 