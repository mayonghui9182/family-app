import type { Weather, ForecastDay, LifeIndex, TravelGuide, Reminder, Todo, Baby, GrowthRecord, Vaccine, Milestone } from '@/types';

export const mockWeather: Weather = {
  city: '北京市',
  temperature: 28,
  condition: '晴',
  conditionIcon: 'sun',
  humidity: 45,
  windSpeed: 12,
  airQuality: '72',
  airQualityLevel: '良',
  feelsLike: 30,
  uvIndex: 6,
  visibility: 15,
};

export const mockForecast: ForecastDay[] = [
  { date: '07/04', dayOfWeek: '今天', high: 32, low: 22, condition: '晴', conditionIcon: 'sun', precipitation: 0 },
  { date: '07/05', dayOfWeek: '明天', high: 30, low: 21, condition: '多云', conditionIcon: 'cloud', precipitation: 10 },
  { date: '07/06', dayOfWeek: '周六', high: 28, low: 20, condition: '小雨', conditionIcon: 'cloud-rain', precipitation: 60 },
  { date: '07/07', dayOfWeek: '周日', high: 26, low: 19, condition: '阴', conditionIcon: 'cloud', precipitation: 20 },
  { date: '07/08', dayOfWeek: '周一', high: 29, low: 20, condition: '晴', conditionIcon: 'sun', precipitation: 5 },
  { date: '07/09', dayOfWeek: '周二', high: 31, low: 22, condition: '晴', conditionIcon: 'sun', precipitation: 0 },
  { date: '07/10', dayOfWeek: '周三', high: 33, low: 24, condition: '多云', conditionIcon: 'cloud-sun', precipitation: 15 },
];

export const mockLifeIndex: LifeIndex[] = [
  { id: '1', name: '穿衣指数', level: '炎热', description: '建议穿短衫、短裙、短裤等清凉透气的衣物', icon: 'shirt' },
  { id: '2', name: '紫外线指数', level: '强', description: '涂擦SPF大于15的防晒霜，避免长时间暴晒', icon: 'sun' },
  { id: '3', name: '运动指数', level: '适宜', description: '天气较好，适宜户外运动，但注意防暑降温', icon: 'dumbbell' },
  { id: '4', name: '洗车指数', level: '较适宜', description: '天气较好，适合洗车，但明天可能有雨', icon: 'car' },
  { id: '5', name: '过敏指数', level: '中等', description: '易感人群应减少外出，外出需做好防护', icon: 'flower-2' },
  { id: '6', name: '空气指数', level: '良', description: '空气质量可接受，可正常进行户外活动', icon: 'wind' },
];

