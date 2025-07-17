package com.sjgd.trigger.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 告警检测引擎
 * 负责获取告警规则、执行告警检测、触发告警
 */
public class AlarmEngine {
    
    private static final Logger logger = LoggerFactory.getLogger(AlarmEngine.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String apiBaseUrl;
    private final CloseableHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    
    // 告警规则缓存
    private final Map<String, AlarmRule> alarmRules = new ConcurrentHashMap<>();
    private final Map<String, AlarmState> alarmStates = new ConcurrentHashMap<>();
    
    public AlarmEngine(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
        this.httpClient = HttpClients.createDefault();
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        // 启动定时同步告警规则
        startRuleSync();
    }
    
    /**
     * 启动告警规则同步
     */
    private void startRuleSync() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                syncAlarmRules();
            } catch (Exception e) {
                logger.error("Error syncing alarm rules", e);
            }
        }, 0, 60, TimeUnit.SECONDS); // 每分钟同步一次
    }
    
    /**
     * 同步告警规则
     */
    private void syncAlarmRules() {
        try {
            String url = apiBaseUrl + "/api/v1/alarms/rules";
            HttpGet request = new HttpGet(url);
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    HttpEntity entity = response.getEntity();
                    String json = EntityUtils.toString(entity);
                    
                    // 解析告警规则列表
                    List<AlarmRule> rules = objectMapper.readValue(json, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, AlarmRule.class));
                    
                    // 更新缓存
                    alarmRules.clear();
                    for (AlarmRule rule : rules) {
                        alarmRules.put(rule.getId(), rule);
                    }
                    
                    logger.info("Synced {} alarm rules", rules.size());
                } else {
                    logger.warn("Failed to sync alarm rules, status: {}", 
                               response.getStatusLine().getStatusCode());
                }
            }
        } catch (Exception e) {
            logger.error("Error syncing alarm rules", e);
        }
    }
    
    /**
     * 检查告警
     */
    public void checkAlarm(String devicePath, String measurementName, Object value, long timestamp) {
        try {
            // 遍历所有告警规则
            for (AlarmRule rule : alarmRules.values()) {
                if (!rule.isEnabled()) {
                    continue;
                }
                
                // 检查规则是否匹配当前设备和测点
                if (matchesDeviceAndMeasurement(rule, devicePath, measurementName)) {
                    // 检查告警条件
                    if (checkAlarmConditions(rule, value)) {
                        // 触发告警
                        triggerAlarm(rule, devicePath, measurementName, value, timestamp);
                    } else {
                        // 检查是否恢复正常
                        checkAlarmRecovery(rule, devicePath, measurementName, value, timestamp);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error checking alarm for device={}, measurement={}, value={}", 
                        devicePath, measurementName, value, e);
        }
    }
    
    /**
     * 检查设备和测点是否匹配规则
     */
    private boolean matchesDeviceAndMeasurement(AlarmRule rule, String devicePath, String measurementName) {
        // 这里需要根据你的告警规则设计来实现匹配逻辑
        // 例如：检查设备路径是否在规则指定的设备范围内
        // 检查测点名是否在规则指定的测点范围内
        return true; // 临时返回true，需要根据实际规则实现
    }
    
    /**
     * 检查告警条件
     */
    private boolean checkAlarmConditions(AlarmRule rule, Object value) {
        // 这里需要根据你的告警条件设计来实现判断逻辑
        // 例如：检查数值是否超过阈值
        return false; // 临时返回false，需要根据实际条件实现
    }
    
    /**
     * 触发告警
     */
    private void triggerAlarm(AlarmRule rule, String devicePath, String measurementName, 
                            Object value, long timestamp) {
        try {
            // 构建告警数据
            Map<String, Object> alarmData = Map.of(
                "rule_id", rule.getId(),
                "device_path", devicePath,
                "measurement_name", measurementName,
                "value", value,
                "timestamp", timestamp,
                "alarm_time", System.currentTimeMillis()
            );
            
            // 发送告警到API
            String url = apiBaseUrl + "/api/v1/alarms/trigger";
            HttpPost request = new HttpPost(url);
            request.setEntity(new StringEntity(objectMapper.writeValueAsString(alarmData), "UTF-8"));
            request.setHeader("Content-Type", "application/json");
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    logger.info("Alarm triggered successfully for rule: {}", rule.getId());
                } else {
                    logger.warn("Failed to trigger alarm, status: {}", 
                               response.getStatusLine().getStatusCode());
                }
            }
        } catch (Exception e) {
            logger.error("Error triggering alarm", e);
        }
    }
    
    /**
     * 检查告警恢复
     */
    private void checkAlarmRecovery(AlarmRule rule, String devicePath, String measurementName, 
                                  Object value, long timestamp) {
        // 检查告警是否恢复正常
        // 如果恢复正常，发送恢复通知
    }
} 