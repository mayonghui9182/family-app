"""
宝宝成长数据分析模块
用于分析宝宝成长数据，生成统计报表
"""

import pandas as pd
from datetime import datetime
from typing import Dict, List, Tuple


class GrowthAnalyzer:
    """成长数据分析器"""

    def __init__(self, growth_records: List[Dict] = None):
        self.records = growth_records or []
        self.df = pd.DataFrame(self.records) if self.records else pd.DataFrame()

    def set_records(self, records: List[Dict]):
        """设置成长记录数据"""
        self.records = records
        self.df = pd.DataFrame(records)
        if not self.df.empty:
            self.df["date"] = pd.to_datetime(self.df["date"])
            self.df = self.df.sort_values("date")

    def get_height_stats(self) -> Dict:
        """身高统计"""
        if self.df.empty or "height" not in self.df.columns:
            return {"current": 0, "growth_total": 0, "growth_per_month": 0}

        current = self.df["height"].iloc[-1]
        first = self.df["height"].iloc[0]
        growth_total = current - first

        months = max(len(self.df) / 3, 1)
        growth_per_month = growth_total / months

        return {
            "current": round(current, 1),
            "growth_total": round(growth_total, 1),
            "growth_per_month": round(growth_per_month, 1),
        }

    def get_weight_stats(self) -> Dict:
        """体重统计"""
        if self.df.empty or "weight" not in self.df.columns:
            return {"current": 0, "growth_total": 0, "growth_per_month": 0}

        current = self.df["weight"].iloc[-1]
        first = self.df["weight"].iloc[0]
        growth_total = current - first

        months = max(len(self.df) / 3, 1)
        growth_per_month = growth_total / months

        return {
            "current": round(current, 1),
            "growth_total": round(growth_total, 1),
            "growth_per_month": round(growth_per_month, 2),
        }

    def get_bmi(self) -> Dict:
        """BMI计算"""
        if self.df.empty:
            return {"bmi": 0, "status": "未知"}

        height = self.df["height"].iloc[-1] / 100
        weight = self.df["weight"].iloc[-1]
        bmi = weight / (height * height)

        if bmi < 18.5:
            status = "偏瘦"
        elif bmi < 24:
            status = "正常"
        elif bmi < 28:
            status = "偏胖"
        else:
            status = "肥胖"

        return {"bmi": round(bmi, 1), "status": status}

    def get_growth_trend(self) -> Dict:
        """成长趋势分析"""
        if self.df.empty or len(self.df) < 2:
            return {"height_trend": "stable", "weight_trend": "stable"}

        recent_3 = self.df.tail(3)
        height_diff = recent_3["height"].diff().mean()
        weight_diff = recent_3["weight"].diff().mean()

        def get_trend(diff, threshold):
            if diff > threshold:
                return "rising"
            elif diff < -threshold:
                return "falling"
            return "stable"

        return {
            "height_trend": get_trend(height_diff, 0.5),
            "weight_trend": get_trend(weight_diff, 0.1),
        }

    def generate_report(self, baby_name: str = "宝宝") -> str:
        """生成成长报告"""
        height_stats = self.get_height_stats()
        weight_stats = self.get_weight_stats()
        bmi_info = self.get_bmi()
        trend = self.get_growth_trend()

        report = f"""
══════════════════════════════════
      {baby_name}成长分析报告
══════════════════════════════════

📊 当前数据
  身高: {height_stats['current']} cm
  体重: {weight_stats['current']} kg
  BMI:  {bmi_info['bmi']} ({bmi_info['status']})

📈 累计增长
  身高增长: {height_stats['growth_total']} cm
  体重增长: {weight_stats['growth_total']} kg

📅 月均增长
  身高: {height_stats['growth_per_month']} cm/月
  体重: {weight_stats['growth_per_month']} kg/月

📉 近期趋势
  身高趋势: {trend['height_trend']}
  体重趋势: {trend['weight_trend']}

💡 建议
  继续保持均衡营养
  保证充足睡眠
  适当户外活动

══════════════════════════════════
  生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
══════════════════════════════════
"""
        return report


def analyze_growth(records: List[Dict], baby_name: str = "宝宝") -> str:
    """便捷函数：分析成长数据并返回报告"""
    analyzer = GrowthAnalyzer(records)
    return analyzer.generate_report(baby_name)


if __name__ == "__main__":
    sample_data = [
        {"date": "2022-03-15", "height": 50, "weight": 3.2},
        {"date": "2022-06-15", "height": 62, "weight": 6.5},
        {"date": "2022-09-15", "height": 70, "weight": 8.2},
        {"date": "2022-12-15", "height": 76, "weight": 9.5},
        {"date": "2023-03-15", "height": 82, "weight": 10.8},
        {"date": "2023-06-15", "height": 88, "weight": 12.0},
        {"date": "2023-09-15", "height": 92, "weight": 13.2},
        {"date": "2023-12-15", "height": 96, "weight": 14.0},
        {"date": "2024-03-15", "height": 100, "weight": 15.2},
        {"date": "2024-06-15", "height": 104, "weight": 16.5},
    ]

    report = analyze_growth(sample_data, "小橙子")
    print(report)