export const mockTravelGuides: TravelGuide[] = [
  {
    id: '1',
    title: '三亚亲子游全攻略',
    destination: '三亚',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=beautiful%20sanya%20tropical%20beach%20with%20palm%20trees%20and%20blue%20ocean%20sunny%20day&image_size=landscape_16_9',
    description: '带宝宝去三亚怎么玩？这份攻略告诉你',
    content: `三亚是中国最受欢迎的热带海滨旅游城市，非常适合亲子游。以下是详细攻略：

**行程推荐（5天4晚）：**

Day 1：抵达三亚，入住酒店，休整适应
- 选择亚龙湾或海棠湾的度假酒店
- 下午可以在酒店沙滩玩耍

Day 2：蜈支洲岛一日游
- 提前订票，早上出发
- 岛上有专门的儿童区域
- 注意防晒，带足饮用水

Day 3：亚特兰蒂斯水世界
- 小朋友的最爱，可以玩一整天
- 有专门的幼儿游玩区域
- 建议自备拖鞋和浴巾

Day 4：南山文化旅游区+天涯海角
- 感受热带自然风光
- 天涯海角适合拍照留念

Day 5：休闲购物，返程
- 三亚国际免税城
- 购买特产和纪念品

**亲子贴士：**
1. 选择有亲子设施的酒店
2. 行程安排不要太满，给宝宝休息时间
3. 必备物品：防晒霜、驱蚊液、常用药品
4. 尽量错峰出行，避开人多的地方`,
    duration: '5天4晚',
    budget: '5000-10000元',
    bestSeason: '11月-次年4月',
    highlights: ['海滩度假', '水上乐园', '亲子酒店', '免税购物'],
  },
  {
    id: '2',
    title: '迪士尼乐园游玩指南',
    destination: '上海',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=shanghai%20disneyland%20castle%20with%20fireworks%20magical%20atmosphere%20night%20scene&image_size=landscape_16_9',
    description: '带娃玩转迪士尼，少排队多玩项目的秘诀',
    content: `上海迪士尼乐园是每个孩子梦想中的乐园。如何高效游玩，让我们一起来看看：

**行前准备：**
- 提前下载上海迪士尼度假区APP
- 购买早享卡，提前1小时入园
- 准备舒适的鞋子，一天要走很多路

**必玩项目（按身高要求）：**

适合小宝宝（90cm以上）：
- 幻想曲旋转木马
- 小飞象
- 晶彩奇航
- 七个小矮人矿山车（97cm以上）

适合大孩子（112cm以上）：
- 翱翔·飞越地平线
- 加勒比海盗
- 雷鸣山漂流
- 创极速光轮（122cm以上）

**演出推荐：**
- 米奇童话专列（花车巡游）
- 冰雪奇缘：欢唱盛会
- 风暴来临：杰克船长之惊天特技大冒险

**省钱Tips：**
1. 自带未开封的食物和水
2. 可以带空瓶子，园内有直饮水
3. 中午在餐厅吃，人多但凉快
4. 纪念品可以晚上看完烟花再买`,
    duration: '1-2天',
    budget: '1000-3000元',
    bestSeason: '春秋季节',
    highlights: ['主题乐园', '花车巡游', '烟花表演', '亲子互动'],
  },
  {
    id: '3',
    title: '杭州西湖亲子文化游',
    destination: '杭州',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=hangzhou%20west%20lake%20beautiful%20scenery%20with%20pagoda%20and%20willow%20trees%20spring%20time&image_size=landscape_16_9',
    description: '赏西湖美景，品江南文化，寓教于乐',
    content: `杭州是一座有着深厚文化底蕴的城市，西湖更是家喻户晓。带着孩子来一场文化之旅吧！

**推荐行程（3天2晚）：**

Day 1：西湖环湖
- 断桥残雪 → 白堤 → 苏堤
- 可以租辆亲子自行车骑行
- 傍晚看音乐喷泉

Day 2：宋城 + 河坊街
- 宋城千古情演出非常震撼
- 河坊街体验传统文化
- 品尝杭州小吃

Day 3：杭州动物园 + 太子湾
- 动物园依山而建，环境优美
- 太子湾公园适合野餐
- 春天还有郁金香展

**美食推荐：**
- 西湖醋鱼（选小的，刺少）
- 东坡肉（肥而不腻）
- 龙井虾仁
- 片儿川
- 葱包桧

**住宿建议：**
- 西湖周边民宿，有家庭房
- 武林广场附近，交通便利`,
    duration: '3天2晚',
    budget: '2000-4000元',
    bestSeason: '3-5月、9-11月',
    highlights: ['西湖美景', '历史文化', '美食体验', '自然风景'],
  },
  {
    id: '4',
    title: '成都熊猫基地亲子游',
    destination: '成都',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=cute%20giant%20panda%20eating%20bamboo%20in%20chengdu%20research%20base%20green%20forest&image_size=landscape_16_9',
    description: '近距离看大熊猫，体验川蜀文化',
    content: `成都，一座来了就不想走的城市。带着宝宝来看萌萌的大熊猫吧！

**大熊猫繁育研究基地攻略：**
- 一定要早去！熊猫早上最活跃
- 建议7:30开园就到
- 必去：月亮产房、太阳产房
- 可以坐观光车到山顶，步行下来
- 全程大约3-4小时

**其他推荐景点：**

1. 成都海昌极地海洋公园
   - 有白鲸、海豚表演
   - 适合带小朋友玩一整天

2. 成都博物馆
   - 免费，提前预约
   - 有专门的儿童展厅
   - 寓教于乐

3. 宽窄巷子
   - 感受老成都文化
   - 有很多特色小吃
   - 可以拍很多好看的照片

**美食推荐（适合小朋友的）：**
- 龙抄手（清汤的）
- 钟水饺
- 担担面（要不辣的）
- 三大炮
- 糖油果子`,
    duration: '3-4天',
    budget: '2000-5000元',
    bestSeason: '春秋季节',
    highlights: ['大熊猫', '美食之都', '文化体验', '海洋公园'],
  },
  {
    id: '5',
    title: '北京故宫文化之旅',
    destination: '北京',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=beijing%20forbidden%20city%20palace%20museum%20traditional%20chinese%20architecture%20blue%20sky&image_size=landscape_16_9',
    description: '带孩子走进故宫，感受中华文明的魅力',
    content: `北京是中国的首都，有着丰富的历史文化遗产。带着孩子来一场别开生面的文化之旅吧！

**故宫游览攻略：**
- 提前7天在官网预约门票
- 建议从午门进，神武门出
- 租个讲解器，给孩子讲历史故事
- 必看：太和殿、乾清宫、御花园
- 珍宝馆和钟表馆值得一看

**亲子必去景点：**

1. 北京动物园 + 海洋馆
   - 动物种类很多
   - 海洋馆有海豚表演
   - 可以玩一整天

2. 中国科学技术馆
   - 孩子可以动手操作
   - 有专门的儿童科学乐园
   - 寓教于乐

3. 天安门广场升旗
   - 早起看庄严的升旗仪式
   - 培养爱国情怀

4. 八达岭长城
   - 不到长城非好汉
   - 可以坐缆车上山
   - 注意安全

**小贴士：**
- 北京很大，景点分散，规划好路线
- 地铁很方便，办张交通卡
- 带孩子出行，行程不要太赶`,
    duration: '4-5天',
    budget: '3000-6000元',
    bestSeason: '4-5月、9-10月',
    highlights: ['故宫文化', '历史教育', '科学探索', '长城'],
  },
  {
    id: '6',
    title: '厦门鼓浪屿悠闲游',
    destination: '厦门',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=xiamen%20gulangyu%20island%20colonial%20architecture%20sea%20view%20sunny%20day&image_size=landscape_16_9',
    description: '漫步文艺小岛，享受悠闲亲子时光',
    content: `厦门是一座美丽的海滨城市，鼓浪屿更是文艺气息满满。带着家人来享受悠闲的假期吧！

**鼓浪屿游玩攻略：**
- 提前在"厦门轮渡"公众号买船票
- 建议白天上岛，住一晚更好
- 岛上没有机动车，全靠步行
- 可以给孩子盖纪念章，很有乐趣

**必打卡景点：**
- 日光岩（俯瞰全岛）
- 菽庄花园（有钢琴博物馆）
- 皓月园（郑成功雕像）
- 最美转角（拍照）

**厦门本岛推荐：**

1. 厦门大学
   - 中国最美大学之一
   - 芙蓉隧道的涂鸦很有特色
   - 需要提前预约

2. 厦门植物园
   - 多肉植物区超美
   - 热带雨林区有喷雾
   - 适合拍照和认识植物

3. 环岛路
   - 可以租亲子自行车骑行
   - 沙滩可以玩沙子
   - 看日落超美

**美食推荐：**
- 沙茶面
- 海蛎煎
- 土笋冻（勇敢者挑战）
- 花生汤
- 芒果冰`,
    duration: '3-4天',
    budget: '2000-4000元',
    bestSeason: '10月-次年4月',
    highlights: ['海岛风情', '文艺气息', '美食小吃', '沙滩玩耍'],
  },
];

