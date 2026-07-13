import { create } from 'zustand';
import type { Weather, ForecastDay, LifeIndex, Reminder, Todo, Baby, GrowthRecord, Vaccine, Milestone } from '@/types';
import {
  mockWeather,
  mockForecast,
  mockLifeIndex,
  mockReminders,
  mockTodos,
  mockBaby,
  mockGrowthRecords,
  mockVaccines,
  mockMilestones,
} from '@/data/mockData';
import { loadFromStorage, saveToStorage, generateId } from '@/utils/storage';

interface AppState {
  weather: Weather;
  forecast: ForecastDay[];
  lifeIndex: LifeIndex[];
  reminders: Reminder[];
  todos: Todo[];
  babyInfo: Baby;
  growthRecords: GrowthRecord[];
  vaccines: Vaccine[];
  milestones: Milestone[];

  addReminder: (reminder: Omit<Reminder, 'id'>) => void;
  toggleReminder: (id: string) => void;
  deleteReminder: (id: string) => void;

  addTodo: (todo: Omit<Todo, 'id'>) => void;
  toggleTodo: (id: string) => void;
  deleteTodo: (id: string) => void;

  addGrowthRecord: (record: Omit<GrowthRecord, 'id'>) => void;
  toggleVaccine: (id: string) => void;
  toggleMilestone: (id: string) => void;
  updateBabyInfo: (info: Partial<Baby>) => void;
}

export const useAppStore = create<AppState>((set) => ({
  weather: loadFromStorage('weather', mockWeather),
  forecast: loadFromStorage('forecast', mockForecast),
  lifeIndex: loadFromStorage('lifeIndex', mockLifeIndex),
  reminders: loadFromStorage('reminders', mockReminders),
  todos: loadFromStorage('todos', mockTodos),
  babyInfo: loadFromStorage('babyInfo', mockBaby),
  growthRecords: loadFromStorage('growthRecords', mockGrowthRecords),
  vaccines: loadFromStorage('vaccines', mockVaccines),
  milestones: loadFromStorage('milestones', mockMilestones),

  addReminder: (reminder) =>
    set((state) => {
      const newReminder = { ...reminder, id: generateId() };
      const reminders = [...state.reminders, newReminder].sort((a, b) => a.time.localeCompare(b.time));
      saveToStorage('reminders', reminders);
      return { reminders };
    }),

  toggleReminder: (id) =>
    set((state) => {
      const reminders = state.reminders.map((r) =>
        r.id === id ? { ...r, enabled: !r.enabled } : r
      );
      saveToStorage('reminders', reminders);
      return { reminders };
    }),

  deleteReminder: (id) =>
    set((state) => {
      const reminders = state.reminders.filter((r) => r.id !== id);
      saveToStorage('reminders', reminders);
      return { reminders };
    }),

  addTodo: (todo) =>
    set((state) => {
      const newTodo = { ...todo, id: generateId() };
      const todos = [newTodo, ...state.todos];
      saveToStorage('todos', todos);
      return { todos };
    }),

  toggleTodo: (id) =>
    set((state) => {
      const todos = state.todos.map((t) =>
        t.id === id ? { ...t, completed: !t.completed } : t
      );
      saveToStorage('todos', todos);
      return { todos };
    }),

  deleteTodo: (id) =>
    set((state) => {
      const todos = state.todos.filter((t) => t.id !== id);
      saveToStorage('todos', todos);
      return { todos };
    }),

  addGrowthRecord: (record) =>
    set((state) => {
      const newRecord = { ...record, id: generateId() };
      const growthRecords = [...state.growthRecords, newRecord].sort((a, b) =>
        a.date.localeCompare(b.date)
      );
      saveToStorage('growthRecords', growthRecords);
      return { growthRecords };
    }),

  toggleVaccine: (id) =>
    set((state) => {
      const vaccines = state.vaccines.map((v) =>
        v.id === id ? { ...v, completed: !v.completed } : v
      );
      saveToStorage('vaccines', vaccines);
      return { vaccines };
    }),

  toggleMilestone: (id) =>
    set((state) => {
      const milestones = state.milestones.map((m) =>
        m.id === id ? { ...m, achieved: !m.achieved } : m
      );
      saveToStorage('milestones', milestones);
      return { milestones };
    }),

  updateBabyInfo: (info) =>
    set((state) => {
      const babyInfo = { ...state.babyInfo, ...info };
      saveToStorage('babyInfo', babyInfo);
      return { babyInfo };
    }),
}));
