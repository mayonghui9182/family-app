// frontend/src/api/profiles.js（P-1~P-6 全部走 shared axios，路径完全对齐后端 PersonProfileController.java）
import client from './client.js';

export function listProfiles(params = {}) {
  // P-1 GET /api/v1/person-profiles query={ search?, type? }
  return client.get('/v1/person-profiles', { params });
}
export function getProfile(id) {
  // P-3 GET /api/v1/person-profiles/{id}
  return client.get(`/v1/person-profiles/${id}`);
}
export function createProfile(payload) {
  // P-2 POST /api/v1/person-profiles body=CreatePersonProfileRequest { profileType, name, relation, gender, birthday?, avatarEmoji, tags[], profileData:{...} }
  return client.post('/v1/person-profiles', payload);
}
export function updateProfile(id, payload) {
  // P-4 PUT /api/v1/person-profiles/{id}
  return client.put(`/v1/person-profiles/${id}`, payload);
}
export function deleteProfile(id) {
  // P-5 DELETE /api/v1/person-profiles/{id} → 40901 有待办时绑定返回
  return client.delete(`/v1/person-profiles/${id}`);
}
export function listProfileOptions() {
  // P-6 仅返回档案类型；任务指派成员必须调用 listProfiles。
  return client.get('/v1/person-profiles/options');
}

export default { listProfiles, getProfile, createProfile, updateProfile, deleteProfile, listProfileOptions };
