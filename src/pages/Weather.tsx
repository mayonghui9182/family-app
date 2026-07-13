import { useAppStore } from '@/store';
import {
  Sun, Cloud, CloudRain, CloudSun, Wind, Droplets, Eye, Thermometer,
  Shirt, Dumbbell, Car, Flower2, MapPin, Droplet
} from 'lucide-react';
import PageHeader from '@/components/common/PageHeader';

const weatherIcons: Record<string, React.ElementType> = {
  sun: Sun,
  cloud: Cloud,
  'cloud-rain': CloudRain,
  'cloud-sun': CloudSun,
};

const lifeIndexIcons: Record<string, React.ElementType> = {
  shirt: Shirt,
  sun: Sun,
  dumbbell: Dumbbell,
  car: Car,
  'flower-2': Flower2,
  wind: Wind,
};

export default function Weather() {
  const { weather, forecast, lifeIndex } = useAppStore();
  const WeatherIcon = weatherIcons[weather.conditionIcon] || Sun;

  const getAirQualityColor = (level: string) => {
    const colors: Record<string, string> = {
      '优': 'text-green-500 bg-green-50',
      '良': 'text-amber-500 bg-amber-50',
      '轻度污染': 'text-orange-500 bg-orange-50',
      '中度污染': 'text-red-500 bg-red-50',
    };
    return colors[level] || 'text-gray-500 bg-gray-50';
  };

  return (
    <div>
      <PageHeader
        title="天气查询"
        subtitle={
          <span className="flex items-center gap-1">
            <MapPin size={14} />
            {weather.city}
          </span>
        }
      />

      <div className="px-5 pb-6 space-y-6">
        {/* 当前天气大卡片 */}
        <div className="relative overflow-hidden rounded-3xl p-8 bg-gradient-to-br from-primary-400 via-primary-500 to-orange-400 text-white shadow-float animate-fade-in">
          <div className="absolute -top-20 -right-20 w-60 h-60 bg-white/10 rounded-full" />
          <div className="absolute -bottom-24 -left-16 w-64 h-64 bg-white/10 rounded-full" />
          <div className="absolute top-1/2 right-10 w-32 h-32 bg-white/5 rounded-full" />
          
          <div className="relative z-10 text-center">
            <div className="flex items-center justify-center mb-4">
              <WeatherIcon size={80} className="text-white animate-float" strokeWidth={1.5} />
            </div>
            <div className="text-7xl font-bold font-rounded mb-2">
              {weather.temperature}°
            </div>
            <div className="text-xl text-white/90 font-medium">{weather.condition}</div>
            <div className="flex items-center justify-center gap-4 mt-3 text-sm text-white/80">
              <span>体感 {weather.feelsLike}°</span>
              <span>·</span>
              <span>{weather.humidity}% 湿度</span>
            </div>
          </div>
        </div>

        {/* 详细数据 */}
        <div className="grid grid-cols-2 gap-4 animate-slide-up" style={{ animationDelay: '0.1s' }}>
          <div className="card-base">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-blue-50 flex items-center justify-center">
                <Droplets size={20} className="text-blue-500" />
              </div>
              <span className="text-gray-500 text-sm">湿度</span>
            </div>
            <p className="text-2xl font-bold text-gray-800 font-rounded">{weather.humidity}%</p>
          </div>
          
          <div className="card-base">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-cyan-50 flex items-center justify-center">
                <Wind size={20} className="text-cyan-500" />
              </div>
              <span className="text-gray-500 text-sm">风力</span>
            </div>
            <p className="text-2xl font-bold text-gray-800 font-rounded">{weather.windSpeed} km/h</p>
          </div>
          
          <div className="card-base">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-amber-50 flex items-center justify-center">
                <Sun size={20} className="text-amber-500" />
              </div>
              <span className="text-gray-500 text-sm">紫外线</span>
            </div>
            <p className="text-2xl font-bold text-gray-800 font-rounded">{weather.uvIndex} 级</p>
          </div>
          
          <div className="card-base">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-gray-50 flex items-center justify-center">
                <Eye size={20} className="text-gray-500" />
              </div>
              <span className="text-gray-500 text-sm">能见度</span>
            </div>
            <p className="text-2xl font-bold text-gray-800 font-rounded">{weather.visibility} km</p>
          </div>
        </div>

        {/* 空气质量 */}
        <div className="card-base animate-slide-up" style={{ animationDelay: '0.2s' }}>
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-bold text-gray-800 flex items-center gap-2">
              <Thermometer size={18} className="text-primary-500" />
              空气质量
            </h3>
            <span className={`px-3 py-1 rounded-full text-sm font-medium ${getAirQualityColor(weather.airQualityLevel)}`}>
              {weather.airQualityLevel}
            </span>
          </div>
          <div className="relative h-3 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="absolute left-0 top-0 h-full bg-gradient-to-r from-green-400 via-yellow-400 to-red-400 rounded-full"
              style={{ width: `${Math.min(100, parseInt(weather.airQuality) / 2)}%` }}
            />
          </div>
          <div className="flex justify-between mt-2 text-xs text-gray-400">
            <span>优 0-50</span>
            <span>良 51-100</span>
            <span>轻度 101-150</span>
          </div>
          <p className="text-center mt-3 text-lg font-bold text-gray-700">
            AQI {weather.airQuality}
          </p>
        </div>

        {/* 7天预报 */}
        <div className="card-base animate-slide-up" style={{ animationDelay: '0.3s' }}>
          <h3 className="font-bold text-gray-800 mb-4 flex items-center gap-2">
            <CloudSun size={18} className="text-primary-500" />
            未来7天
          </h3>
          <div className="space-y-3">
            {forecast.map((day, index) => {
              const DayIcon = weatherIcons[day.conditionIcon] || Sun;
              const tempRange = day.high - day.low;
              const lowPos = ((day.low - 15) / 25) * 100;
              const highPos = ((day.high - 15) / 25) * 100;
              
              return (
                <div
                  key={index}
                  className="flex items-center gap-4 py-2 border-b border-gray-50 last:border-0"
                >
                  <div className="w-14 flex-shrink-0">
                    <p className="text-sm font-medium text-gray-700">{day.dayOfWeek}</p>
                    <p className="text-xs text-gray-400">{day.date}</p>
                  </div>
                  
                  <div className="w-10 flex-shrink-0">
                    <DayIcon size={24} className="text-primary-400" />
                  </div>
                  
                  <div className="flex-1">
                    <div className="relative h-2 bg-gray-100 rounded-full">
                      <div
                        className="absolute h-full bg-gradient-to-r from-blue-400 to-orange-400 rounded-full"
                        style={{
                          left: `${Math.max(0, lowPos)}%`,
                          right: `${Math.max(0, 100 - highPos)}%`,
                        }}
                      />
                    </div>
                  </div>
                  
                  <div className="w-20 text-right flex-shrink-0">
                    <span className="text-gray-400">{day.low}°</span>
                    <span className="mx-1 text-gray-300">/</span>
                    <span className="text-gray-700 font-medium">{day.high}°</span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* 生活指数 */}
        <div className="animate-slide-up" style={{ animationDelay: '0.4s' }}>
          <h3 className="font-bold text-gray-800 mb-4 flex items-center gap-2">
            <Droplet size={18} className="text-primary-500" />
            生活指数
          </h3>
          <div className="grid grid-cols-2 gap-3">
            {lifeIndex.map((item, index) => {
              const Icon = lifeIndexIcons[item.icon] || Sun;
              return (
                <div key={item.id} className="card-base">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="w-10 h-10 rounded-xl bg-primary-50 flex items-center justify-center">
                      <Icon size={20} className="text-primary-500" />
                    </div>
                    <span className="text-sm font-medium text-gray-700">{item.name}</span>
                  </div>
                  <p className="text-primary-500 font-bold mb-1">{item.level}</p>
                  <p className="text-xs text-gray-400 line-clamp-2">{item.description}</p>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
