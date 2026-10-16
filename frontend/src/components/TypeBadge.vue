<template>
  <el-tag
    class="type-badge"
    :type="tagType"
    :size="size"
    effect="dark"
    round
  >{{ text }} {{ emoji }}</el-tag>
</template>
<script setup>
import { computed } from 'vue';
const props = defineProps({
  type: { type: String, required: true }, // BABY/ELDER/ADULT/PET/OTHER
  size: { type: String, default: 'large' }
});
const MAP = {
  BABY:  { t:'danger', text:'宝宝', emoji:'👶' },
  ELDER: { t:'warning', text:'老人', emoji:'👴' },
  ADULT: { t:'primary', text:'成人', emoji:'👨' },
  PET:   { t:'success', text:'宠物', emoji:'🐾' },
  OTHER: { t:'info',    text:'其他', emoji:'👤' }
};
const cfg = computed(() => MAP[props.type] || MAP.OTHER);
const tagType = computed(() => cfg.value.t);
const text = computed(() => cfg.value.text);
const emoji = computed(() => cfg.value.emoji);
</script>
<style scoped>
/* 覆盖 el-tag 更大字号 老人看得见 */
:deep(.el-tag.el-tag--large) {
  min-height: 36px;
  padding: 4px 16px;
  font-size: 17px;
  line-height: 1.2;
}
</style>
