import type { Weather, ForecastDay, LifeIndex, TravelGuide, Reminder, Todo, Baby, BabyGrowthRecord, Milestone, UserInfo, FamilyMember, InviteCode, FamilyInfo, BabyVaccine, VaccineStats, Album, Photo, PageResult, HouseholdItem, ItemRecord, ItemStats } from '@/types'
import { dayjs } from '@/utils'

export const mockWeather: Weather = {
  city: '北京市朝阳区',
  temperature: 26,
  feelsLike: 28,
  humidity: 60,
  windSpeed: 3,
  windDirection: '东南风',
  condition: '晴朗',
  icon: 'sunny',
  pressure: 1013,
  visibility: 15,
  uvIndex: 6,
  airQuality: '良',
  aqi: 68,
}

const daysOfWeek = ['今天', '明天', '周三', '周四', '周五', '周六', '周日']
const weatherIcons = ['sunny', 'cloudy', 'rainy', 'sunny', 'cloudy', 'overcast', 'sunny']
const weatherConditions = ['晴', '多云', '小雨', '晴', '多云', '阴', '晴']

export const mockForecast: ForecastDay[] = Array.from({ length: 7 }, (_, i) => ({
  date: dayjs().add(i, 'day').format('YYYY-MM-DD'),
  dayOfWeek: daysOfWeek[i],
  high: 28 - i,
  low: 18 + i,
  condition: weatherConditions[i],
  icon: weatherIcons[i],
  precipitation: [0, 10, 80, 5, 20, 30, 0][i],
  humidity: [60, 65, 80, 55, 70, 75, 58][i],
  windSpeed: [3, 4, 2, 3, 4, 3, 2][i],
}))

export const mockLifeIndex: LifeIndex[] = [
  { name: '穿衣指数', level: '薄外套', description: '天气舒适，建议穿薄外套', icon: 'shirt' },
  { name: '紫外线', level: '中等', description: '涂抹防晒霜，注意防护', icon: 'sun' },
  { name: '运动指数', level: '适宜', description: '天气晴好，适合户外运动', icon: 'dumbbell' },
  { name: '洗车指数', level: '适宜', description: '天气晴朗，适合洗车', icon: 'car' },
  { name: '感冒指数', level: '低', description: '天气稳定，感冒机率低', icon: 'thermometer' },
  { name: '空气污染', level: '良', description: '空气质量可接受，适合外出', icon: 'wind' },
]

