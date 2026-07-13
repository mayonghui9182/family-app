export interface Weather {
  city: string;
  temperature: number;
  condition: string;
  conditionIcon: string;
  humidity: number;
  windSpeed: number;
  airQuality: string;
  airQualityLevel: string;
  feelsLike: number;
  uvIndex: number;
  visibility: number;
}

export interface ForecastDay {
  date: string;
  dayOfWeek: string;
  high: number;
  low: number;
  condition: string;
  conditionIcon: string;
  precipitation: number;
}

export interface LifeIndex {
  id: string;
  name: string;
  level: string;
  description: string;
  icon: string;
}

export interface TravelGuide {
  id: string;
  title: string;
  destination: string;
  image: string;
  description: string;
  content: string;
  duration: string;
  budget: string;
  bestSeason: string;
  highlights: string[];
}

export interface Reminder {
  id: string;
  title: string;
  time: string;
  repeat: 'once' | 'daily' | 'weekly' | 'monthly';
  enabled: boolean;
  category: string;
  note?: string;
}

export interface Todo {
  id: string;
  title: string;
  priority: 'low' | 'medium' | 'high';
  completed: boolean;
  date: string;
  category?: string;
}

export interface Baby {
  name: string;
  birthday: string;
  gender: 'boy' | 'girl';
  avatar: string;
}

export interface GrowthRecord {
  id: string;
  date: string;
  height: number;
  weight: number;
}

export interface Vaccine {
  id: string;
  name: string;
  date: string;
  completed: boolean;
  age: string;
}

export interface Milestone {
  id: string;
  title: string;
  description: string;
  date: string;
  achieved: boolean;
  age: string;
  image?: string;
}
