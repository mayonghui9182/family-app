import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import MetricCard from '@/components/MetricCard.vue';

describe('MetricCard X3 color thresholds', () => {
  it('用例6: success_rate=45%(低于50) -> el-card 红色类 bg-red-light', () => {
    const wrapper = mount(MetricCard, {
      props: { title: 'AI拆解成功率', value: 45, unit: '%', percentStatus: 45, emoji: '🎯' }
    });
    const card = wrapper.find('.metric-card');
    expect(card.classes()).toContain('bg-red-light');
    expect(wrapper.text()).toContain('45%');
  });

  it('用例6b: success_rate=70%(50至74之间) -> 黄色类 bg-yellow-light', () => {
    const wrapper = mount(MetricCard, {
      props: { title: '成功率', value: 70, unit: '%', percentStatus: 70, emoji: '📊' }
    });
    expect(wrapper.find('.metric-card').classes()).toContain('bg-yellow-light');
  });

  it('用例6c: success_rate=90%(大于等于75) -> 绿色类 bg-green-light', () => {
    const wrapper = mount(MetricCard, {
      props: { title: '成功率', value: 90, unit: '%', percentStatus: 90, emoji: '✅' }
    });
    expect(wrapper.find('.metric-card').classes()).toContain('bg-green-light');
  });

  it('用例7: 非百分比场景(value+unit直接显示), delta小字标注增量', () => {
    const wrapper = mount(MetricCard, {
      props: { title: '生成模板版本', value: 12, unit: '个', emoji: '📋', delta: '+4' }
    });
    expect(wrapper.text()).toContain('12');
    expect(wrapper.text()).toContain('个');
    expect(wrapper.text()).toContain('+4');
  });

  it('用例7b: 无delta时不显示delta行', () => {
    const wrapper = mount(MetricCard, {
      props: { title: '学习次数', value: 5, unit: '次', emoji: '🧠' }
    });
    expect(wrapper.text()).not.toMatch(/较\s|\+/);
  });

  // 终审挂账：指标未就绪时页面传的是占位串 '--'，而百分支路做 Math.round(Number('--')) → NaN，
  // 首页顶部就显示「NaN%」。对老人来说这不是「还没加载」，是一块坏掉的屏幕。
  it('用例8: 百分比卡 value 为占位 "--" 时原样显示，不得渲染 NaN', () => {
    const wrapper = mount(MetricCard, {
      props: { title: 'AI拆解成功率', value: '--', unit: '%', percentStatus: null, emoji: '🎯' }
    });
    expect(wrapper.text()).not.toContain('NaN');
    expect(wrapper.text()).toContain('--');
  });
});