export const mockTravelList: TravelGuide[] = [
  {
    id: 1,
    name: '丽江古城',
    location: '云南丽江',
    description: '丽江古城位于云南省丽江市古城区，是中国历史文化名城之一，也是世界文化遗产。古城始建于宋末元初，距今已有800多年历史。古城依山傍水，青石板路蜿蜒曲折，小桥流水人家，是纳西族文化的重要发祥地。',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=ancient%20chinese%20town%20with%20traditional%20architecture%20mountains%20in%20background%20sunny%20day&image_size=landscape_4_3',
    images: [],
    rating: 4.8,
    reviewCount: 12580,
    price: '¥1,299起',
    minPrice: 1299,
    tags: ['古镇', '自然风光', '摄影'],
    category: 'cultural',
    openTime: '全天开放',
    suggestedDuration: '2-3天',
    suitableFor: ['情侣', '家庭', '朋友'],
    transportation: [
      { type: 'plane', icon: 'plane', name: '飞机', description: '丽江三义机场，距离古城约30公里' },
      { type: 'train', icon: 'train', name: '高铁', description: '丽江站，乘坐公交可直达古城' },
    ],
    highlights: ['世界文化遗产', '纳西族文化', '玉龙雪山', '四方街', '木府'],
    tips: ['最佳旅游时间为春秋两季', '早晚温差大，建议带外套', '古城内石板路多，穿舒适的鞋子'],
  },
  {
    id: 2,
    name: '三亚湾',
    location: '海南三亚',
    description: '三亚湾是三亚最长的海湾，绵延22公里，有着"亚洲第一大道"之称的椰梦长廊沿海湾而建。这里沙滩平缓，海水清澈，是度假休闲的绝佳去处。',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=tropical%20beach%20with%20palm%20trees%20turquoise%20water%20sunny%20paradise%20island&image_size=landscape_4_3',
    images: [],
    rating: 4.9,
    reviewCount: 28650,
    price: '¥2,599起',
    minPrice: 2599,
    tags: ['海滩', '度假', '亲子游'],
    category: 'beach',
    openTime: '全天开放',
    suggestedDuration: '3-5天',
    suitableFor: ['家庭', '情侣', '朋友'],
    transportation: [
      { type: 'plane', icon: 'plane', name: '飞机', description: '三亚凤凰国际机场，距离市区约20公里' },
    ],
    highlights: ['椰梦长廊', '日落美景', '海鲜美食', '水上运动', '热带雨林'],
    tips: ['注意防晒，带好防晒霜和遮阳帽', '海鲜市场可以砍价', '雨季为5-10月'],
  },
  {
    id: 3,
    name: '九寨沟',
    location: '四川阿坝',
    description: '九寨沟以翠海、叠瀑、彩林、雪峰、藏情著称于世，被誉为"童话世界"、"水景之王"。这里有108个高山湖泊，湖水清澈见底，色彩斑斓。',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=beautiful%20turquoise%20lake%20in%20mountain%20valley%20autumn%20colors%20forest%20waterfall&image_size=landscape_4_3',
    images: [],
    rating: 4.7,
    reviewCount: 15890,
    price: '¥1,899起',
    minPrice: 1899,
    tags: ['自然风光', '摄影', '山水'],
    category: 'nature',
    openTime: '07:00-17:00',
    suggestedDuration: '1-2天',
    suitableFor: ['朋友', '情侣', '家庭'],
    transportation: [
      { type: 'plane', icon: 'plane', name: '飞机', description: '九寨黄龙机场，距离景区约80公里' },
      { type: 'bus', icon: 'bus', name: '大巴', description: '成都新南门车站有直达九寨沟的大巴' },
    ],
    highlights: ['五花海', '诺日朗瀑布', '长海', '树正沟', '则查洼沟'],
    tips: ['最佳旅游时间为10月中旬至11月初', '景区内海拔较高，注意高反', '景区内温差大，带好保暖衣物'],
  },
  {
    id: 4,
    name: '西湖',
    location: '浙江杭州',
    description: '西湖是中国大陆首批国家重点风景名胜区和中国十大风景名胜之一。西湖三面环山，面积约6.39平方千米，湖中被孤山、白堤、苏堤、杨公堤分隔。',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=traditional%20chinese%20lake%20landscape%20pagoda%20bridge%20willow%20trees%20misty%20morning&image_size=landscape_4_3',
    images: [],
    rating: 4.6,
    reviewCount: 32450,
    price: '¥899起',
    minPrice: 899,
    tags: ['历史文化', '园林', '城市'],
    category: 'cultural',
    openTime: '全天开放',
    suggestedDuration: '1-2天',
    suitableFor: ['家庭', '情侣', '朋友', '亲子游'],
    transportation: [
      { type: 'train', icon: 'train', name: '高铁', description: '杭州东站，地铁1号线可直达西湖' },
    ],
    highlights: ['苏堤春晓', '断桥残雪', '雷峰塔', '三潭印月', '花港观鱼'],
    tips: ['西湖免费开放，部分景点需购票', '建议租自行车环湖', '晚上可以看印象西湖演出'],
  },
  {
    id: 5,
    name: '张家界',
    location: '湖南张家界',
    description: '张家界国家森林公园是中国第一个国家森林公园，以峰林、峡谷、溪流、瀑布等自然景观著称。这里的石英砂岩峰林地貌世界罕见，被誉为"中国山水画的原本"。',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=tall%20sandstone%20pillars%20mountain%20landscape%20misty%20clouds%20green%20forest%20avatar%20mountains&image_size=landscape_4_3',
    images: [],
    rating: 4.8,
    reviewCount: 18760,
    price: '¥2,199起',
    minPrice: 2199,
    tags: ['自然风光', '摄影', '山水'],
    category: 'nature',
    openTime: '07:00-18:00',
    suggestedDuration: '2-3天',
    suitableFor: ['朋友', '情侣', '家庭'],
    transportation: [
      { type: 'plane', icon: 'plane', name: '飞机', description: '张家界荷花机场，距离市区约10公里' },
      { type: 'train', icon: 'train', name: '高铁', description: '张家界西站，有直达景区的大巴' },
    ],
    highlights: ['天门山', '玻璃栈道', '金鞭溪', '袁家界', '天子山'],
    tips: ['建议游玩2-3天', '山上气温较低，带好外套', '玻璃栈道需租鞋套'],
  },
  {
    id: 6,
    name: '鼓浪屿',
    location: '福建厦门',
    description: '鼓浪屿是厦门最大的一个卫星岛，岛上气候宜人，四季如春，无车马喧嚣，有鸟语花香，素有"海上花园"之誉。',
    image: 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=coastal%20island%20with%20colonial%20architecture%20palm%20trees%20beach%20sunset%20xiamen&image_size=landscape_4_3',
    images: [],
    rating: 4.7,
    reviewCount: 25680,
    price: '¥1,099起',
    minPrice: 1099,
    tags: ['海岛', '历史文化', '情侣游'],
    category: 'beach',
    openTime: '全天开放',
    suggestedDuration: '1-2天',
    suitableFor: ['情侣', '朋友', '家庭'],
    transportation: [
      { type: 'plane', icon: 'plane', name: '飞机', description: '厦门高崎国际机场，距离市区约15公里' },
      { type: 'ferry', icon: 'ship', name: '轮渡', description: '从厦门岛乘坐轮渡前往鼓浪屿' },
    ],
    highlights: ['日光岩', '菽庄花园', '皓月园', '钢琴博物馆', '龙头路'],
    tips: ['需要提前购买船票', '岛上坡多路窄，穿舒适的鞋子', '晚上可以在岛上住一晚'],
  },
]

