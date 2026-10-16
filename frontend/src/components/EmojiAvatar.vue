<template>
  <div
    class="emoji-avatar"
    :class="[`size-${size}`]"
    :title="name"
    role="img"
    :aria-label="name"
  >
    <span class="emoji">{{ displayEmoji }}</span>
  </div>
</template>
<script setup>
import { computed } from 'vue';
import { avatarEmojiDefault } from '../utils/profileRender.js';
const props = defineProps({
  size: { type: [Number, String], default: 48 },   // 24 / 36 / 48
  emoji: { type: String, default: '' },
  profileType: { type: String, default: null },   // 若 emoji 空，按类型兜底
  name: { type: String, default: '' }
});
const displayEmoji = computed(() => props.emoji || avatarEmojiDefault(props.profileType));
</script>
<style scoped>
.emoji-avatar {
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 50%; background: #ffffff; border: 1px solid #e5e7eb;
  flex-shrink: 0; user-select: none;
}
.size-24 { width: 24px; height: 24px; }
.size-36 { width: 36px; height: 36px; }
.size-48 { width: 48px; height: 48px; }
.size-64 { width: 64px; height: 64px; }
.size-24 .emoji { font-size: 14px; }
.size-36 .emoji { font-size: 22px; }
.size-48 .emoji { font-size: 30px; }
.size-64 .emoji { font-size: 40px; }
</style>