export const mockReminders: Reminder[] = [
  { id: '1', title: '宝宝喝牛奶', time: '07:00', repeat: 'daily', enabled: true, category: '宝宝', note: '200ml温水+3勺奶粉' },
  { id: '2', title: '送孩子上学', time: '07:30', repeat: 'daily', enabled: true, category: '日常' },
  { id: '3', title: '妈妈吃药', time: '08:00', repeat: 'daily', enabled: true, category: '健康', note: '饭后半小时' },
  { id: '4', title: '中午午睡提醒', time: '12:30', repeat: 'daily', enabled: true, category: '宝宝' },
  { id: '5', title: '接孩子放学', time: '16:30', repeat: 'daily', enabled: true, category: '日常' },
  { id: '6', title: '宝宝洗澡', time: '20:00', repeat: 'daily', enabled: true, category: '宝宝' },
  { id: '7', title: '垃圾分类投放', time: '19:00', repeat: 'weekly', enabled: true, category: '家务' },
  { id: '8', title: '缴水电费', time: '10:00', repeat: 'monthly', enabled: true, category: '账单' },
];

export const mockTodos: Todo[] = [
  { id: '1', title: '给宝宝买奶粉', priority: 'high', completed: false, date: '2024-07-04', category: '购物' },
  { id: '2', title: '幼儿园家长会', priority: 'high', completed: false, date: '2024-07-05', category: '孩子' },
  { id: '3', title: '整理宝宝衣柜', priority: 'medium', completed: false, date: '2024-07-04', category: '家务' },
  { id: '4', title: '预约疫苗接种', priority: 'medium', completed: true, date: '2024-07-03', category: '健康' },
  { id: '5', title: '买周末出游的零食', priority: 'low', completed: false, date: '2024-07-06', category: '购物' },
  { id: '6', title: '给宝宝读故事书', priority: 'low', completed: true, date: '2024-07-03', category: '亲子' },
  { id: '7', title: '修理宝宝的玩具车', priority: 'medium', completed: false, date: '2024-07-07', category: '其他' },
];

