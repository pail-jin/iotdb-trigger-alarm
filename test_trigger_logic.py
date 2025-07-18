#!/usr/bin/env python3
"""
测试IoTDB触发器逻辑的Python脚本
模拟Java代码的条件判断逻辑
"""

import json
from typing import Dict, List, Any, Union

class AlarmCondition:
    """告警条件类，模拟Java AlarmCondition"""
    
    def __init__(self, property_identifier: str, condition_type: str, 
                 threshold_value: str, threshold_value2: str = None, relation: str = "AND"):
        self.property_identifier = property_identifier
        self.condition_type = condition_type
        self.threshold_value = threshold_value
        self.threshold_value2 = threshold_value2
        self.relation = relation
    
    @classmethod
    def from_json_node(cls, data: Dict[str, Any]) -> 'AlarmCondition':
        """从JSON节点创建条件对象"""
        return cls(
            property_identifier=data.get('property_identifier', ''),
            condition_type=data.get('condition_type', ''),
            threshold_value=data.get('threshold_value', ''),
            threshold_value2=data.get('threshold_value2'),
            relation=data.get('relation', 'AND')
        )

class AlarmRule:
    """告警规则类，模拟Java AlarmRule"""
    
    def __init__(self, rule_id: int, name: str, conditions: List[AlarmCondition] = None):
        self.rule_id = rule_id
        self.name = name
        self.conditions = conditions or []
    
    @classmethod
    def from_json(cls, json_str: str) -> 'AlarmRule':
        """从JSON字符串创建规则对象"""
        try:
            data = json.loads(json_str)
            if data.get('code') == 200 and 'data' in data:
                rule_data = data['data']
                conditions = []
                if 'conditions' in rule_data:
                    for cond_data in rule_data['conditions']:
                        conditions.append(AlarmCondition.from_json_node(cond_data))
                
                return cls(
                    rule_id=rule_data.get('id', 0),
                    name=rule_data.get('name', ''),
                    conditions=conditions
                )
        except Exception as e:
            print(f"解析规则JSON失败: {e}")
        return None

def check_single_condition(condition: AlarmCondition, telemetry_dict: Dict[str, Any]) -> bool:
    """单条件判断，模拟Java checkSingleCondition方法"""
    prop_id = condition.property_identifier
    print(f"检查条件: propId={prop_id}, type={condition.condition_type}, threshold={condition.threshold_value}")
    
    if prop_id not in telemetry_dict:
        print(f"属性 {prop_id} 在遥测数据中未找到")
        return False
    
    value = telemetry_dict[prop_id]
    if value is None:
        print(f"属性 {prop_id} 值为null")
        return False
    
    condition_type = condition.condition_type
    threshold = condition.threshold_value
    threshold2 = condition.threshold_value2
    
    try:
        numeric_value = float(value)
        th = float(threshold) if threshold else 0
        th2 = float(threshold2) if threshold2 else 0
        
        result = False
        if condition_type == "GREATER_THAN":
            result = numeric_value > th
        elif condition_type == "LESS_THAN":
            result = numeric_value < th
        elif condition_type == "EQUAL_TO":
            result = numeric_value == th
        elif condition_type == "NOT_EQUAL_TO":
            result = numeric_value != th
        elif condition_type == "BETWEEN":
            result = th2 != 0 and (numeric_value >= th and numeric_value <= th2)
        elif condition_type == "NOT_BETWEEN":
            result = th2 != 0 and not (numeric_value >= th and numeric_value <= th2)
        else:
            print(f"未知条件类型: {condition_type}")
            return False
        
        print(f"条件结果: {numeric_value} {condition_type} {threshold} = {result}")
        return result
        
    except (ValueError, TypeError) as e:
        print(f"解析数值失败: {value}, 错误: {e}")
        return False

def check_conditions(conditions: List[AlarmCondition], telemetry_dict: Dict[str, Any]) -> bool:
    """多条件组合判断，模拟Java checkConditions方法"""
    if not conditions:
        return False
    
    first = conditions[0]
    result = check_single_condition(first, telemetry_dict)
    
    for i in range(1, len(conditions)):
        cond = conditions[i]
        current = check_single_condition(cond, telemetry_dict)
        if cond.relation.upper() == "AND":
            result = result and current
        else:
            result = result or current
    
    return result

def test_trigger_logic():
    """测试触发器逻辑"""
    print("=== IoTDB触发器逻辑测试 ===")
    
    # 模拟从API获取的规则JSON
    rule_json = '''
    {
        "code": 200,
        "msg": "获取成功",
        "data": {
            "id": 1,
            "name": "温度告警规则",
            "description": "监控设备温度",
            "enabled": true,
            "severity": "WARNING",
            "product_id": 1,
            "conditions": [
                {
                    "id": 1,
                    "property_identifier": "temperature",
                    "condition_type": "GREATER_THAN",
                    "threshold_value": "80.0",
                    "relation": "AND"
                },
                {
                    "id": 2,
                    "property_identifier": "humidity",
                    "condition_type": "LESS_THAN",
                    "threshold_value": "30.0",
                    "relation": "OR"
                }
            ]
        }
    }
    '''
    
    # 解析规则
    rule = AlarmRule.from_json(rule_json)
    if not rule:
        print("规则解析失败")
        return
    
    print(f"规则名称: {rule.name}")
    print(f"条件数量: {len(rule.conditions)}")
    
    # 测试不同的遥测数据
    test_cases = [
        {
            "name": "温度过高触发",
            "telemetry": {"temperature": 85.0, "humidity": 50.0}
        },
        {
            "name": "湿度过低触发",
            "telemetry": {"temperature": 75.0, "humidity": 25.0}
        },
        {
            "name": "温度和湿度都正常",
            "telemetry": {"temperature": 75.0, "humidity": 50.0}
        },
        {
            "name": "温度和湿度都异常",
            "telemetry": {"temperature": 85.0, "humidity": 25.0}
        },
        {
            "name": "缺少温度数据",
            "telemetry": {"humidity": 25.0}
        }
    ]
    
    for test_case in test_cases:
        print(f"\n--- 测试: {test_case['name']} ---")
        print(f"遥测数据: {test_case['telemetry']}")
        
        result = check_conditions(rule.conditions, test_case['telemetry'])
        print(f"触发结果: {result}")
        
        if result:
            print("*** 告警触发 ***")
        else:
            print("条件不满足，不触发告警")

if __name__ == "__main__":
    test_trigger_logic() 