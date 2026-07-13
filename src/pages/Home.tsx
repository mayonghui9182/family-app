import { useNavigate } from 'react-router-dom';
import { useAppStore } from '@/store';
import { Sun, Cloud, CloudRain, Map, Bell, CheckSquare, Baby, ChevronRight, Sparkles, Calendar } from 'lucide-react';
import { getDayOfWeek, formatDate } from '@/utils/storage';

const weatherIcons: Record<string, React.ElementType> = {
  sun: Sun,
  cloud: Cloud,
  'cloud-rain': CloudRain,
  'cloud-sun': Cloud,
};

const quickActions = [
  { path: '/weather', icon: Sun, label: '天气查询', color: 'from-amber-400 to-orange-400', bgColor: 'bg-amber-50' },
  { path: '/travel', icon: Map, label: '旅游攻略', color: 'from-emerald-400 to-teal-400', bgColor: 'bg-emerald-50' },
  { path: '/reminders', icon: Bell, label: '日常提醒', color: 'from-rose-400 to-pink-400', bgColor: 'bg-rose-50' },
  { path: '/todos', icon: CheckSquare, label: '待办清单', color: 'from-violet-400 to-purple-400', bgColor: 'bg-violet-50' },
  { path: '/baby', icon: Baby, label: '宝宝计划', color: 'from-sky-400 to-blue-400', bgColor: 'bg-sky-50' },
];

