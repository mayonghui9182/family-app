import { useState } from 'react';
import { useAppStore } from '@/store';
import type { Reminder } from '@/types';
import {
  Bell, Plus, Trash2, ChevronDown, Clock, Repeat, X,
  Baby, Home, Heart, ShoppingBag, Utensils, Dumbbell
} from 'lucide-react';
import PageHeader from '@/components/common/PageHeader';

const categoryIcons: Record<string, React.ElementType> = {
  '宝宝': Baby,
  '日常': Home,
  '健康': Heart,
  '购物': ShoppingBag,
  '家务': Utensils,
  '账单': Dumbbell,
};

const categoryColors: Record<string, string> = {
  '宝宝': 'bg-pink-50 text-pink-500',
  '日常': 'bg-blue-50 text-blue-500',
  '健康': 'bg-green-50 text-green-500',
  '购物': 'bg-purple-50 text-purple-500',
  '家务': 'bg-amber-50 text-amber-500',
  '账单': 'bg-rose-50 text-rose-500',
};

const repeatLabels: Record<string, string> = {
  once: '仅一次',
  daily: '每天',
  weekly: '每周',
  monthly: '每月',
};

export default function Reminders() {
  const { reminders, addReminder, toggleReminder, deleteReminder } = useAppStore();
  const [showModal, setShowModal] = useState(false);
  const [newReminder, setNewReminder] = useState({
    title: '',
    time: '08:00',
    repeat: 'daily' as Reminder['repeat'],
    category: '日常',
    note: '',
  });
  const [activeCategory, setActiveCategory] = useState<string>('全部');

  const categories = ['全部', ...new Set(reminders.map((r) => r.category))];

  const filteredReminders = activeCategory === '全部'
    ? reminders
    : reminders.filter((r) => r.category === activeCategory);

  const sortedReminders = [...filteredReminders].sort((a, b) => a.time.localeCompare(b.time));

  const handleAdd = () => {
    if (!newReminder.title.trim()) return;
    addReminder({
      ...newReminder,
      enabled: true,
    });
    setNewReminder({
      title: '',
      time: '08:00',
      repeat: 'daily',
      category: '日常',
      note: '',
    });
    setShowModal(false);
  };

  const getCategoryIcon = (category: string) => {
    return categoryIcons[category] || Bell;
  };

  const getCategoryColor = (category: string) => {
    return categoryColors[category] || 'bg-gray-50 text-gray-500';
  };

  return (
    <div>
      <PageHeader
        title="日常提醒"
        subtitle="不错过每一个重要时刻"
        rightElement={
          <button
            onClick={() => setShowModal(true)}
            className="w-10 h-10 rounded-full bg-primary-400 text-white flex items-center justify-center shadow-soft hover:bg-primary-500 transition-all active:scale-95"
          >
            <Plus size={20} />
          </button>
        }
      />

      <div className="px-5 pb-6">
        {/* 分类标签 */}
        <div className="flex gap-2 overflow-x-auto pb-3 mb-4 -mx-5 px-5 scrollbar-hide animate-fade-in">
          {categories.map((cat) => (
            <button
              key={cat}
              onClick={() => setActiveCategory(cat)}
              className={`px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap transition-all ${
                activeCategory === cat
                  ? 'bg-primary-400 text-white shadow-soft'
                  : 'bg-white text-gray-600 shadow-card hover:bg-primary-50'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>

        {/* 提醒列表 */}
        <div className="space-y-3">
          {sortedReminders.map((reminder, index) => {
            const Icon = getCategoryIcon(reminder.category);
            return (
              <div
                key={reminder.id}
                className={`card-base animate-slide-up ${
                  !reminder.enabled ? 'opacity-50' : ''
                }`}
                style={{ animationDelay: `${index * 0.05}s` }}
              >
                <div className="flex items-start gap-3">
                  <div
                    className={`w-12 h-12 rounded-2xl flex items-center justify-center flex-shrink-0 ${getCategoryColor(
                      reminder.category
                    )}`}
                  >
                    <Icon size={22} />
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between">
                      <h3 className={`font-medium ${
                        reminder.enabled ? 'text-gray-800' : 'text-gray-400 line-through'
                      }`}>
                        {reminder.title}
                      </h3>
                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => deleteReminder(reminder.id)}
                          className="p-2 text-gray-300 hover:text-red-400 transition-colors"
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </div>

                    <div className="flex items-center gap-3 mt-1.5 text-sm text-gray-500">
                      <span className="flex items-center gap-1">
                        <Clock size={14} />
                        {reminder.time}
                      </span>
                      <span className="flex items-center gap-1">
                        <Repeat size={14} />
                        {repeatLabels[reminder.repeat]}
                      </span>
                    </div>

                    {reminder.note && (
                      <p className="text-xs text-gray-400 mt-2 bg-gray-50 rounded-lg p-2">
                        {reminder.note}
                      </p>
                    )}

                    {/* 开关 */}
                    <div className="flex items-center justify-end mt-3">
                      <button
                        onClick={() => toggleReminder(reminder.id)}
                        className={`relative w-12 h-7 rounded-full transition-all duration-300 ${
                          reminder.enabled ? 'bg-primary-400' : 'bg-gray-200'
                        }`}
                      >
                        <div
                          className={`absolute top-0.5 w-6 h-6 bg-white rounded-full shadow-md transition-all duration-300 ${
                            reminder.enabled ? 'left-[22px]' : 'left-0.5'
                          }`}
                        />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {sortedReminders.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <Bell size={48} className="mx-auto mb-3 opacity-50" />
            <p>暂无提醒</p>
            <p className="text-sm mt-1">点击右上角添加新提醒</p>
          </div>
        )}
      </div>

      {/* 添加提醒弹窗 */}
      {showModal && (
        <div className="fixed inset-0 z-50 flex items-end justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => setShowModal(false)}
          />
          <div className="relative w-full max-w-[480px] bg-white rounded-t-3xl p-6 animate-slide-up">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-bold text-gray-800 font-rounded">添加提醒</h2>
              <button
                onClick={() => setShowModal(false)}
                className="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center text-gray-500 hover:bg-gray-200"
              >
                <X size={18} />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  提醒内容
                </label>
                <input
                  type="text"
                  value={newReminder.title}
                  onChange={(e) =>
                    setNewReminder({ ...newReminder, title: e.target.value })
                  }
                  placeholder="输入提醒内容..."
                  className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
                />
              </div>

              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  提醒时间
                </label>
                <input
                  type="time"
                  value={newReminder.time}
                  onChange={(e) =>
                    setNewReminder({ ...newReminder, time: e.target.value })
                  }
                  className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
                />
              </div>

              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  重复频率
                </label>
                <div className="grid grid-cols-4 gap-2">
                  {(['once', 'daily', 'weekly', 'monthly'] as const).map((rep) => (
                    <button
                      key={rep}
                      onClick={() => setNewReminder({ ...newReminder, repeat: rep })}
                      className={`py-2.5 rounded-xl text-sm font-medium transition-all ${
                        newReminder.repeat === rep
                          ? 'bg-primary-400 text-white'
                          : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                      }`}
                    >
                      {repeatLabels[rep]}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  分类
                </label>
                <div className="grid grid-cols-3 gap-2">
                  {Object.keys(categoryIcons).map((cat) => {
                    const Icon = categoryIcons[cat];
                    return (
                      <button
                        key={cat}
                        onClick={() =>
                          setNewReminder({ ...newReminder, category: cat })
                        }
                        className={`py-2.5 rounded-xl text-sm font-medium flex items-center justify-center gap-1.5 transition-all ${
                          newReminder.category === cat
                            ? 'bg-primary-400 text-white'
                            : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                        }`}
                      >
                        <Icon size={16} />
                        {cat}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  备注（选填）
                </label>
                <textarea
                  value={newReminder.note}
                  onChange={(e) =>
                    setNewReminder({ ...newReminder, note: e.target.value })
                  }
                  placeholder="添加备注..."
                  rows={2}
                  className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all resize-none"
                />
              </div>
            </div>

            <button
              onClick={handleAdd}
              disabled={!newReminder.title.trim()}
              className="w-full mt-6 btn-primary disabled:opacity-50 disabled:cursor-not-allowed"
            >
              保存提醒
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