export const mockReminders: Reminder[] = [
  {
    id: 1,
    title: '妈妈生日',
    date: dayjs().add(10, 'day').format('YYYY-MM-DD'),
    time: '09:00',
    type: 'birthday',
    repeat: 'yearly',
    enabled: true,
    description: '记得订蛋糕和买礼物',
  },
  {
    id: 2,
    title: '交房租',
    date: dayjs().date(1).format('YYYY-MM-DD'),
    time: '10:00',
    type: 'bill',
    repeat: 'monthly',
    enabled: true,
    description: '每月1号交房租',
  },
  {
    id: 3,
    title: '健身打卡',
    date: dayjs().format('YYYY-MM-DD'),
    time: '18:00',
    type: 'health',
    repeat: 'daily',
    enabled: true,
    description: '下班后去健身房',
  },
  {
    id: 4,
    title: '朋友聚会',
    date: dayjs().add(3, 'day').format('YYYY-MM-DD'),
    time: '19:00',
    type: 'social',
    repeat: 'none',
    enabled: false,
    description: '老同学聚会',
  },
  {
    id: 5,
    title: '项目评审',
    date: dayjs().add(5, 'day').format('YYYY-MM-DD'),
    time: '14:00',
    type: 'work',
    repeat: 'none',
    enabled: true,
    description: '季度项目评审会议',
  },
  {
    id: 6,
    title: '体检提醒',
    date: dayjs().add(15, 'day').format('YYYY-MM-DD'),
    time: '08:00',
    type: 'health',
    repeat: 'yearly',
    enabled: true,
    description: '年度体检，空腹前往',
  },
]

export const mockTodos: Todo[] = [
  {
    id: 1,
    title: '完成项目报告',
    description: '整理Q2项目数据，撰写总结报告',
    completed: false,
    priority: 'high',
    dueDate: dayjs().format('YYYY-MM-DD'),
    dueTime: '18:00',
    tags: ['工作'],
    category: '工作',
    createdAt: dayjs().subtract(1, 'day').toISOString(),
    contentType: 'text',
    hasReminder: true,
    remindTimeType: 'absolute',
    repeatType: 'none',
  },
  {
    id: 2,
    title: '回复客户邮件',
    description: '回复ABC公司的合作意向邮件',
    completed: false,
    priority: 'medium',
    dueDate: dayjs().format('YYYY-MM-DD'),
    dueTime: '16:00',
    tags: ['工作', '客户'],
    category: '工作',
    createdAt: dayjs().subtract(1, 'day').toISOString(),
    contentType: 'text',
    hasReminder: false,
    repeatType: 'none',
  },
  {
    id: 3,
    title: '购买生活用品',
    description: '牙膏、洗发水、纸巾等',
    completed: true,
    priority: 'low',
    dueDate: dayjs().subtract(1, 'day').format('YYYY-MM-DD'),
    tags: ['生活'],
    category: '生活',
    createdAt: dayjs().subtract(2, 'day').toISOString(),
    completedAt: dayjs().subtract(1, 'day').toISOString(),
    contentType: 'text',
    hasReminder: false,
    repeatType: 'none',
  },
  {
    id: 4,
    title: '预约牙医',
    description: '半年一次的口腔检查',
    completed: false,
    priority: 'medium',
    dueDate: dayjs().add(1, 'day').format('YYYY-MM-DD'),
    tags: ['健康'],
    category: '健康',
    createdAt: dayjs().subtract(3, 'day').toISOString(),
    contentType: 'voice',
    voiceUrl: '',
    voiceDuration: 15,
    hasReminder: true,
    remindTimeType: 'relative',
    remindRelativeMinutes: 30,
    repeatType: 'none',
  },
  {
    id: 5,
    title: '阅读技术书籍第3章',
    description: '《JavaScript高级程序设计》第3章',
    completed: false,
    priority: 'low',
    dueDate: dayjs().add(3, 'day').format('YYYY-MM-DD'),
    tags: ['学习'],
    category: '学习',
    createdAt: dayjs().subtract(4, 'day').toISOString(),
    contentType: 'text',
    hasReminder: false,
    repeatType: 'daily',
  },
  {
    id: 6,
    title: '健身1小时',
    description: '有氧+力量训练',
    completed: true,
    priority: 'medium',
    dueDate: dayjs().subtract(1, 'day').format('YYYY-MM-DD'),
    tags: ['健康', '健身'],
    category: '健康',
    createdAt: dayjs().subtract(2, 'day').toISOString(),
    completedAt: dayjs().subtract(1, 'day').toISOString(),
    contentType: 'text',
    hasReminder: true,
    remindTimeType: 'absolute',
    repeatType: 'daily',
  },
  {
    id: 7,
    title: '周末大扫除',
    description: '打扫房间，整理衣柜',
    completed: false,
    priority: 'low',
    dueDate: dayjs().add(5, 'day').format('YYYY-MM-DD'),
    tags: ['生活'],
    category: '生活',
    createdAt: dayjs().subtract(1, 'day').toISOString(),
    contentType: 'text',
    hasReminder: false,
    repeatType: 'weekly',
  },
  {
    id: 8,
    title: '准备presentation',
    description: '下周一的团队分享',
    completed: false,
    priority: 'high',
    dueDate: dayjs().add(7, 'day').format('YYYY-MM-DD'),
    tags: ['工作'],
    category: '工作',
    createdAt: dayjs().toISOString(),
    contentType: 'voice',
    voiceUrl: '',
    voiceDuration: 25,
    hasReminder: true,
    remindTimeType: 'absolute',
    repeatType: 'none',
  },
]

