import { useState } from 'react';
import { useAppStore } from '@/store';
import type { Todo } from '@/types';
import {
  CheckSquare, Plus, Trash2, Calendar, Flag, X,
  ShoppingBag, Baby, Home, Heart, Briefcase, Star
} from 'lucide-react';
import PageHeader from '@/components/common/PageHeader';
import { formatDate } from '@/utils/storage';

const priorityConfig = {
  high: { label: '高', color: 'text-red-500', bg: 'bg-red-50', border: 'border-red-400' },
  medium: { label: '中', color: 'text-amber-500', bg: 'bg-amber-50', border: 'border-amber-400' },
  low: { label: '低', color: 'text-green-500', bg: 'bg-green-50', border: 'border-green-400' },
};

const categoryIcons: Record<string, React.ElementType> = {
  '购物': ShoppingBag,
  '孩子': Baby,
  '家务': Home,
  '健康': Heart,
  '工作': Briefcase,
  '亲子': Star,
  '其他': CheckSquare,
};

const categories = ['购物', '孩子', '家务', '健康', '工作', '亲子', '其他'];

export default function Todos() {
  const { todos, addTodo, toggleTodo, deleteTodo } = useAppStore();
  const [showModal, setShowModal] = useState(false);
  const [newTodo, setNewTodo] = useState({
    title: '',
    priority: 'medium' as Todo['priority'],
    category: '其他',
    date: formatDate(new Date()),
  });
  const [filter, setFilter] = useState<'all' | 'active' | 'completed'>('all');

  const filteredTodos = todos.filter((todo) => {
    if (filter === 'active') return !todo.completed;
    if (filter === 'completed') return todo.completed;
    return true;
  });

  const sortedTodos = [...filteredTodos].sort((a, b) => {
    const priorityOrder = { high: 0, medium: 1, low: 2 };
    if (a.completed !== b.completed) return a.completed ? 1 : -1;
    return priorityOrder[a.priority] - priorityOrder[b.priority];
  });

  const handleAdd = () => {
    if (!newTodo.title.trim()) return;
    addTodo({
      ...newTodo,
      completed: false,
    });
    setNewTodo({
      title: '',
      priority: 'medium',
      category: '其他',
      date: formatDate(new Date()),
    });
    setShowModal(false);
  };

  const activeCount = todos.filter((t) => !t.completed).length;
  const completedCount = todos.filter((t) => t.completed).length;

  return (
    <div>
      <PageHeader
        title="待办清单"
        subtitle={`还有 ${activeCount} 件事待完成`}
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
        {/* 统计卡片 */}
        <div className="grid grid-cols-3 gap-3 mb-6 animate-fade-in">
          <div className="card-base text-center p-4">
            <p className="text-2xl font-bold text-primary-500 font-rounded">{todos.length}</p>
            <p className="text-xs text-gray-500 mt-1">全部</p>
          </div>
          <div className="card-base text-center p-4">
            <p className="text-2xl font-bold text-amber-500 font-rounded">{activeCount}</p>
            <p className="text-xs text-gray-500 mt-1">进行中</p>
          </div>
          <div className="card-base text-center p-4">
            <p className="text-2xl font-bold text-green-500 font-rounded">{completedCount}</p>
            <p className="text-xs text-gray-500 mt-1">已完成</p>
          </div>
        </div>

        {/* 筛选标签 */}
        <div className="flex gap-2 mb-4 animate-slide-up" style={{ animationDelay: '0.1s' }}>
          {[
            { key: 'all', label: '全部' },
            { key: 'active', label: '进行中' },
            { key: 'completed', label: '已完成' },
          ].map((item) => (
            <button
              key={item.key}
              onClick={() => setFilter(item.key as typeof filter)}
              className={`px-4 py-2 rounded-full text-sm font-medium transition-all ${
                filter === item.key
                  ? 'bg-primary-400 text-white shadow-soft'
                  : 'bg-white text-gray-600 shadow-card hover:bg-primary-50'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        {/* 待办列表 */}
        <div className="space-y-3">
          {sortedTodos.map((todo, index) => {
            const priority = priorityConfig[todo.priority];
            const CatIcon = categoryIcons[todo.category || '其他'] || CheckSquare;
            return (
              <div
                key={todo.id}
                className={`card-base animate-slide-up transition-all duration-300 ${
                  todo.completed ? 'opacity-60' : ''
                }`}
                style={{ animationDelay: `${index * 0.05}s` }}
              >
                <div className="flex items-start gap-3">
                  <button
                    onClick={() => toggleTodo(todo.id)}
                    className={`w-6 h-6 rounded-full border-2 flex items-center justify-center flex-shrink-0 mt-0.5 transition-all ${
                      todo.completed
                        ? 'bg-secondary-400 border-secondary-400'
                        : priority.border
                    }`}
                  >
                    {todo.completed && (
                      <CheckSquare size={14} className="text-white" fill="white" />
                    )}
                  </button>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <h3
                        className={`font-medium flex-1 ${
                          todo.completed
                            ? 'text-gray-400 line-through'
                            : 'text-gray-800'
                        }`}
                      >
                        {todo.title}
                      </h3>
                      <button
                        onClick={() => deleteTodo(todo.id)}
                        className="p-1 text-gray-300 hover:text-red-400 transition-colors"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>

                    <div className="flex items-center gap-3 mt-2">
                      <span
                        className={`px-2 py-0.5 rounded-lg text-xs font-medium ${priority.bg} ${priority.color} flex items-center gap-1`}
                      >
                        <Flag size={12} />
                        {priority.label}
                      </span>
                      <span className="flex items-center gap-1 text-xs text-gray-400">
                        <CatIcon size={12} />
                        {todo.category}
                      </span>
                      <span className="flex items-center gap-1 text-xs text-gray-400">
                        <Calendar size={12} />
                        {todo.date.slice(5)}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {sortedTodos.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <CheckSquare size={48} className="mx-auto mb-3 opacity-50" />
            <p>
              {filter === 'completed' ? '还没有完成的任务' : filter === 'active' ? '太棒了，没有待办！' : '暂无待办事项'}
            </p>
            <p className="text-sm mt-1">点击右上角添加新任务</p>
          </div>
        )}
      </div>

      {/* 添加待办弹窗 */}
      {showModal && (
        <div className="fixed inset-0 z-50 flex items-end justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => setShowModal(false)}
          />
          <div className="relative w-full max-w-[480px] bg-white rounded-t-3xl p-6 animate-slide-up">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-bold text-gray-800 font-rounded">添加待办</h2>
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
                  任务内容
                </label>
                <input
                  type="text"
                  value={newTodo.title}
                  onChange={(e) => setNewTodo({ ...newTodo, title: e.target.value })}
                  placeholder="输入待办事项..."
                  className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
                />
              </div>

              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  优先级
                </label>
                <div className="grid grid-cols-3 gap-2">
                  {(['high', 'medium', 'low'] as const).map((p) => {
                    const config = priorityConfig[p];
                    return (
                      <button
                        key={p}
                        onClick={() => setNewTodo({ ...newTodo, priority: p })}
                        className={`py-2.5 rounded-xl text-sm font-medium transition-all flex items-center justify-center gap-1.5 ${
                          newTodo.priority === p
                            ? `${config.bg} ${config.color} ring-2 ring-current`
                            : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
                        }`}
                      >
                        <Flag size={14} />
                        {config.label}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  分类
                </label>
                <div className="grid grid-cols-4 gap-2">
                  {categories.map((cat) => {
                    const Icon = categoryIcons[cat] || CheckSquare;
                    return (
                      <button
                        key={cat}
                        onClick={() => setNewTodo({ ...newTodo, category: cat })}
                        className={`py-2 rounded-xl text-xs font-medium flex flex-col items-center gap-1 transition-all ${
                          newTodo.category === cat
                            ? 'bg-primary-100 text-primary-600'
                            : 'bg-gray-50 text-gray-500 hover:bg-gray-100'
                        }`}
                      >
                        <Icon size={18} />
                        {cat}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  日期
                </label>
                <input
                  type="date"
                  value={newTodo.date}
                  onChange={(e) => setNewTodo({ ...newTodo, date: e.target.value })}
                  className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
                />
              </div>
            </div>

            <button
              onClick={handleAdd}
              disabled={!newTodo.title.trim()}
              className="w-full mt-6 btn-primary disabled:opacity-50 disabled:cursor-not-allowed"
            >
              添加待办
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