export default function Home() {
  const navigate = useNavigate();
  const { weather, forecast, todos, reminders, babyInfo } = useAppStore();
  const WeatherIcon = weatherIcons[weather.conditionIcon] || Sun;

  const today = new Date();
  const todayStr = formatDate(today);
  const todayTodos = todos.filter((t) => t.date === todayStr && !t.completed);
  const todayReminders = reminders.filter((r) => r.enabled);

  const getGreeting = () => {
    const hour = today.getHours();
    if (hour < 6) return '夜深了';
    if (hour < 12) return '早上好';
    if (hour < 14) return '中午好';
    if (hour < 18) return '下午好';
    return '晚上好';
  };

  return (
    <div className="px-5 pt-8 pb-6">
      {/* 问候语 */}
      <div className="flex items-center justify-between mb-6 animate-fade-in">
        <div>
          <p className="text-gray-500 text-sm">
            {getDayOfWeek(today)} · {today.getMonth() + 1}月{today.getDate()}日
          </p>
          <h1 className="text-2xl font-bold text-gray-800 mt-1 font-rounded">
            {getGreeting()}，{babyInfo.name ? `${babyInfo.name}的家人` : '亲爱的家人'}
            <Sparkles className="inline-block ml-2 text-amber-400" size={24} />
          </h1>
        </div>
      </div>

      {/* 天气卡片 */}
      <div
        onClick={() => navigate('/weather')}
        className="relative overflow-hidden rounded-3xl p-6 mb-6 cursor-pointer transition-all duration-500 hover:shadow-float hover:-translate-y-1 bg-gradient-to-br from-primary-400 via-primary-500 to-orange-400 text-white animate-slide-up"
        style={{ animationDelay: '0.1s' }}
      >
        <div className="absolute -top-10 -right-10 w-40 h-40 bg-white/10 rounded-full" />
        <div className="absolute -bottom-16 -left-10 w-48 h-48 bg-white/10 rounded-full" />
        
        <div className="relative z-10">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-white/80 text-sm">{weather.city}</p>
              <div className="flex items-end gap-2 mt-1">
                <span className="text-5xl font-bold font-rounded">{weather.temperature}°</span>
                <span className="text-white/80 mb-2">{weather.condition}</span>
              </div>
            </div>
            <WeatherIcon size={60} className="text-white/90 animate-bounce-soft" />
          </div>
          
          <div className="flex items-center gap-4 mt-5 text-sm text-white/80">
            <span>体感 {weather.feelsLike}°</span>
            <span>·</span>
            <span>湿度 {weather.humidity}%</span>
            <span>·</span>
            <span>空气 {weather.airQualityLevel}</span>
          </div>

          <div className="flex items-center justify-between mt-5 pt-4 border-t border-white/20">
            <div className="flex gap-4">
              {forecast.slice(0, 3).map((day, index) => (
                <div key={index} className="text-center">
                  <p className="text-xs text-white/70">{day.dayOfWeek}</p>
                  <p className="text-sm font-medium mt-1">
                    {day.high}°/{day.low}°
                  </p>
                </div>
              ))}
            </div>
            <div className="flex items-center text-sm font-medium">
              查看详情
              <ChevronRight size={16} />
            </div>
          </div>
        </div>
      </div>

      {/* 快捷功能入口 */}
      <div className="mb-6 animate-slide-up" style={{ animationDelay: '0.2s' }}>
        <h2 className="text-lg font-bold text-gray-800 mb-4 font-rounded">
          快捷功能
        </h2>
        <div className="grid grid-cols-5 gap-3">
          {quickActions.map((action, index) => (
            <button
              key={action.path}
              onClick={() => navigate(action.path)}
              className="flex flex-col items-center gap-2 p-3 rounded-2xl bg-white shadow-card hover:shadow-float hover:-translate-y-1 transition-all duration-300"
              style={{ animationDelay: `${0.3 + index * 0.1}s` }}
            >
              <div className={`w-12 h-12 rounded-2xl ${action.bgColor} flex items-center justify-center bg-gradient-to-br ${action.color}`}>
                <action.icon size={24} className="text-white" />
              </div>
              <span className="text-xs font-medium text-gray-600">{action.label}</span>
            </button>
          ))}
        </div>
      </div>

      {/* 今日待办 */}
      <div className="mb-6 animate-slide-up" style={{ animationDelay: '0.3s' }}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-gray-800 font-rounded flex items-center gap-2">
            <Calendar size={20} className="text-primary-500" />
            今日待办
          </h2>
          <button
            onClick={() => navigate('/todos')}
            className="text-sm text-primary-500 flex items-center hover:text-primary-600 transition-colors"
          >
            全部 <ChevronRight size={16} />
          </button>
        </div>
        
        {todayTodos.length > 0 ? (
          <div className="space-y-3">
            {todayTodos.slice(0, 3).map((todo) => (
              <div
                key={todo.id}
                className="card-base flex items-center gap-3 py-3"
              >
                <div
                  className={`w-5 h-5 rounded-full border-2 ${
                    todo.priority === 'high'
                      ? 'border-red-400'
                      : todo.priority === 'medium'
                      ? 'border-amber-400'
                      : 'border-green-400'
                  } flex-shrink-0`}
                />
                <span className="text-gray-700 flex-1">{todo.title}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="card-base text-center py-8 text-gray-400">
            <CheckSquare size={40} className="mx-auto mb-2 opacity-50" />
            <p>今天没有待办事项，享受生活吧~</p>
          </div>
        )}
      </div>

      {/* 今日提醒 */}
      <div className="animate-slide-up" style={{ animationDelay: '0.4s' }}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-gray-800 font-rounded flex items-center gap-2">
            <Bell size={20} className="text-secondary-500" />
            今日提醒
          </h2>
          <button
            onClick={() => navigate('/reminders')}
            className="text-sm text-primary-500 flex items-center hover:text-primary-600 transition-colors"
          >
            全部 <ChevronRight size={16} />
          </button>
        </div>
        
        {todayReminders.length > 0 ? (
          <div className="space-y-3">
            {todayReminders.slice(0, 3).map((reminder) => (
              <div
                key={reminder.id}
                className="card-base flex items-center gap-3 py-3"
              >
                <div className="w-10 h-10 rounded-xl bg-primary-50 flex items-center justify-center flex-shrink-0">
                  <Bell size={18} className="text-primary-500" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-gray-800 font-medium truncate">{reminder.title}</p>
                  <p className="text-xs text-gray-400 mt-0.5">{reminder.time}</p>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="card-base text-center py-8 text-gray-400">
            <Bell size={40} className="mx-auto mb-2 opacity-50" />
            <p>暂无提醒</p>
          </div>
        )}
      </div>
    </div>
  );
}
