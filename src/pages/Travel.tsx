import { useNavigate } from 'react-router-dom';
import { mockTravelGuides } from '@/data/mockData';
import { MapPin, Clock, Wallet, Calendar, ChevronRight, Search } from 'lucide-react';
import { useState } from 'react';
import PageHeader from '@/components/common/PageHeader';

export default function Travel() {
  const navigate = useNavigate();
  const [searchTerm, setSearchTerm] = useState('');

  const filteredGuides = mockTravelGuides.filter(
    (guide) =>
      guide.title.toLowerCase().includes(searchTerm.toLowerCase()) ||
      guide.destination.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const destinations = [...new Set(mockTravelGuides.map((g) => g.destination))];

  return (
    <div>
      <PageHeader title="旅游攻略" subtitle="发现美好的亲子旅行目的地" />

      <div className="px-5 pb-6">
        {/* 搜索框 */}
        <div className="relative mb-6 animate-fade-in">
          <Search
            size={20}
            className="absolute left-4 top-1/2 -translate-y-1/2 text-gray-400"
          />
          <input
            type="text"
            placeholder="搜索目的地或攻略..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pl-12 pr-4 py-3 rounded-2xl bg-white shadow-card border-0 focus:outline-none focus:ring-2 focus:ring-primary-300 transition-all"
          />
        </div>

        {/* 热门目的地标签 */}
        <div className="mb-6 animate-slide-up" style={{ animationDelay: '0.1s' }}>
          <h3 className="text-sm font-bold text-gray-700 mb-3 flex items-center gap-2">
            <MapPin size={16} className="text-primary-500" />
            热门目的地
          </h3>
          <div className="flex gap-2 flex-wrap">
            {destinations.map((dest) => (
              <button
                key={dest}
                onClick={() => setSearchTerm(dest)}
                className={`px-4 py-2 rounded-full text-sm font-medium transition-all ${
                  searchTerm === dest
                    ? 'bg-primary-400 text-white shadow-soft'
                    : 'bg-white text-gray-600 shadow-card hover:bg-primary-50 hover:text-primary-500'
                }`}
              >
                {dest}
              </button>
            ))}
            {searchTerm && (
              <button
                onClick={() => setSearchTerm('')}
                className="px-4 py-2 rounded-full text-sm font-medium bg-gray-100 text-gray-500 hover:bg-gray-200 transition-all"
              >
                清除
              </button>
            )}
          </div>
        </div>

        {/* 攻略列表 */}
        <div className="space-y-4">
          {filteredGuides.map((guide, index) => (
            <div
              key={guide.id}
              onClick={() => navigate(`/travel/${guide.id}`)}
              className="card-base card-hover overflow-hidden p-0 animate-slide-up"
              style={{ animationDelay: `${0.2 + index * 0.1}s` }}
            >
              <div className="relative h-40 overflow-hidden">
                <img
                  src={guide.image}
                  alt={guide.title}
                  className="w-full h-full object-cover transition-transform duration-500 hover:scale-110"
                />
                <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />
                <div className="absolute bottom-3 left-4 right-4">
                  <div className="flex items-center gap-2 text-white/90 text-sm mb-1">
                    <MapPin size={14} />
                    <span>{guide.destination}</span>
                  </div>
                  <h3 className="text-white font-bold text-lg font-rounded">
                    {guide.title}
                  </h3>
                </div>
              </div>
              
              <div className="p-4">
                <p className="text-gray-500 text-sm line-clamp-2 mb-3">
                  {guide.description}
                </p>
                
                <div className="flex items-center gap-4 text-xs text-gray-400 mb-3">
                  <span className="flex items-center gap-1">
                    <Clock size={14} />
                    {guide.duration}
                  </span>
                  <span className="flex items-center gap-1">
                    <Wallet size={14} />
                    {guide.budget}
                  </span>
                  <span className="flex items-center gap-1">
                    <Calendar size={14} />
                    {guide.bestSeason}
                  </span>
                </div>

                <div className="flex flex-wrap gap-2 mb-3">
                  {guide.highlights.slice(0, 3).map((tag) => (
                    <span
                      key={tag}
                      className="px-2 py-1 bg-primary-50 text-primary-500 rounded-lg text-xs"
                    >
                      {tag}
                    </span>
                  ))}
                </div>

                <div className="flex items-center justify-end text-primary-500 text-sm font-medium">
                  查看详情
                  <ChevronRight size={16} />
                </div>
              </div>
            </div>
          ))}
        </div>

        {filteredGuides.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <MapPin size={48} className="mx-auto mb-3 opacity-50" />
            <p>没有找到相关攻略</p>
            <p className="text-sm mt-1">换个关键词试试吧</p>
          </div>
        )}
      </div>
    </div>
  );
}