export const mockBaby: Baby = {
  id: 1,
  name: '小棉袄',
  nickname: '朵朵',
  gender: 'girl',
  birthDate: dayjs().subtract(1, 'year').subtract(3, 'month').subtract(15, 'day').format('YYYY-MM-DD'),
  birthTime: '08:30',
  birthWeight: 3.2,
  birthHeight: 50,
  avatar: '',
  bloodType: 'A',
  zodiac: '白羊座',
}

export const mockGrowthRecords: BabyGrowthRecord[] = [
  { id: 1, babyId: 1, date: dayjs().subtract(1, 'year').subtract(3, 'month').subtract(15, 'day').format('YYYY-MM-DD'), height: 50, weight: 3.2, headCircumference: 34 },
  { id: 2, babyId: 1, date: dayjs().subtract(1, 'year').format('YYYY-MM-DD'), height: 55, weight: 4.5, headCircumference: 37 },
  { id: 3, babyId: 1, date: dayjs().subtract(10, 'month').toDate().toISOString().slice(0, 10), height: 60, weight: 6.0, headCircumference: 40 },
  { id: 4, babyId: 1, date: dayjs().subtract(7, 'month').toDate().toISOString().slice(0, 10), height: 66, weight: 7.5, headCircumference: 43 },
  { id: 5, babyId: 1, date: dayjs().subtract(4, 'month').toDate().toISOString().slice(0, 10), height: 72, weight: 9.0, headCircumference: 45 },
  { id: 6, babyId: 1, date: dayjs().subtract(1, 'month').toDate().toISOString().slice(0, 10), height: 76.5, weight: 9.9, headCircumference: 46 },
  { id: 7, babyId: 1, date: dayjs().format('YYYY-MM-DD'), height: 78.5, weight: 10.2, headCircumference: 46.5 },
]

export const mockMilestones: Milestone[] = [
  { id: 1, babyId: 1, title: '第一次抬头', description: '能够自主抬头几秒钟', achievedDate: dayjs().subtract(1, 'year').subtract(2, 'month').format('YYYY-MM-DD'), expectedAge: '2个月', category: '大动作', achieved: true, icon: 'head' },
  { id: 2, babyId: 1, title: '会翻身', description: '能够从仰卧翻到俯卧', achievedDate: dayjs().subtract(1, 'year').subtract(7, 'month').format('YYYY-MM-DD'), expectedAge: '4-6个月', category: '大动作', achieved: true, icon: 'roll' },
  { id: 3, babyId: 1, title: '会坐', description: '能够独立坐稳', achievedDate: dayjs().subtract(1, 'year').subtract(5, 'month').format('YYYY-MM-DD'), expectedAge: '6-8个月', category: '大动作', achieved: true, icon: 'sit' },
  { id: 4, babyId: 1, title: '会爬', description: '能够手膝爬行', achievedDate: dayjs().subtract(1, 'year').subtract(2, 'month').format('YYYY-MM-DD'), expectedAge: '8-10个月', category: '大动作', achieved: true, icon: 'crawl' },
  { id: 5, babyId: 1, title: '会说妈妈', description: '能够清晰地叫妈妈', achievedDate: dayjs().subtract(1, 'year').subtract(1, 'month').format('YYYY-MM-DD'), expectedAge: '10-12个月', category: '语言', achieved: true, icon: 'speak' },
  { id: 6, babyId: 1, title: '会独立走路', description: '能够独立行走几步', achievedDate: dayjs().subtract(2, 'month').toDate().toISOString().slice(0, 10), expectedAge: '12-15个月', category: '大动作', achieved: true, icon: 'walk' },
  { id: 7, babyId: 1, title: '会说简单词语', description: '会说5个以上的词', achievedDate: dayjs().subtract(1, 'month').toDate().toISOString().slice(0, 10), expectedAge: '12-18个月', category: '语言', achieved: true, icon: 'speak' },
  { id: 8, babyId: 1, title: '会自己吃饭', description: '能够用勺子自己吃饭', achievedDate: undefined, expectedAge: '15-18个月', category: '精细动作', achieved: false, icon: 'eat' },
  { id: 9, babyId: 1, title: '会跑', description: '能够稳定地跑步', achievedDate: undefined, expectedAge: '18-24个月', category: '大动作', achieved: false, icon: 'run' },
  { id: 10, babyId: 1, title: '会说短句', description: '能够说2-3个字的短语', achievedDate: undefined, expectedAge: '18-24个月', category: '语言', achieved: false, icon: 'speak' },
]

export const mockUser: UserInfo = {
  id: '1',
  name: '用户',
  avatar: '',
  email: 'user@example.com',
  phone: '13800138000',
}