export const mockBaby: Baby = {
  name: '小橙子',
  birthday: '2022-03-15',
  gender: 'girl',
  avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=orange&backgroundColor=ffdfbf',
};

export const mockGrowthRecords: GrowthRecord[] = [
  { id: '1', date: '2022-03', height: 50, weight: 3.2 },
  { id: '2', date: '2022-06', height: 62, weight: 6.5 },
  { id: '3', date: '2022-09', height: 70, weight: 8.2 },
  { id: '4', date: '2022-12', height: 76, weight: 9.5 },
  { id: '5', date: '2023-03', height: 82, weight: 10.8 },
  { id: '6', date: '2023-06', height: 88, weight: 12.0 },
  { id: '7', date: '2023-09', height: 92, weight: 13.2 },
  { id: '8', date: '2023-12', height: 96, weight: 14.0 },
  { id: '9', date: '2024-03', height: 100, weight: 15.2 },
  { id: '10', date: '2024-06', height: 104, weight: 16.5 },
];

export const mockVaccines: Vaccine[] = [
  { id: '1', name: '乙肝疫苗（第1剂）', date: '2022-03-15', completed: true, age: '出生时' },
  { id: '2', name: '卡介苗', date: '2022-03-16', completed: true, age: '出生时' },
  { id: '3', name: '乙肝疫苗（第2剂）', date: '2022-04-15', completed: true, age: '1月龄' },
  { id: '4', name: '脊灰疫苗（第1剂）', date: '2022-05-15', completed: true, age: '2月龄' },
  { id: '5', name: '百白破疫苗（第1剂）', date: '2022-06-15', completed: true, age: '3月龄' },
  { id: '6', name: '百白破疫苗（第2剂）', date: '2022-07-15', completed: true, age: '4月龄' },
  { id: '7', name: '百白破疫苗（第3剂）', date: '2022-08-15', completed: true, age: '5月龄' },
  { id: '8', name: '乙肝疫苗（第3剂）', date: '2022-09-15', completed: true, age: '6月龄' },
  { id: '9', name: '麻腮风疫苗（第1剂）', date: '2022-09-15', completed: true, age: '8月龄' },
  { id: '10', name: '乙脑减毒活疫苗（第1剂）', date: '2023-03-15', completed: true, age: '1岁' },
  { id: '11', name: '百白破疫苗（第4剂）', date: '2024-03-15', completed: false, age: '2岁' },
  { id: '12', name: '麻腮风疫苗（第2剂）', date: '2024-09-15', completed: false, age: '2岁半' },
];

export const mockMilestones: Milestone[] = [
  { id: '1', title: '第一次笑', description: '宝宝第一次露出了开心的笑容', date: '2022-05-20', achieved: true, age: '2个月' },
  { id: '2', title: '学会翻身', description: '从仰卧翻到俯卧，小胳膊还不太有力', date: '2022-08-10', achieved: true, age: '5个月' },
  { id: '3', title: '长出第一颗牙', description: '下门牙冒出小白尖，爱流口水', date: '2022-09-15', achieved: true, age: '6个月' },
  { id: '4', title: '学会爬行', description: '匍匐前进，到处探索', date: '2022-12-01', achieved: true, age: '8个半月' },
  { id: '5', title: '叫第一声妈妈', description: '模糊的发音，激动得妈妈流泪', date: '2023-01-20', achieved: true, age: '10个月' },
  { id: '6', title: '独立走路', description: '摇摇晃晃迈出第一步', date: '2023-04-10', achieved: true, age: '1岁1个月' },
  { id: '7', title: '自己吃饭', description: '用勺子吃得满脸都是', date: '2023-08-15', achieved: true, age: '1岁5个月' },
  { id: '8', title: '说完整句子', description: '"妈妈抱宝宝"，第一句完整的话', date: '2023-12-20', achieved: true, age: '1岁9个月' },
  { id: '9', title: '学会自己穿衣服', description: '还会穿反，但是很棒', date: '2024-05-10', achieved: true, age: '2岁2个月' },
  { id: '10', title: '上幼儿园第一天', description: '哭了一下下，很快就适应了', date: '2024-09-01', achieved: false, age: '2岁半' },
];
