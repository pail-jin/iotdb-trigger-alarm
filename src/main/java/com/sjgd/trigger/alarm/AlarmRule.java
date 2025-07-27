package com.sjgd.trigger.alarm;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;

public class AlarmRule {
    private String id;
    private String name;
    private List<AlarmCondition> conditions;
    private String description;
    private String severity;
    private Double threshold; // 兼容简单阈值

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<AlarmCondition> getConditions() { return conditions; }
    public void setConditions(List<AlarmCondition> conditions) { this.conditions = conditions; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }

    /**
     * 将条件列表转换为JSON字符串
     */
    public String getConditionsJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(conditions);
        } catch (Exception e) {
            e.printStackTrace();
            return "[]";
        }
    }

    // 反序列化API返回的规则详情
    public static AlarmRule fromJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(json);
            
            // API返回格式: {"code":200,"msg":"OK","data":{...}}
            JsonNode node = rootNode.has("data") ? rootNode.get("data") : rootNode;
            
            AlarmRule rule = new AlarmRule();
            rule.id = node.has("id") ? node.get("id").asText() : null;
            rule.name = node.has("name") ? node.get("name").asText() : null;
            rule.description = node.has("description") ? node.get("description").asText() : null;
            rule.severity = node.has("severity") ? node.get("severity").asText() : null;
            if (node.has("threshold")) {
                rule.threshold = node.get("threshold").asDouble();
            }
            
            // 解析conditions
            List<AlarmCondition> conds = new ArrayList<>();
            if (node.has("conditions") && node.get("conditions").isArray()) {
                for (JsonNode c : node.get("conditions")) {
                    conds.add(AlarmCondition.fromJsonNode(c));
                }
            }
            rule.conditions = conds;
            return rule;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
} 