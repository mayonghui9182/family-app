"""
数据初始化脚本
用于初始化数据库中的基础数据
"""

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def init_weather_data():
    """初始化天气数据"""
    print("🌤️  初始化天气数据...")
    from src.crawlers.weather import WeatherCrawler

    crawler = WeatherCrawler()
    crawler.save_to_db("北京市", city_id=1)
    print("✅ 天气数据初始化完成\n")


def init_growth_analysis_demo():
    """成长分析演示"""
    print("📊 运行成长数据分析演示...")
    from src.analysis.growth_stats import analyze_growth

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


def main():
    """主函数"""
    print("\n" + "=" * 50)
    print("  家庭生活APP - Python数据初始化脚本")
    print("=" * 50 + "\n")

    init_weather_data()
    init_growth_analysis_demo()

    print("\n" + "=" * 50)
    print("  所有初始化任务完成！")
    print("=" * 50 + "\n")


if __name__ == "__main__":
    main()