export const mockFamilyInfo: FamilyInfo = {
  id: '1',
  name: '幸福之家',
  inviteCode: 'FAMILY888',
  memberCount: 4,
}

export const mockFamilyMembers: FamilyMember[] = [
  {
    id: '1',
    name: '爸爸',
    avatar: '',
    role: 'admin',
    lastLoginAt: dayjs().format('YYYY-MM-DD HH:mm'),
    joinedAt: dayjs().subtract(1, 'year').format('YYYY-MM-DD'),
  },
  {
    id: '2',
    name: '妈妈',
    avatar: '',
    role: 'admin',
    lastLoginAt: dayjs().subtract(1, 'hour').format('YYYY-MM-DD HH:mm'),
    joinedAt: dayjs().subtract(1, 'year').format('YYYY-MM-DD'),
  },
  {
    id: '3',
    name: '爷爷',
    avatar: '',
    role: 'member',
    lastLoginAt: dayjs().subtract(2, 'day').format('YYYY-MM-DD HH:mm'),
    joinedAt: dayjs().subtract(10, 'month').format('YYYY-MM-DD'),
  },
  {
    id: '4',
    name: '奶奶',
    avatar: '',
    role: 'member',
    lastLoginAt: dayjs().subtract(3, 'day').format('YYYY-MM-DD HH:mm'),
    joinedAt: dayjs().subtract(10, 'month').format('YYYY-MM-DD'),
  },
]

export const mockInviteList: InviteCode[] = [
  {
    id: '1',
    code: 'ABC123',
    creatorName: '爸爸',
    usedCount: 2,
    maxCount: 5,
    status: 'active',
    expireAt: dayjs().add(7, 'day').format('YYYY-MM-DD HH:mm'),
    createdAt: dayjs().subtract(1, 'day').format('YYYY-MM-DD HH:mm'),
  },
  {
    id: '2',
    code: 'XYZ789',
    creatorName: '妈妈',
    usedCount: 1,
    maxCount: 3,
    status: 'active',
    expireAt: dayjs().add(3, 'day').format('YYYY-MM-DD HH:mm'),
    createdAt: dayjs().subtract(2, 'day').format('YYYY-MM-DD HH:mm'),
  },
  {
    id: '3',
    code: 'OLD001',
    creatorName: '爸爸',
    usedCount: 5,
    maxCount: 5,
    status: 'expired',
    expireAt: dayjs().subtract(1, 'day').format('YYYY-MM-DD HH:mm'),
    createdAt: dayjs().subtract(10, 'day').format('YYYY-MM-DD HH:mm'),
  },
  {
    id: '4',
    code: 'DISABLED',
    creatorName: '妈妈',
    usedCount: 0,
    maxCount: 10,
    status: 'disabled',
    expireAt: dayjs().add(30, 'day').format('YYYY-MM-DD HH:mm'),
    createdAt: dayjs().subtract(5, 'day').format('YYYY-MM-DD HH:mm'),
  },
]

const vaccineTemplates = [
  { vaccineId: 1, vaccineName: '乙肝疫苗', shortName: '乙肝', type: 'free' as const, totalDoses: 3, preventDisease: '乙型病毒性肝炎' },
  { vaccineId: 2, vaccineName: '卡介苗', shortName: '卡介苗', type: 'free' as const, totalDoses: 1, preventDisease: '结核病' },
  { vaccineId: 3, vaccineName: '脊灰灭活疫苗', shortName: '脊灰', type: 'free' as const, totalDoses: 4, preventDisease: '脊髓灰质炎' },
  { vaccineId: 4, vaccineName: '百白破疫苗', shortName: '百白破', type: 'free' as const, totalDoses: 4, preventDisease: '百日咳、白喉、破伤风' },
  { vaccineId: 5, vaccineName: '麻腮风疫苗', shortName: '麻腮风', type: 'free' as const, totalDoses: 2, preventDisease: '麻疹、流行性腮腺炎、风疹' },
  { vaccineId: 6, vaccineName: '乙脑减毒活疫苗', shortName: '乙脑', type: 'free' as const, totalDoses: 2, preventDisease: '流行性乙型脑炎' },
  { vaccineId: 7, vaccineName: 'A群流脑多糖疫苗', shortName: 'A群流脑', type: 'free' as const, totalDoses: 2, preventDisease: 'A群流行性脑脊髓膜炎' },
  { vaccineId: 8, vaccineName: '甲肝减毒活疫苗', shortName: '甲肝', type: 'free' as const, totalDoses: 1, preventDisease: '甲型病毒性肝炎' },
  { vaccineId: 9, vaccineName: '13价肺炎球菌结合疫苗', shortName: '13价肺炎', type: 'paid' as const, totalDoses: 4, preventDisease: '肺炎球菌感染' },
  { vaccineId: 10, vaccineName: '五联疫苗', shortName: '五联', type: 'paid' as const, totalDoses: 4, preventDisease: '百日咳、白喉、破伤风、脊灰、Hib' },
  { vaccineId: 11, vaccineName: '轮状病毒疫苗', shortName: '轮状病毒', type: 'paid' as const, totalDoses: 3, preventDisease: '轮状病毒感染性腹泻' },
  { vaccineId: 12, vaccineName: '手足口疫苗', shortName: '手足口', type: 'paid' as const, totalDoses: 2, preventDisease: 'EV71型手足口病' },
]

