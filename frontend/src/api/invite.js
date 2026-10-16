import client from './client'

export const inviteApi = {
  create:  (familyId, payload) => client.post(`/families/${familyId}/invites`, payload),
  list:    (familyId, status)  => {
    const params = {}
    if (status) params.status = status
    return client.get(`/families/${familyId}/invites`, { params })
  },
  cancel:  (familyId, inviteId) => client.delete(`/families/${familyId}/invites/${inviteId}`),
  accept:  (payload)           => client.post('/invites/accept', payload)
}

export default inviteApi