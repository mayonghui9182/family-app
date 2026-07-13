import { useState } from 'react';
import { useAppStore } from '@/store';
import {
  Baby, Cake, Ruler, Scale, Syringe, Star, ChevronRight,
  TrendingUp, Calendar, Heart, Edit3, X, Plus
} from 'lucide-react';
import PageHeader from '@/components/common/PageHeader';
import { calculateAge, formatDate } from '@/utils/storage';

export default function BabyPlan() {
  const { babyInfo, growthRecords, vaccines, milestones, toggleVaccine, toggleMilestone } = useAppStore();
  const [activeTab, setActiveTab] = useState<'growth' | 'vaccine' | 'milestone'>('growth');
  const [showGrowthModal, setShowGrowthModal] = useState(false);
  const [newRecord, setNewRecord] = useState({
    date: formatDate(new Date()),
    height: '',
    weight: '',
  });

  const age = calculateAge(babyInfo.birthday);
  const latestRecord = growthRecords[growthRecords.length - 1];
  const completedVaccines = vaccines.filter((v) => v.completed).length;
  const achievedMilestones = milestones.filter((m) => m.achieved).length;

  const handleAddRecord = () => {
    // 这里简化处理，实际项目中应该有对应的 store action
    setShowGrowthModal(false);
  };

  const tabs = [
    { key: 'growth', label: '成长记录', icon: TrendingUp },
    { key: 'vaccine', label: '疫苗提醒', icon: Syringe },
    { key: 'milestone', label: '里程碑', icon: Star },
  ];

  return (
    <div>
      <PageHeader title="宝宝计划" subtitle="记录成长的每一个瞬间" />

      <div className="px-5 pb-6">
        {/* 宝宝信息卡片 */}
        <div className="relative overflow-hidden rounded-3xl p-6 mb-6 bg-gradient-to-br from-pink-400 via-rose-400 to-orange-400 text-white animate-fade-in">
          <div className="absolute -top-16 -right-16 w-48 h-48 bg-white/10 rounded-full" />
          <div className="absolute -bottom-20 -left-10 w-56 h-56 bg-white/10 rounded-full" />
          
          <div className="relative z-10 flex items-center gap-4">
            <div className="w-20 h-20 rounded-3xl bg-white/20 backdrop-blur-sm flex items-center justify-center overflow-hidden border-2 border-white/30">
              <Baby size={40} className="text-white" />
            </div>
            <div className="flex-1">
              <h2 className="text-2xl font-bold font-rounded">{babyInfo.name}</h2>
              <div className="flex items-center gap-2 mt-1 text-white/90 text-sm">
                <Cake size={14} />
                <span>{age}</span>
              </div>
              <div className="flex items-center gap-2 mt-1 text-white/80 text-xs">
                <Calendar size={12} />
                <span>{babyInfo.birthday}</span>
                <span>·</span>
                <span>{babyInfo.gender === 'girl' ? '小公主' : '小王子'}</span>
              </div>
            </div>
          </div>

          {/* 统计数据 */}
          <div className="relative z-10 grid grid-cols-3 gap-3 mt-5 pt-5 border-t border-white/20">
            <div className="text-center">
              <div className="flex items-center justify-center gap-1 mb-1">
                <Ruler size={14} className="text-white/80" />
                <span className="text-xs text-white/80">身高</span>
              </div>
              <p className="text-xl font-bold font-rounded">
                {latestRecord ? latestRecord.height : '-'}
                <span className="text-sm font-normal">cm</span>
              </p>
            </div>
            <div className="text-center">
              <div className="flex items-center justify-center gap-1 mb-1">
                <Scale size={14} className="text-white/80" />
                <span className="text-xs text-white/80">体重</span>
              </div>
              <p className="text-xl font-bold font-rounded">
                {latestRecord ? latestRecord.weight : '-'}
                <span className="text-sm font-normal">kg</span>
              </p>
            </div>
            <div className="text-center">
              <div className="flex items-center justify-center gap-1 mb-1">
                <Heart size={14} className="text-white/80" />
                <span className="text-xs text-white/80">健康</span>
              </div>
              <p className="text-xl font-bold font-rounded">
                {completedVaccines}<span className="text-sm font-normal">/{vaccines.length}</span>
              </p>
            </div>
          </div>
        </div>

        {/* Tab切换 */}
        <div className="flex bg-white rounded-2xl p-1 shadow-card mb-5 animate-slide-up" style={{ animationDelay: '0.1s' }}>
          {tabs.map((tab) => {
            const Icon = tab.icon;
            return (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key as typeof activeTab)}
                className={`flex-1 py-2.5 rounded-xl text-sm font-medium flex items-center justify-center gap-1.5 transition-all ${
                  activeTab === tab.key
                    ? 'bg-primary-400 text-white shadow-soft'
                    : 'text-gray-500 hover:text-gray-700'
                }`}
              >
                <Icon size={16} />
                {tab.label}
              </button>
            );
          })}
        </div>

        {/* 成长记录 */}
        {activeTab === 'growth' && (
          <div className="animate-fade-in">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-bold text-gray-800 flex items-center gap-2">
                <TrendingUp size={18} className="text-primary-500" />
                成长曲线
              </h3>
              <button
                onClick={() => setShowGrowthModal(true)}
                className="text-sm text-primary-500 flex items-center gap-1"
              >
                <Plus size={16} />
                添加记录
              </button>
            </div>

            {/* 简易成长图表 */}
            <div className="card-base mb-5">
              <div className="h-40 relative">
                {/* 网格线 */}
                <div className="absolute inset-0 flex flex-col justify-between py-2">
                  {[0, 1, 2, 3].map((i) => (
                    <div key={i} className="border-b border-gray-100 w-full" />
                  ))}
                </div>
                {/* 身高曲线 */}
                <svg className="absolute inset-0 w-full h-full" viewBox="0 0 200 100" preserveAspectRatio="none">
                  <defs>
                    <linearGradient id="heightGradient" x1="0%" y1="0%" x2="0%" y2="100%">
                      <stop offset="0%" stopColor="#FF8C42" stopOpacity="0.3" />
                      <stop offset="100%" stopColor="#FF8C42" stopOpacity="0" />
                    </linearGradient>
                  </defs>
                  {/* 填充区域 */}
                  <path
                    d={`M 0 100 ${growthRecords
                      .map((r, i) => {
                        const x = (i / (growthRecords.length - 1 || 1)) * 200;
                        const y = 100 - ((r.height - 40) / 80) * 100;
                        return `L ${x} ${Math.max(5, Math.min(95, y))}`;
                      })
                      .join(' ')} L 200 100 Z`}
                    fill="url(#heightGradient)"
                  />
                  {/* 折线 */}
                  <path
                    d={`M 0 ${100 - ((growthRecords[0]?.height - 40) / 80) * 100 || 50} ${growthRecords
                      .map((r, i) => {
                        const x = (i / (growthRecords.length - 1 || 1)) * 200;
                        const y = 100 - ((r.height - 40) / 80) * 100;
                        return `L ${x} ${Math.max(5, Math.min(95, y))}`;
                      })
                      .join(' ')}`}
                    fill="none"
                    stroke="#FF8C42"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  {/* 数据点 */}
                  {growthRecords.map((r, i) => {
                    const x = (i / (growthRecords.length - 1 || 1)) * 200;
                    const y = 100 - ((r.height - 40) / 80) * 100;
                    return (
                      <circle
                        key={i}
                        cx={x}
                        cy={Math.max(5, Math.min(95, y))}
                        r="3"
                        fill="white"
                        stroke="#FF8C42"
                        strokeWidth="2"
                      />
                    );
                  })}
                </svg>
                {/* Y轴标签 */}
                <div className="absolute left-0 top-0 bottom-0 flex flex-col justify-between py-0 text-[10px] text-gray-400 -ml-1">
                  <span>120</span>
                  <span>100</span>
                  <span>80</span>
                  <span>60</span>
                  <span>40</span>
                </div>
              </div>
              <div className="flex justify-between mt-2 text-xs text-gray-400">
                {growthRecords.slice(0, 5).map((r, i) => (
                  <span key={i}>{r.date.slice(2, 7)}</span>
                ))}
                {growthRecords.length > 5 && <span>...</span>}
              </div>
            </div>

            {/* 历史记录 */}
            <h3 className="font-bold text-gray-800 mb-3">历史记录</h3>
            <div className="space-y-2">
              {[...growthRecords].reverse().map((record, index) => (
                <div key={record.id} className="card-base py-3 flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-xl bg-primary-50 flex items-center justify-center">
                      <Ruler size={18} className="text-primary-500" />
                    </div>
                    <div>
                      <p className="text-sm font-medium text-gray-700">{record.date}</p>
                      <p className="text-xs text-gray-400">第 {index + 1} 次测量</p>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-bold text-gray-800">
                      {record.height} cm · {record.weight} kg
                    </p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 疫苗提醒 */}
        {activeTab === 'vaccine' && (
          <div className="animate-fade-in">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-bold text-gray-800 flex items-center gap-2">
                <Syringe size={18} className="text-primary-500" />
                疫苗接种计划
              </h3>
              <span className="text-sm text-gray-500">
                已完成 {completedVaccines}/{vaccines.length}
              </span>
            </div>

            {/* 进度条 */}
            <div className="card-base mb-5">
              <div className="relative h-3 bg-gray-100 rounded-full overflow-hidden">
                <div
                  className="absolute left-0 top-0 h-full bg-gradient-to-r from-secondary-400 to-green-400 rounded-full transition-all duration-500"
                  style={{ width: `${(completedVaccines / vaccines.length) * 100}%` }}
                />
              </div>
              <p className="text-center mt-2 text-sm text-gray-500">
                已完成 <span className="font-bold text-secondary-500">{Math.round((completedVaccines / vaccines.length) * 100)}%</span>
              </p>
            </div>

            {/* 疫苗列表 */}
            <div className="space-y-3">
              {vaccines.map((vaccine, index) => (
                <div
                  key={vaccine.id}
                  className={`card-base animate-slide-up ${
                    vaccine.completed ? 'opacity-70' : ''
                  }`}
                  style={{ animationDelay: `${index * 0.05}s` }}
                >
                  <div className="flex items-start gap-3">
                    <button
                      onClick={() => toggleVaccine(vaccine.id)}
                      className={`w-6 h-6 rounded-full border-2 flex items-center justify-center flex-shrink-0 mt-0.5 transition-all ${
                        vaccine.completed
                          ? 'bg-secondary-400 border-secondary-400'
                          : 'border-gray-300'
                      }`}
                    >
                      {vaccine.completed && (
                        <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </button>

                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between">
                        <h4
                          className={`font-medium ${
                            vaccine.completed
                              ? 'text-gray-400 line-through'
                              : 'text-gray-800'
                          }`}
                        >
                          {vaccine.name}
                        </h4>
                        <span className={`text-xs px-2 py-0.5 rounded-full ${
                          vaccine.completed
                            ? 'bg-green-50 text-green-500'
                            : 'bg-amber-50 text-amber-500'
                        }`}>
                          {vaccine.completed ? '已接种' : '待接种'}
                        </span>
                      </div>
                      <div className="flex items-center gap-3 mt-1.5 text-xs text-gray-400">
                        <span className="flex items-center gap-1">
                          <Calendar size={12} />
                          {vaccine.date}
                        </span>
                        <span className="flex items-center gap-1">
                          <Baby size={12} />
                          {vaccine.age}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 发育里程碑 */}
        {activeTab === 'milestone' && (
          <div className="animate-fade-in">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-bold text-gray-800 flex items-center gap-2">
                <Star size={18} className="text-primary-500" />
                成长里程碑
              </h3>
              <span className="text-sm text-gray-500">
                已达成 {achievedMilestones}/{milestones.length}
              </span>
            </div>

            {/* 时间线 */}
            <div className="relative">
              {/* 时间线竖线 */}
              <div className="absolute left-5 top-2 bottom-2 w-0.5 bg-gradient-to-b from-primary-300 via-primary-200 to-gray-100" />

              <div className="space-y-4">
                {milestones.map((milestone, index) => (
                  <div
                    key={milestone.id}
                    className="relative pl-14 animate-slide-up"
                    style={{ animationDelay: `${index * 0.08}s` }}
                  >
                    {/* 时间点 */}
                    <div
                      className={`absolute left-0 w-10 h-10 rounded-full flex items-center justify-center ${
                        milestone.achieved
                          ? 'bg-gradient-to-br from-primary-400 to-orange-400 shadow-float'
                          : 'bg-white border-2 border-gray-200'
                      }`}
                    >
                      {milestone.achieved ? (
                        <Star size={18} className="text-white" fill="white" />
                      ) : (
                        <span className="text-xs text-gray-400 font-medium">
                          {index + 1}
                        </span>
                      )}
                    </div>

                    <div
                      className={`card-base ${
                        !milestone.achieved ? 'opacity-70' : ''
                      }`}
                    >
                      <div className="flex items-start justify-between">
                        <div className="flex-1">
                          <h4
                            className={`font-bold ${
                              milestone.achieved ? 'text-gray-800' : 'text-gray-500'
                            }`}
                          >
                            {milestone.title}
                          </h4>
                          <p className="text-sm text-gray-500 mt-1">
                            {milestone.description}
                          </p>
                          <div className="flex items-center gap-3 mt-2 text-xs text-gray-400">
                            <span className="flex items-center gap-1">
                              <Calendar size={12} />
                              {milestone.date}
                            </span>
                            <span className="flex items-center gap-1">
                              <Baby size={12} />
                              {milestone.age}
                            </span>
                          </div>
                        </div>
                        <button
                          onClick={() => toggleMilestone(milestone.id)}
                          className={`px-3 py-1 rounded-full text-xs font-medium transition-all ${
                            milestone.achieved
                              ? 'bg-secondary-100 text-secondary-600'
                              : 'bg-gray-100 text-gray-500 hover:bg-primary-50 hover:text-primary-500'
                          }`}
                        >
                          {milestone.achieved ? '已达成' : '标记达成'}
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 添加成长记录弹窗 */}
      {showGrowthModal && (
        <div className="fixed inset-0 z-50 flex items-end justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => setShowGrowthModal(false)}
          />
          <div className="relative w-full max-w-[480px] bg-white rounded-t-3xl p-6 animate-slide-up">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-bold text-gray-800 font-rounded">添加成长记录</h2>
              <button
                onClick={() => setShowGrowthModal(false)}
                className="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center text-gray-500 hover:bg-gray-200"
              >
                <X size={18} />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                  测量日期
                </label>
                <input
                  type="date"
                  value={newRecord.date}
                  onChange={(e) => setNewRecord({ ...newRecord, date: e.target.value })}
                  className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                    身高 (cm)
                  </label>
                  <input
                    type="number"
                    value={newRecord.height}
                    onChange={(e) => setNewRecord({ ...newRecord, height: e.target.value })}
                    placeholder="身高"
                    className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
                  />
                </div>
                <div>
                  <label className="text-sm font-medium text-gray-700 mb-1.5 block">
                    体重 (kg)
                  </label>
                  <input
                    type="number"
                    step="0.1"
                    value={newRecord.weight}
                    onChange={(e) => setNewRecord({ ...newRecord, weight: e.target.value })}
                    placeholder="体重"
                    className="w-full px-4 py-3 rounded-xl bg-gray-50 border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
                  />
                </div>
              </div>
            </div>

            <button
              onClick={handleAddRecord}
              className="w-full mt-6 btn-primary"
            >
              保存记录
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