const vaccineSchedule = [
  { vaccineIndex: 0, doseNumber: 1, monthsAfterBirth: 0 },
  { vaccineIndex: 1, doseNumber: 1, monthsAfterBirth: 0 },
  { vaccineIndex: 0, doseNumber: 2, monthsAfterBirth: 1 },
  { vaccineIndex: 2, doseNumber: 1, monthsAfterBirth: 2 },
  { vaccineIndex: 9, doseNumber: 1, monthsAfterBirth: 2 },
  { vaccineIndex: 10, doseNumber: 1, monthsAfterBirth: 2 },
  { vaccineIndex: 8, doseNumber: 1, monthsAfterBirth: 2 },
  { vaccineIndex: 0, doseNumber: 3, monthsAfterBirth: 6 },
  { vaccineIndex: 3, doseNumber: 1, monthsAfterBirth: 3 },
  { vaccineIndex: 2, doseNumber: 2, monthsAfterBirth: 3 },
  { vaccineIndex: 3, doseNumber: 2, monthsAfterBirth: 4 },
  { vaccineIndex: 2, doseNumber: 3, monthsAfterBirth: 4 },
  { vaccineIndex: 3, doseNumber: 3, monthsAfterBirth: 5 },
  { vaccineIndex: 8, doseNumber: 2, monthsAfterBirth: 4 },
  { vaccineIndex: 8, doseNumber: 3, monthsAfterBirth: 6 },
  { vaccineIndex: 3, doseNumber: 4, monthsAfterBirth: 18 },
  { vaccineIndex: 2, doseNumber: 4, monthsAfterBirth: 48 },
  { vaccineIndex: 4, doseNumber: 1, monthsAfterBirth: 8 },
  { vaccineIndex: 5, doseNumber: 1, monthsAfterBirth: 8 },
  { vaccineIndex: 6, doseNumber: 1, monthsAfterBirth: 6 },
  { vaccineIndex: 6, doseNumber: 2, monthsAfterBirth: 9 },
  { vaccineIndex: 4, doseNumber: 2, monthsAfterBirth: 18 },
  { vaccineIndex: 5, doseNumber: 2, monthsAfterBirth: 24 },
  { vaccineIndex: 7, doseNumber: 1, monthsAfterBirth: 18 },
  { vaccineIndex: 11, doseNumber: 1, monthsAfterBirth: 6 },
  { vaccineIndex: 11, doseNumber: 2, monthsAfterBirth: 7 },
  { vaccineIndex: 9, doseNumber: 2, monthsAfterBirth: 3 },
  { vaccineIndex: 9, doseNumber: 3, monthsAfterBirth: 4 },
  { vaccineIndex: 9, doseNumber: 4, monthsAfterBirth: 18 },
  { vaccineIndex: 10, doseNumber: 2, monthsAfterBirth: 3 },
  { vaccineIndex: 10, doseNumber: 3, monthsAfterBirth: 4 },
  { vaccineIndex: 8, doseNumber: 4, monthsAfterBirth: 15 },
]

export const generateMockVaccines = (babyBirthDate: string): BabyVaccine[] => {
  const birthDate = dayjs(babyBirthDate)
  return vaccineSchedule.map((schedule, index) => {
    const template = vaccineTemplates[schedule.vaccineIndex]
    const plannedDate = birthDate.add(schedule.monthsAfterBirth, 'month').format('YYYY-MM-DD')
    const today = dayjs()
    const planned = dayjs(plannedDate)
    const daysLeft = planned.diff(today, 'day')
    const isOverdue = daysLeft < 0

    let status: 'pending' | 'completed' | 'skipped' | 'delayed' = 'pending'
    let actualDate: string | undefined

    if (daysLeft < -30 && Math.random() > 0.3) {
      status = 'completed'
      actualDate = planned.add(Math.floor(Math.random() * 10), 'day').format('YYYY-MM-DD')
    } else if (daysLeft < 0) {
      status = 'delayed'
    }

    if (index >= vaccineSchedule.length - 5) {
      status = 'pending'
      actualDate = undefined
    }

    return {
      id: index + 1,
      vaccineId: template.vaccineId,
      vaccineName: template.vaccineName,
      shortName: template.shortName,
      type: template.type,
      doseNumber: schedule.doseNumber,
      totalDoses: template.totalDoses,
      preventDisease: template.preventDisease,
      plannedDate,
      actualDate,
      status,
      injectionSite: status === 'completed' ? '上臂三角肌' : undefined,
      hospital: status === 'completed' ? '社区卫生服务中心' : undefined,
      batchNumber: status === 'completed' ? '202401001' : undefined,
      adverseReaction: status === 'completed' && Math.random() > 0.8 ? '轻微发热' : undefined,
      remark: '',
      remindEnabled: status === 'pending' || status === 'delayed' ? 1 : 0,
      remindDaysBefore: 3,
      reminded: 0,
      description: `${template.vaccineName}用于预防${template.preventDisease}`,
      precautions: '接种后请留观30分钟，如有不适请及时就医',
      isOverdue: status === 'delayed' || (status === 'pending' && isOverdue),
      daysLeft,
    }
  })
}

