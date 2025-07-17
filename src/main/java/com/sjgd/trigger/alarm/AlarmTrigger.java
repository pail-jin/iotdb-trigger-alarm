package com.sjgd.trigger.alarm;

import org.apache.iotdb.trigger.api.Trigger;
import org.apache.iotdb.trigger.api.TriggerAttributes;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.write.record.Tablet;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

/**
 * IoTDB告警触发器（单规则、单测点/单pattern高性能版）
 */
public class AlarmTrigger implements Trigger {
    private static final Logger logger = LoggerFactory.getLogger(AlarmTrigger.class);

    private String apiBaseUrl;
    private String ruleId;
    private String apiKeyId;
    private String apiKeySecret;
    private String actionHookUrl;
    private AlarmRule rule;

    @Override
    public void onCreate(TriggerAttributes attributes) throws Exception {
        this.apiBaseUrl = attributes.getString("apiBaseUrl");
        this.ruleId = attributes.getString("rule_id");
        this.apiKeyId = attributes.getString("X-API-Key-ID");
        this.apiKeySecret = attributes.getString("X-API-Key-Secret");
        this.actionHookUrl = attributes.getString("actionHookUrl");
        logger.info("AlarmTrigger created with apiBaseUrl={}, rule_id={}, actionHookUrl={}", apiBaseUrl, ruleId, actionHookUrl);
        // 拉取本rule配置
        this.rule = fetchRuleFromApi();
        if (rule == null) {
            throw new RuntimeException("Failed to fetch rule from API, rule_id=" + ruleId);
        }
    }

    @Override
    public void onDrop() { logger.info("AlarmTrigger dropped"); }
    @Override
    public void onStart() { logger.info("AlarmTrigger started"); }
    @Override
    public void onStop() { logger.info("AlarmTrigger stopped"); }

    @Override
    public void fire(Tablet tablet, TriggerAttributes attributes) {
        try {
            String devicePath = tablet.deviceId;
            long[] timestamps = tablet.timestamps;
            List<MeasurementSchema> schemas = tablet.getSchemas();
            for (int i = 0; i < timestamps.length; i++) {
                long timestamp = timestamps[i];
                for (int j = 0; j < schemas.size(); j++) {
                    MeasurementSchema schema = schemas.get(j);
                    String measurementName = schema.getMeasurementId();
                    TSDataType dataType = schema.getType();
                    Object value = getValue(tablet, i, j, dataType);
                    // 构造本次遥测数据字典
                    java.util.Map<String, Object> telemetryDict = new java.util.HashMap<>();
                    telemetryDict.put(measurementName, value);
                    if (rule != null && rule.getConditions() != null && !rule.getConditions().isEmpty()) {
                        boolean triggered = checkConditions(rule.getConditions(), telemetryDict);
                        if (triggered) {
                            triggerAlarmHistory(devicePath, measurementName, value, timestamp);
                            triggerActionHook(devicePath, measurementName, value, timestamp);
                        }
                    } else if (value != null && matchCondition(value)) {
                        // 兼容无conditions的简单阈值
                        triggerAlarmHistory(devicePath, measurementName, value, timestamp);
                        triggerActionHook(devicePath, measurementName, value, timestamp);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in fire", e);
        }
    }

    private Object getValue(Tablet tablet, int rowIndex, int columnIndex, TSDataType dataType) {
        try {
            switch (dataType) {
                case INT32: return tablet.values[columnIndex].getInt(rowIndex);
                case INT64: return tablet.values[columnIndex].getLong(rowIndex);
                case FLOAT: return tablet.values[columnIndex].getFloat(rowIndex);
                case DOUBLE: return tablet.values[columnIndex].getDouble(rowIndex);
                case BOOLEAN: return tablet.values[columnIndex].getBoolean(rowIndex);
                case TEXT: return tablet.values[columnIndex].getBinary(rowIndex).getStringValue();
                default: return null;
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
            String url = apiBaseUrl + "/api/v1/alarms/rule/" + ruleId;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-API-Key-ID", apiKeyId);
            conn.setRequestProperty("X-API-Key-Secret", apiKeySecret);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code == 200) {
                Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                String json = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                return AlarmRule.fromJson(json);
            } else {
                logger.error("fetchRuleFromApi failed, code={}, url={}", code, url);
                return null;
            }
        } catch (IOException e) {
            logger.error("fetchRuleFromApi exception", e);
            return null;
        }
    }

    /**
     * 判断是否满足本rule的条件（伪实现，需根据实际rule condition实现）
     */
    private boolean matchCondition(Object value) {
        // TODO: 根据rule.condition实现真正的判断逻辑
        // 这里只做简单示例：假设大于某个阈值
        if (rule != null && rule.getThreshold() != null && value instanceof Number) {
            double v = ((Number) value).doubleValue();
            return v > rule.getThreshold();
        }
        return false;
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
        if (!telemetryDict.containsKey(propId)) return false;
        Object value = telemetryDict.get(propId);
        if (value == null) return false;
        String type = cond.getConditionType();
        String threshold = cond.getThresholdValue();
        String threshold2 = cond.getThresholdValue2();
        try {
            double numericValue = Double.parseDouble(value.toString());
            double th = threshold != null ? Double.parseDouble(threshold) : 0;
            double th2 = threshold2 != null ? Double.parseDouble(threshold2) : 0;
            switch (type) {
                case "GREATER_THAN": return numericValue > th;
                case "LESS_THAN": return numericValue < th;
                case "EQUAL_TO": return numericValue == th;
                case "NOT_EQUAL_TO": return numericValue != th;
                case "BETWEEN": return th2 != 0 ? (numericValue >= th && numericValue <= th2) : false;
                case "NOT_BETWEEN": return th2 != 0 ? !(numericValue >= th && numericValue <= th2) : false;
                // CHANGE_RATE等特殊类型可扩展
                default: return false;
            }
        } catch (Exception e) {
            // 字符串比较
            if (threshold == null) return false;
            switch (type) {
                case "EQUAL_TO": return value.toString().equals(threshold);
                case "NOT_EQUAL_TO": return !value.toString().equals(threshold);
                default: return false;
            }
        }
    }

    /**
     * 调用后端API，更新alarm history
     */
    private void triggerAlarmHistory(String device, String measurement, Object value, long timestamp) {
        try {
            String url = apiBaseUrl + "/api/v1/alarms/history";
            String payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"measurement\":\"%s\",\"value\":%s,\"timestamp\":%d}",
                    ruleId, device, measurement, value, timestamp);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key-ID", apiKeyId);
            conn.setRequestProperty("X-API-Key-Secret", apiKeySecret);
            conn.setDoOutput(true);
            conn.getOutputStream().write(payload.getBytes("UTF-8"));
            int code = conn.getResponseCode();
            if (code == 200) {
                logger.info("Alarm history updated for rule {}", ruleId);
            } else {
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
        if (actionHookUrl == null || actionHookUrl.isEmpty()) return;
        try {
            String payload = String.format("{\"rule_id\":%s,\"device\":\"%s\",\"measurement\":\"%s\",\"value\":%s,\"timestamp\":%d}",
                    ruleId, device, measurement, value, timestamp);
            HttpURLConnection conn = (HttpURLConnection) new URL(actionHookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(payload.getBytes("UTF-8"));
            int code = conn.getResponseCode();
            if (code == 200) {
                logger.info("Action hook triggered for rule {}", ruleId);
            } else {
                logger.warn("Failed to trigger action hook, code={}", code);
            }
        } catch (Exception e) {
            logger.error("triggerActionHook exception", e);
        }
    }
} 