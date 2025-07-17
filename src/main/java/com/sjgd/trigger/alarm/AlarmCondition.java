package com.sjgd.trigger.alarm;

import com.fasterxml.jackson.databind.JsonNode;

public class AlarmCondition {
    private String propertyIdentifier;
    private String conditionType;
    private String thresholdValue;
    private String thresholdValue2;
    private String relation; // and/or

    public String getPropertyIdentifier() { return propertyIdentifier; }
    public void setPropertyIdentifier(String propertyIdentifier) { this.propertyIdentifier = propertyIdentifier; }
    public String getConditionType() { return conditionType; }
    public void setConditionType(String conditionType) { this.conditionType = conditionType; }
    public String getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(String thresholdValue) { this.thresholdValue = thresholdValue; }
    public String getThresholdValue2() { return thresholdValue2; }
    public void setThresholdValue2(String thresholdValue2) { this.thresholdValue2 = thresholdValue2; }
    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public static AlarmCondition fromJsonNode(JsonNode node) {
        AlarmCondition cond = new AlarmCondition();
        cond.propertyIdentifier = node.has("property_identifier") ? node.get("property_identifier").asText() : null;
        cond.conditionType = node.has("condition_type") ? node.get("condition_type").asText() : null;
        cond.thresholdValue = node.has("threshold_value") ? node.get("threshold_value").asText() : null;
        cond.thresholdValue2 = node.has("threshold_value2") ? node.get("threshold_value2").asText() : null;
        cond.relation = node.has("relation") ? node.get("relation").asText() : null;
        return cond;
    }
} 