export const generateMockVaccineStats = (vaccines: BabyVaccine[]): VaccineStats => {
  const total = vaccines.length
  const completed = vaccines.filter(v => v.status === 'completed').length
  const pending = vaccines.filter(v => v.status === 'pending' || v.status === 'delayed').length
  const skipped = vaccines.filter(v => v.status === 'skipped').length
  const overdue = vaccines.filter(v => v.isOverdue).length
  const progress = total > 0 ? Math.round((completed / total) * 100) : 0

  const nextVaccine = vaccines
    .filter(v => v.status === 'pending' || v.status === 'delayed')
    .sort((a, b) => Math.abs(a.daysLeft) - Math.abs(b.daysLeft))[0]

  return {
    total,
    completed,
    pending,
    skipped,
    overdue,
    nextVaccineName: nextVaccine?.vaccineName,
    nextVaccineDate: nextVaccine?.plannedDate,
    progress,
  }
}

const babyPhotoPrompts = [
  'cute baby girl smiling in pink dress soft lighting nursery room',
  'baby playing with colorful toys on soft blanket',
  'baby sleeping peacefully in crib with stuffed animals',
  'baby taking bath with bubbles laughing',
  'baby first steps walking in living room',
  'baby eating food messy face high chair',
  'baby playing in park green grass sunny day',
  'baby reading picture book with parent',
  'baby wearing cute hat winter coat',
  'baby celebrating birthday with cake balloons',
  'baby playing peekaboo laughing',
  'baby holding tiny flower garden',
  'baby splashing in pool summer',
  'baby dressed up for halloween costume',
  'baby playing with pet cat dog',
  'baby building blocks tower',
  'baby at beach sand ocean',
  'baby first christmas tree lights',
]

export const generateMockPhotos = (babyId: string | number, albumId: string | number, count: number = 12): Photo[] => {
  return Array.from({ length: count }, (_, i) => {
    const promptIndex = i % babyPhotoPrompts.length
    const url = `https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=${encodeURIComponent(babyPhotoPrompts[promptIndex])}&image_size=square_hd`
    return {
      id: i + 1,
      babyId,
      albumId,
      url,
      thumbnailUrl: url,
      title: `照片 ${i + 1}`,
      description: '',
      date: dayjs().subtract(i * 5, 'day').format('YYYY-MM-DD'),
      size: Math.floor(Math.random() * 2000000) + 500000,
      createdAt: dayjs().subtract(i * 5, 'day').format('YYYY-MM-DD HH:mm:ss'),
    }
  })
}

export const generateMockAlbums = (babyId: string | number): Album[] => {
  const albumData = [
    { title: '成长日记', description: '记录宝宝每天的成长瞬间', photoCount: 24 },
    { title: '第一次', description: '宝宝的许多第一次', photoCount: 15 },
    { title: '家庭聚会', description: '和家人在一起的美好时光', photoCount: 18 },
    { title: '户外活动', description: '宝宝探索大自然', photoCount: 12 },
  ]

  return albumData.map((album, index) => {
    const photos = generateMockPhotos(babyId, index + 1, album.photoCount)
    return {
      id: index + 1,
      babyId,
      title: album.title,
      description: album.description,
      coverUrl: photos[0]?.url,
      photoCount: album.photoCount,
      createdAt: dayjs().subtract((index + 1) * 30, 'day').format('YYYY-MM-DD HH:mm:ss'),
      updatedAt: dayjs().subtract(index * 10, 'day').format('YYYY-MM-DD HH:mm:ss'),
    }
  })
}

export const getMockPhotoList = (babyId: string | number, albumId: string | number, page: number = 1, size: number = 20): PageResult<Photo> => {
  const allPhotos = generateMockPhotos(babyId, albumId, 30)
  const start = (page - 1) * size
  const list = allPhotos.slice(start, start + size)
  return {
    list,
    total: allPhotos.length,
    page,
    pageSize: size,
    totalPages: Math.ceil(allPhotos.length / size),
  }
}

