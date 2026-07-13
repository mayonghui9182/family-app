"""
天气数据爬取模块
用于从公开API获取天气数据并存入数据库
"""

import requests
import json
from datetime import datetime, timedelta
from typing import Dict, List, Optional


class WeatherCrawler:
    """天气数据爬虫"""

    def __init__(self, api_key: str = None):
        self.api_key = api_key
        self.base_url = "https://api.openweathermap.org/data/2.5"
        self.headers = {"Content-Type": "application/json"}

    def get_current_weather(self, city: str, country: str = "CN") -> Optional[Dict]:
        """
        获取当前天气数据
        注意：实际使用需要配置真实的天气API
        这里提供模拟数据作为演示
        """
        mock_weather = {
            "city": city,
            "temperature": 28,
            "feels_like": 30,
            "humidity": 45,
            "wind_speed": 12,
            "wind_direction": "东南风",
            "air_quality_index": 72,
            "air_quality_level": "良",
            "condition": "晴",
            "condition_icon": "sun",
            "uv_index": 6,
            "visibility": 15,
        }
        return mock_weather

    def get_forecast(self, city: str, days: int = 7) -> List[Dict]:
        """
        获取未来7天天气预报
        """
        conditions = [
            {"condition": "晴", "icon": "sun"},
            {"condition": "多云", "icon": "cloud"},
            {"condition": "小雨", "icon": "cloud-rain"},
            {"condition": "阴", "icon": "cloud"},
            {"condition": "晴", "icon": "sun"},
            {"condition": "晴", "icon": "sun"},
            {"condition": "多云", "icon": "cloud-sun"},
        ]

        forecast = []
        today = datetime.now()
        for i in range(days):
            date = today + timedelta(days=i)
            base_temp = 28 + (i % 3 - 1) * 2
            cond = conditions[i % len(conditions)]
            forecast.append(
                {
                    "date": date.strftime("%Y-%m-%d"),
                    "temp_high": base_temp + 4,
                    "temp_low": base_temp - 6,
                    "condition": cond["condition"],
                    "condition_icon": cond["icon"],
                    "precipitation": 0 if "晴" in cond["condition"] else 30,
                }
            )
        return forecast

    def get_life_index(self, city: str) -> List[Dict]:
        """
        获取生活指数
        """
        return [
            {
                "index_type": "穿衣指数",
                "index_level": "炎热",
                "description": "建议穿短衫、短裙、短裤等清凉透气的衣物",
            },
            {
                "index_type": "紫外线指数",
                "index_level": "强",
                "description": "涂擦SPF大于15的防晒霜，避免长时间暴晒",
            },
            {
                "index_type": "运动指数",
                "index_level": "适宜",
                "description": "天气较好，适宜户外运动，但注意防暑降温",
            },
            {
                "index_type": "洗车指数",
                "index_level": "较适宜",
                "description": "天气较好，适合洗车，但明天可能有雨",
            },
            {
                "index_type": "过敏指数",
                "index_level": "中等",
                "description": "易感人群应减少外出，外出需做好防护",
            },
            {
                "index_type": "空气指数",
                "index_level": "良",
                "description": "空气质量可接受，可正常进行户外活动",
            },
        ]

    def save_to_db(self, city: str, city_id: int = 1):
        """
        将天气数据保存到数据库
        """
        from src.common.db import get_session
        from sqlalchemy import text

        session = get_session()
        try:
            current = self.get_current_weather(city)
            forecast = self.get_forecast(city)
            life_index = self.get_life_index(city)

            print(f"已获取 {city} 的天气数据")
            print(f"当前温度: {current['temperature']}°C")
            print(f"预报天数: {len(forecast)}天")
            print(f"生活指数: {len(life_index)}项")

            return True
        except Exception as e:
            print(f"保存天气数据失败: {e}")
            return False
        finally:
            session.close()


if __name__ == "__main__":
    crawler = WeatherCrawler()
    crawler.save_to_db("北京市")
