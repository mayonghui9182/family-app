import { useParams, useNavigate } from 'react-router-dom';
import { mockTravelGuides } from '@/data/mockData';
import { ArrowLeft, MapPin, Clock, Wallet, Calendar, Star, Share2 } from 'lucide-react';

export default function TravelDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const guide = mockTravelGuides.find((g) => g.id === id);

  if (!guide) {
    return (
      <div className="min-h-screen flex items-center justify-center text-gray-500">
        <div className="text-center">
          <p className="mb-4">攻略不存在</p>
          <button
            onClick={() => navigate('/travel')}
            className="btn-primary"
          >
            返回列表
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-white">
      {/* 顶部图片和返回按钮 */}
      <div className="relative h-64 overflow-hidden">
        <img
          src={guide.image}
          alt={guide.title}
          className="w-full h-full object-cover"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-black/20 to-transparent" />
        
        <button
          onClick={() => navigate(-1)}
          className="absolute top-6 left-4 w-10 h-10 rounded-full bg-white/20 backdrop-blur-md flex items-center justify-center text-white hover:bg-white/30 transition-all"
        >
          <ArrowLeft size={20} />
        </button>
        
        <button className="absolute top-6 right-4 w-10 h-10 rounded-full bg-white/20 backdrop-blur-md flex items-center justify-center text-white hover:bg-white/30 transition-all">
          <Share2 size={20} />
        </button>

        <div className="absolute bottom-4 left-5 right-5 text-white">
          <div className="flex items-center gap-2 text-sm text-white/90 mb-2">
            <MapPin size={16} />
            <span>{guide.destination}</span>
          </div>
          <h1 className="text-2xl font-bold font-rounded">{guide.title}</h1>
        </div>
      </div>

      {/* 内容区域 */}
      <div className="px-5 py-6 -mt-4 bg-white rounded-t-3xl relative z-10">
        {/* 基本信息 */}
        <div className="grid grid-cols-3 gap-3 mb-6">
          <div className="text-center p-3 bg-primary-50 rounded-2xl">
            <Clock size={20} className="mx-auto text-primary-500 mb-1" />
            <p className="text-xs text-gray-500">游玩时长</p>
            <p className="text-sm font-bold text-gray-700 mt-0.5">{guide.duration}</p>
          </div>
          <div className="text-center p-3 bg-secondary-50 rounded-2xl">
            <Wallet size={20} className="mx-auto text-secondary-500 mb-1" />
            <p className="text-xs text-gray-500">人均预算</p>
            <p className="text-sm font-bold text-gray-700 mt-0.5">{guide.budget}</p>
          </div>
          <div className="text-center p-3 bg-amber-50 rounded-2xl">
            <Calendar size={20} className="mx-auto text-amber-500 mb-1" />
            <p className="text-xs text-gray-500">最佳季节</p>
            <p className="text-sm font-bold text-gray-700 mt-0.5">{guide.bestSeason}</p>
          </div>
        </div>

        {/* 亮点标签 */}
        <div className="mb-6">
          <h3 className="text-sm font-bold text-gray-700 mb-3">行程亮点</h3>
          <div className="flex flex-wrap gap-2">
            {guide.highlights.map((tag) => (
              <span
                key={tag}
                className="px-3 py-1.5 bg-gradient-to-r from-primary-100 to-primary-50 text-primary-600 rounded-full text-sm font-medium flex items-center gap-1"
              >
                <Star size={14} fill="currentColor" />
                {tag}
              </span>
            ))}
          </div>
        </div>

        {/* 攻略简介 */}
        <div className="mb-6">
          <h3 className="text-base font-bold text-gray-800 mb-3">攻略简介</h3>
          <p className="text-gray-600 leading-relaxed">{guide.description}</p>
        </div>

        {/* 详细内容 */}
        <div>
          <h3 className="text-base font-bold text-gray-800 mb-4">详细攻略</h3>
          <div className="prose prose-sm max-w-none text-gray-600 leading-relaxed space-y-4">
            {guide.content.split('\n\n').map((paragraph, index) => {
              if (paragraph.startsWith('**') && paragraph.endsWith('**')) {
                return (
                  <h4 key={index} className="text-base font-bold text-gray-800 mt-6 mb-2">
                    {paragraph.replace(/\*\*/g, '')}
                  </h4>
                );
              }
              if (paragraph.startsWith('- ')) {
                const items = paragraph.split('\n').filter((line) => line.startsWith('- '));
                return (
                  <ul key={index} className="list-disc list-inside space-y-2">
                    {items.map((item, i) => (
                      <li key={i} className="text-gray-600">
                        {item.replace('- ', '')}
                      </li>
                    ))}
                  </ul>
                );
              }
              if (paragraph.match(/^\d+\./)) {
                const items = paragraph.split('\n').filter((line) => line.match(/^\d+\./));
                return (
                  <ol key={index} className="list-decimal list-inside space-y-2">
                    {items.map((item, i) => (
                      <li key={i} className="text-gray-600">
                        {item.replace(/^\d+\.\s*/, '')}
                      </li>
                    ))}
                  </ol>
                );
              }
              return <p key={index} className="text-gray-600 leading-relaxed">{paragraph}</p>;
            })}
          </div>
        </div>

        {/* 底部间距 */}
        <div className="h-8" />
      </div>
    </div>
  );
}
