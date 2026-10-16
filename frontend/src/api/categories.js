import client from './client'

export const categoryApi = {
  list:   ()              => client.get('/categories'),
  create: (payload)       => client.post('/categories', payload),
  update: (id, payload)   => client.put(`/categories/${id}`, payload),
  del:    (id, mergeToId) => {
    const config = {}
    if (mergeToId) config.params = { mergeTo: mergeToId }
    return client.delete(`/categories/${id}`, config)
  },
  merge:  (id, mergeToId) => client.post(`/categories/${id}/merge`, { mergeTo: mergeToId })
}

export default categoryApi