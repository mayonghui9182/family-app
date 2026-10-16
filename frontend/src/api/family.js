import client from './client'

export const familyApi = {
  create:        (payload)        => client.post('/families', payload),
  list:          ()               => client.get('/families'),
  getDetail:     (id)            => client.get(`/families/${id}`),
  update:        (id, payload)   => client.put(`/families/${id}`, payload),
  leave:         (id)            => client.post(`/families/${id}/leave`),
  transferOwner: (id, payload)   => {
    const body = { targetUserId: payload.targetUserId ?? payload.newOwnerId }
    return client.post(`/families/${id}/transfer-owner`, body)
  },
  changeRole:    (familyId, userId, payload) => client.post(`/families/${familyId}/members/${userId}`, payload),
  kick:          (familyId, userId) => client.delete(`/families/${familyId}/members/${userId}`),
  remove:        (id)            => client.delete(`/families/${id}`)
}

export default familyApi