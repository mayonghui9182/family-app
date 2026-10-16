import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import TaskCardMobile from '@/components/TaskCardMobile.vue';

function mountCard(task) {
  return mount(TaskCardMobile, {
    props: { task },
    global: {
      config: { globalProperties: {} }
    }
  });
}

describe('TaskCardMobile D3 移动端任务卡片', () => {
  it('用例1: 渲染标题与中文状态标签（PENDING -> 待办，warning 语义色）', () => {
    const wrapper = mountCard({ id: 1, title: '早上吃药', status: 'PENDING' });
    expect(wrapper.find('.task-title').text()).toBe('早上吃药');
    const tag = wrapper.find('.el-tag');
    expect(tag.text()).toContain('待办');
    expect(wrapper.find('.task-card-mobile').classes()).toContain('status-pending');
  });

  it('用例2: 渲染指派人（assigneeProfile.name）+ 截止日期', () => {
    const wrapper = mountCard({
      id: 2, title: '接孩子', status: 'IN_PROGRESS',
      assigneeProfile: { id: 5, name: '妈妈' }, dueDate: '2026-09-10'
    });
    const text = wrapper.text();
    expect(text).toContain('妈妈');
    expect(text).toContain('2026-09-10');
  });

  it('用例3a: PENDING 渲染 开始做/完成/取消 三个流转按钮', () => {
    const wrapper = mountCard({ id: 3, title: '买菜', status: 'PENDING' });
    const labels = wrapper.findAll('.el-button').map(b => b.text());
    expect(labels.some(t => t.includes('开始做'))).toBe(true);
    expect(labels.some(t => t.includes('完成'))).toBe(true);
    expect(labels.some(t => t.includes('取消'))).toBe(true);
  });

  it('用例3b: COMPLETED 终态无流转按钮，显示"已结束"', () => {
    const wrapper = mountCard({ id: 4, title: '已完成的事', status: 'COMPLETED' });
    expect(wrapper.text()).toContain('已结束');
    const labels = wrapper.findAll('.el-button').map(b => b.text());
    expect(labels.some(t => t.includes('开始做') || t.includes('完成') || t.includes('取消'))).toBe(false);
  });

  it('用例4: 点击"完成"按钮 emit transition(task, COMPLETED)', async () => {
    const task = { id: 5, title: '散步', status: 'PENDING' };
    const wrapper = mountCard(task);
    await wrapper.findAll('.el-button').find(b => b.text().includes('完成')).trigger('click');
    const events = wrapper.emitted('transition');
    expect(events).toBeTruthy();
    expect(events[0]).toEqual([task, 'COMPLETED']);
  });
});