export const mockItems: HouseholdItem[] = [
  {
    id: 1,
    name: '纸尿裤 L码',
    category: 'diaper',
    categoryName: '尿不湿',
    unit: '包',
    totalQuantity: 2,
    warningQuantity: 3,
    icon: '👶',
    remark: '宝宝常用的纸尿裤',
    isLowStock: true,
    createTime: dayjs().subtract(30, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 2,
    name: '婴儿奶粉 3段',
    category: 'milk',
    categoryName: '奶粉',
    unit: '罐',
    totalQuantity: 5,
    warningQuantity: 2,
    icon: '🍼',
    remark: '宝宝主食奶粉',
    isLowStock: false,
    createTime: dayjs().subtract(25, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 3,
    name: '婴儿湿巾',
    category: 'daily',
    categoryName: '日用品',
    unit: '包',
    totalQuantity: 8,
    warningQuantity: 3,
    icon: '🧻',
    remark: '手口专用湿巾',
    isLowStock: false,
    createTime: dayjs().subtract(20, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 4,
    name: '维生素D滴剂',
    category: 'medicine',
    categoryName: '药品',
    unit: '瓶',
    totalQuantity: 1,
    warningQuantity: 2,
    icon: '💊',
    remark: '每日补充维生素D',
    isLowStock: true,
    createTime: dayjs().subtract(15, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 5,
    name: '宝宝米粉',
    category: 'food',
    categoryName: '食品',
    unit: '盒',
    totalQuantity: 3,
    warningQuantity: 2,
    icon: '🥣',
    remark: '原味高铁米粉',
    isLowStock: false,
    createTime: dayjs().subtract(10, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 6,
    name: '宝宝洗衣液',
    category: 'daily',
    categoryName: '日用品',
    unit: '瓶',
    totalQuantity: 1,
    warningQuantity: 1,
    icon: '🧴',
    remark: '婴儿专用洗衣液',
    isLowStock: true,
    createTime: dayjs().subtract(8, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 7,
    name: '果泥辅食',
    category: 'food',
    categoryName: '食品',
    unit: '袋',
    totalQuantity: 12,
    warningQuantity: 5,
    icon: '🍎',
    remark: '多种口味混合果泥',
    isLowStock: false,
    createTime: dayjs().subtract(5, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 8,
    name: '退热贴',
    category: 'medicine',
    categoryName: '药品',
    unit: '盒',
    totalQuantity: 0,
    warningQuantity: 1,
    icon: '🤒',
    remark: '宝宝退烧备用',
    isLowStock: true,
    createTime: dayjs().subtract(3, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 9,
    name: '拉拉裤 XL码',
    category: 'diaper',
    categoryName: '尿不湿',
    unit: '包',
    totalQuantity: 4,
    warningQuantity: 2,
    icon: '🩲',
    remark: '学步期宝宝用',
    isLowStock: false,
    createTime: dayjs().subtract(2, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    id: 10,
    name: '婴儿沐浴露',
    category: 'daily',
    categoryName: '日用品',
    unit: '瓶',
    totalQuantity: 2,
    warningQuantity: 1,
    icon: '🛁',
    remark: '洗发沐浴二合一',
    isLowStock: false,
    createTime: dayjs().subtract(1, 'day').format('YYYY-MM-DD HH:mm:ss'),
  },
]

export const generateMockItemRecords = (itemId: string | number): ItemRecord[] => {
  const item = mockItems.find(i => String(i.id) === String(itemId))
  if (!item) return []

  const records: ItemRecord[] = []
  let quantity = item.totalQuantity + 10

  for (let i = 0; i < 15; i++) {
    const isIn = Math.random() > 0.6
    const qty = isIn ? Math.floor(Math.random() * 3) + 1 : Math.floor(Math.random() * 2) + 1
    const date = dayjs().subtract(i * 3, 'day')

    if (isIn) {
      quantity += qty
    } else {
      quantity = Math.max(0, quantity - qty)
    }

    records.push({
      id: i + 1,
      itemId,
      itemName: item.name,
      type: isIn ? 'in' : 'out',
      typeName: isIn ? '入库' : '出库',
      quantity: qty,
      unit: item.unit,
      recordDate: date.format('YYYY-MM-DD'),
      operatorName: isIn ? '妈妈' : '奶奶',
      source: isIn ? ['购买', '赠送', '其他'][Math.floor(Math.random() * 3)] : undefined,
      price: isIn ? Math.floor(Math.random() * 100) + 20 : undefined,
      totalPrice: isIn ? (Math.floor(Math.random() * 100) + 20) * qty : undefined,
      remark: isIn ? '京东购买' : '日常使用',
      createTime: date.format('YYYY-MM-DD HH:mm:ss'),
    })
  }

  return records.sort((a, b) => dayjs(b.recordDate).valueOf() - dayjs(a.recordDate).valueOf())
}

export const mockItemStats: ItemStats = {
  totalItems: mockItems.length,
  lowStockItems: mockItems.filter(i => i.isLowStock).length,
  categories: [
    { category: 'diaper', categoryName: '尿不湿', count: mockItems.filter(i => i.category === 'diaper').length },
    { category: 'milk', categoryName: '奶粉', count: mockItems.filter(i => i.category === 'milk').length },
    { category: 'food', categoryName: '食品', count: mockItems.filter(i => i.category === 'food').length },
    { category: 'daily', categoryName: '日用品', count: mockItems.filter(i => i.category === 'daily').length },
    { category: 'medicine', categoryName: '药品', count: mockItems.filter(i => i.category === 'medicine').length },
    { category: 'other', categoryName: '其他', count: mockItems.filter(i => i.category === 'other').length },
  ],
  monthlyIn: 28,
  monthlyOut: 35,
}

export const getMockLowStockItems = (): HouseholdItem[] => {
  return mockItems.filter(i => i.isLowStock)
}
