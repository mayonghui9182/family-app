import { describe, it, expect } from 'vitest'
import { typePrioritySort } from '../../utils/profileRender.js'

describe('typePrioritySort（排序 A：宝宝→老人→成人→宠物→其他，组内创建时间升序）', () => {
  const mk = (profileType, idx, createdAtTs) => ({ id: idx, profileType, name: 'x'+idx, createdAt: new Date(createdAtTs).toISOString() })

  it('空数组不崩溃返回空', () => {
    expect(typePrioritySort([])).toEqual([])
  })

  it('单类型不打乱', () => {
    const list = [mk('BABY',2,1700000002000), mk('BABY',1,1700000001000)]
    const r = typePrioritySort(list)
    expect(r.map(x=>x.id)).toEqual([1,2]) // 按 createdAt 升序
  })

  it('5 类型严格按 BABY→ELDER→ADULT→PET→OTHER 分组', () => {
    const list = [mk('OTHER',1,1), mk('PET',2,2), mk('ADULT',3,3), mk('ELDER',4,4), mk('BABY',5,5)]
    const r = typePrioritySort(list)
    expect(r.map(x=>x.profileType)).toEqual(['BABY','ELDER','ADULT','PET','OTHER'])
  })

  it('混合类型组内升序', () => {
    const list = [
      mk('ADULT','a2',1700000020000), mk('ADULT','a1',1700000010000),
      mk('BABY','b3',1700000030000), mk('BABY','b1',1700000010000), mk('BABY','b2',1700000020000)
    ]
    const r = typePrioritySort(list)
    expect(r.map(x=>x.id)).toEqual(['b1','b2','b3', 'a1','a2'])
  })

  it('未知 profileType 排到 OTHER 之后（兜底）', () => {
    const list = [mk('UNKNOWN_TYPE',1,1), mk('OTHER',2,2)]
    const r = typePrioritySort(list)
    expect(r.map(x=>x.profileType)).toEqual(['OTHER','UNKNOWN_TYPE'])
  })
})
