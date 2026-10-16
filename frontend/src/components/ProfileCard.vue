<template>
  <el-card class="profile-card" shadow="hover">
    <div class="header">
      <EmojiAvatar :size="48" :emoji="profile.avatarEmoji" :profile-type="profile.profileType" :name="profile.name" />
      <TypeBadge class="badge" :type="profile.profileType" />
    </div>
    <div class="body">
      <h3 class="name">{{ profile.name }}</h3>
      <div class="meta"><span class="relation">关系：{{ profile.relation || '未填' }}</span></div>
      <div class="sub-meta" v-if="subMetaHint">{{ subMetaHint }}</div>
      <div class="tags" v-if="profile.tags && profile.tags.length">
        <el-tag size="large" effect="plain" v-for="t in profile.tags.slice(0,3)" :key="t">{{ t }}</el-tag>
      </div>
    </div>
    <div class="actions">
      <el-button size="large" @click="$emit('view', profile.id)">
        👁️ 查看详情
      </el-button>
      <el-button size="large" type="primary" class="ai-entry-btn" data-ai="true" @click="$emit('aiInterview', profile.id)">
        🤖 AI访谈
      </el-button>
    </div>
  </el-card>
</template>
<script setup>
import { computed } from 'vue';
import EmojiAvatar from './EmojiAvatar.vue';
import TypeBadge from './TypeBadge.vue';
const props = defineProps({ profile: { type: Object, required: true } });
defineEmits(['view', 'aiInterview']);
// 老人一眼看到的关键信息：宝宝显示月龄/过敏几项；老人显示年龄/慢病；宠物显示品种
const subMetaHint = computed(() => {
  const pd = props.profile.profileData || {};
  const t = props.profile.profileType;
  if (t === 'BABY') {
    const a = pd.month_age != null ? `月龄 ${pd.month_age} 个月` : '';
    const al = (pd.allergy_list && pd.allergy_list.length) ? `；过敏 ${pd.allergy_list.length} 项` : '';
    return a + al;
  }
  if (t === 'ELDER') {
    const a = pd.age ? `${pd.age} 岁` : '';
    const d = (pd.chronic_diseases && pd.chronic_diseases.length) ? `；慢病 ${pd.chronic_diseases.join('、')}` : '';
    return a + d;
  }
  if (t === 'ADULT') return pd.age ? `${pd.age} 岁` : '';
  if (t === 'PET') return [pd.breed, pd.age_years!=null?`${pd.age_years} 岁`:null].filter(Boolean).join(' · ') || '';
  return '';
});
</script>
<style scoped>
.profile-card { min-height: 180px; display: flex; flex-direction: column; }
.header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.badge { flex-shrink: 0; }
.name { font-size: 22px; font-weight: 700; margin: 0 0 6px 0; color: #111827; }
.meta { font-size: 16px; color: #606266; margin-bottom: 4px; }
.sub-meta { font-size: 15px; color: #2563EB; font-weight: 600; margin-bottom: 8px; }
.tags { margin: 8px 0; display: flex; flex-wrap: wrap; gap: 6px; }
.actions { margin-top: auto; display: flex; gap: 8px; padding-top: 12px; }
.actions .el-button { flex: 1; min-height: 44px; font-size: 16px; }
</style>
