import { NavLink } from 'react-router-dom';
import { Home, CloudSun, Map, Bell, CheckSquare, Baby } from 'lucide-react';

const navItems = [
  { path: '/', icon: Home, label: '首页' },
  { path: '/weather', icon: CloudSun, label: '天气' },
  { path: '/travel', icon: Map, label: '旅游' },
  { path: '/reminders', icon: Bell, label: '提醒' },
  { path: '/todos', icon: CheckSquare, label: '待办' },
  { path: '/baby', icon: Baby, label: '宝宝' },
];

export default function BottomNav() {
  return (
    <nav className="fixed bottom-0 left-1/2 -translate-x-1/2 w-full max-w-[480px] bg-white/95 backdrop-blur-lg border-t border-primary-100 px-2 py-2 z-50">
      <div className="flex justify-around items-center">
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
          >
            {({ isActive }) => (
              <div
                className={`flex flex-col items-center gap-1 px-3 py-2 rounded-2xl transition-all duration-300 ${
                  isActive
                    ? 'text-primary-500 bg-primary-50 scale-105'
                    : 'text-gray-400 hover:text-primary-400 hover:bg-primary-50/50'
                }`}
              >
                <item.icon size={22} strokeWidth={isActive ? 2.5 : 2} />
                <span className="text-xs font-medium">{item.label}</span>
              </div>
            )}
          </NavLink>
        ))}
      </div>
    </nav>
  );
}
