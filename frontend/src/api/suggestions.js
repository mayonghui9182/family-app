// frontend/src/api/suggestions.js（S-1 /api/v1/person-profiles/{profileId}/suggestions?categoryId=）
import client from './client.js';

export function getProfileSuggestions(profileId, categoryId = null) {
  const params = {};
  if (categoryId != null) params.categoryId = categoryId;
  return client.get(`/v1/person-profiles/${profileId}/suggestions`, { params });
  // 返回 { similarCompletedTasks:[], similarTemplates:[], pendingTasks:[] }
}

export default { getProfileSuggestions };
